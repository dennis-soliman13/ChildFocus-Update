package com.childfocus

import android.content.Intent
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
        setContent {
            ChildFocusTheme {
                val safetyOn by viewModel.safetyModeOn.collectAsState()
                val classifyState by viewModel.classifyState.collectAsState()

                if (safetyOn) {
                    SafetyModeScreen(
                        classifyState = classifyState,
                        onTurnOff = { viewModel.toggleSafetyMode() },
                        onDismissBlock = { viewModel.dismissBlock() }
                    )
                } else {
                    LandingScreen(
                        onTurnOn = {
                            // Check if accessibility service is enabled, if not prompt user
                            if (!isAccessibilityServiceEnabled()) {
                                openAccessibilitySettings()
                            }
                            viewModel.toggleSafetyMode()
                        }
                    )
                }
            }
        }
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

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
