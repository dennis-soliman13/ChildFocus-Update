package com.childfocus

import android.content.Intent
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
import com.childfocus.ui.theme.ChildFocusTheme
import com.childfocus.ui.LandingScreen
import com.childfocus.ui.SafetyModeScreen
import com.childfocus.viewmodel.SafetyViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SafetyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Request POST_NOTIFICATIONS (required on Android 13+) ─────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
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
     * Called every time the user returns to the app — including from Accessibility Settings.
     *
     * This is the "Proton VPN" moment: as soon as the user flips the toggle in settings
     * and comes back, onResume fires, we detect the service is now enabled, and the UI
     * transitions automatically without any extra tap.
     */
    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        }
    }

    /**
     * The main "Enable Protection" equivalent of Proton VPN's "Connect".
     *
     * 1. If service already enabled → activate immediately, no settings trip.
     * 2. If not → mark UI as waiting, then deep-link to ChildFocus's own
     *    accessibility settings page (Android 13+) or the generic list (older).
     */
    private fun enableProtection() {
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        } else {
            viewModel.setWaitingForService(true)
            openAccessibilitySettingsForChildFocus()
        }
    }

    /**
     * On Android 13+ deep-links directly to ChildFocus's accessibility detail page
     * so the user sees exactly ONE toggle — no hunting through a list.
     * Falls back to the generic list on older Android versions.
     */
    private fun openAccessibilitySettingsForChildFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Deep-link unavailable on this device — fall through
            }
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${packageName}.service.ChildFocusAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }
}
