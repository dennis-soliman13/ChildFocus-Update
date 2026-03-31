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

class MainActivity : ComponentActivity() {

    private val viewModel: SafetyViewModel by viewModels()

    private val classificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val videoId = intent.getStringExtra("video_id") ?: "unknown"
            val label   = intent.getStringExtra("oir_label") ?: "Neutral"
            val score   = intent.getFloatExtra("score_final", 0f)
            val cached  = intent.getBooleanExtra("cached", false)

            when {
                label.equals("Analyzing", ignoreCase = true) ->
                    viewModel.setAnalyzing(videoId)
                label.equals("Overstimulating", ignoreCase = true) ->
                    viewModel.setBlocked(videoId, score)
                label.equals("error", ignoreCase = true) ->
                    viewModel.setError(videoId)
                else ->
                    viewModel.setAllowed(label, score, cached)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            classificationReceiver,
            IntentFilter("com.childfocus.CLASSIFICATION_RESULT")
        )
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(classificationReceiver)
    }

    private fun enableProtection() {
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        } else {
            viewModel.setWaitingForService(true)
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                // fall through to generic settings
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