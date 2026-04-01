package com.childfocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.childfocus.ui.LandingScreen
import com.childfocus.ui.SafetyModeScreen
import com.childfocus.ui.theme.ChildFocusTheme
import com.childfocus.viewmodel.SafetyViewModel

/**
 * MainActivity — v6
 *
 * Responsibilities:
 *   1. Render the two top-level screens (LandingScreen / SafetyModeScreen)
 *      driven by SafetyViewModel.
 *   2. Register / unregister the LocalBroadcastReceiver that listens for
 *      "com.childfocus.CLASSIFICATION_RESULT" intents from the accessibility
 *      service and routes them to the correct ViewModel state.
 *   3. Handle the "Proton VPN-style" accessibility-settings round-trip:
 *        • open accessibility settings on button tap
 *        • auto-activate safety mode on onResume() if service is now enabled
 *
 * Broadcast extras from ChildFocusAccessibilityService (v6):
 *   "video_id"    String  — 11-char ID or title excerpt
 *   "oir_label"   String  — "Analyzing" | "Educational" | "Neutral" |
 *                           "Overstimulating" | "error"
 *   "score_final" Float   — fused score [0, 1]
 *   "cached"      Boolean — true if result served from cache (Tier 0)
 *   "tier"        Int     — 0–5 (which tier resolved this result)
 *   "confidence"  String  — "FULL" | "PARTIAL" | "LOW" | "NONE"
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SafetyViewModel by viewModels()

    // ── Classification broadcast receiver ─────────────────────────────────────

    private val classificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val videoId = intent.getStringExtra("video_id") ?: "unknown"
            val label   = intent.getStringExtra("oir_label") ?: "Neutral"
            val score   = intent.getFloatExtra("score_final", 0f)
            val cached  = intent.getBooleanExtra("cached", false)

            when {
                label.equals("Analyzing", ignoreCase = true) ->
                    viewModel.setAnalyzing(videoId)

                label.equals("Overstimulating", ignoreCase = true) ||
                label.equals("Overstimulation",  ignoreCase = true) ->
                    viewModel.setBlocked(videoId, score)

                label.equals("error", ignoreCase = true) ->
                    viewModel.setError(videoId)

                // Educational, Neutral — safe content
                else ->
                    viewModel.setAllowed(label, score, cached)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }

        setContent {
            ChildFocusTheme {
                val safetyOn      by viewModel.safetyModeOn.collectAsState()
                val classifyState by viewModel.classifyState.collectAsState()
                val isWaiting     by viewModel.isWaitingForService.collectAsState()

                if (safetyOn) {
                    SafetyModeScreen(
                        classifyState  = classifyState,
                        onTurnOff      = { viewModel.turnOffSafetyMode() },
                        onDismissBlock = { viewModel.dismissBlock() }
                    )
                } else {
                    LandingScreen(
                        isWaiting = isWaiting,
                        onTurnOn  = { enableProtection() }
                    )
                }
            }
        }
    }

    /**
     * onResume handles both:
     *   1. Normal app resume — register the broadcast receiver.
     *   2. Return from Accessibility Settings — if service now active, auto-confirm.
     */
    override fun onResume() {
        super.onResume()

        // Register the classification result receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            classificationReceiver,
            IntentFilter(ACTION_CLASSIFICATION_RESULT)
        )

        // Proton VPN-style auto-confirm after user enables service in Settings
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister to avoid leaking the receiver when the app is not in foreground
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(classificationReceiver)
    }

    // ── Protection enable flow ────────────────────────────────────────────────

    private fun enableProtection() {
        if (isAccessibilityServiceEnabled()) {
            // Service already active (e.g. user killed app and re-opened)
            viewModel.onServiceConfirmed()
        } else {
            viewModel.setWaitingForService(true)
            openAccessibilitySettings()
        }
    }

    /**
     * On Android 13+ deep-links directly to ChildFocus's accessibility
     * detail page so the user sees exactly ONE toggle.
     * Falls back to the generic list on older Android versions.
     */
    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // ACTION_ACCESSIBILITY_DETAILS_SETTINGS added in API 33.
                // Using the raw action string here avoids compile-time
                // resolution issues when build configs disagree on compileSdk.
                val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Deep-link unsupported on this device — fall through.
            }
        }
        // Fallback: generic accessibility list (Android 12 and below)
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Returns true when the ChildFocus accessibility service is registered
     * and enabled in Android's Accessibility Settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceFqn =
            "$packageName/${packageName}.service.ChildFocusAccessibilityService"
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.split(":").any {
                it.equals(serviceFqn, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_CLASSIFICATION_RESULT = "com.childfocus.CLASSIFICATION_RESULT"
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
    }
}
