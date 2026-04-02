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
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.childfocus.ui.LandingScreen
import com.childfocus.ui.SafetyModeScreen
import com.childfocus.ui.ScreenTimeScreen
import com.childfocus.ui.SessionAuthManager
import com.childfocus.ui.WebBlockerScreen
import com.childfocus.ui.theme.ChildFocusTheme
import com.childfocus.viewmodel.SafetyViewModel

// ─── Nav destinations ─────────────────────────────────────────────────────────

private sealed class Screen(
    val route: String,
    val title: String,
    val icon:  ImageVector
) {
    object Home        : Screen("home",         "Home",         Icons.Default.Home)
    object Safety      : Screen("safety",       "Safety Mode",  Icons.Default.Shield)
    object WebBlocker  : Screen("web_blocker",  "Web Blocker",  Icons.Default.Lock)
    object ScreenTime  : Screen("screen_time",  "Screen Time",  Icons.Default.Schedule)
}

private val NAV_ITEMS = listOf(
    Screen.Home,
    Screen.Safety,
    Screen.WebBlocker,
    Screen.ScreenTime
)

// ─── Theme colours ────────────────────────────────────────────────────────────
private val NavyDark = Color(0xFF0D1B2A)
private val NavyMid  = Color(0xFF1B2D3E)
private val Teal     = Color(0xFF00C9A7)
private val Muted    = Color(0xFF8BA3B8)
private val ErrorRed = Color(0xFFFF5252)

// ─── System-wide parental password ───────────────────────────────────────────
internal const val PARENTAL_PASSWORD = "1234"

internal fun isCorrectPassword(input: String): Boolean =
    input == PARENTAL_PASSWORD

// ─────────────────────────────────────────────────────────────────────────────

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
                        label.equals("Overstimulation", ignoreCase = true) ->
                    viewModel.setBlocked(videoId, score)

                label.equals("error", ignoreCase = true) ->
                    viewModel.setError(videoId)

                else ->
                    viewModel.setAllowed(label, score, cached)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 1. Lock session whenever the app goes to background ───────────────
        // onStop fires on: Home button, Recents button, screen off, or any
        // other app coming to the foreground.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                SessionAuthManager.onAppStop()   // clears isAuthenticated
            }
        })

        // ── 2. Intercept Back button to block task-kill without PIN ───────────
        // This fires on the hardware/gesture back press. We route it through
        // SessionAuthManager which either shows the close-confirm PIN dialog
        // (when Safety Mode is active) or lets the system handle it normally.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val safetyOn = viewModel.safetyModeOn.value
                if (safetyOn) {
                    // Safety Mode is active — require PIN to close
                    SessionAuthManager.onBackPressed()
                    // The CloseConfirmPinDialog in SafetyModeScreen will now
                    // appear; when confirmed it calls onConfirmedClose() below
                    // which calls finishAndRemoveTask().
                } else {
                    // Safety Mode is OFF — normal back behaviour
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

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
                        classifyState    = classifyState,
                        onTurnOff        = { viewModel.turnOffSafetyMode() },
                        onDismissBlock   = { viewModel.dismissBlock() },
                        // ── 3. Called after correct PIN in close-confirm dialog ──
                        // finishAndRemoveTask() closes the app AND removes it from
                        // the Recents screen so the child cannot swipe back to it.
                        onConfirmedClose = { finishAndRemoveTask() }
                    )
                } else {
                    ChildFocusApp(
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
            IntentFilter(ACTION_CLASSIFICATION_RESULT)
        )
        if (isAccessibilityServiceEnabled()) {
            viewModel.onServiceConfirmed()
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(classificationReceiver)
    }

    // ── Protection enable flow ────────────────────────────────────────────────

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
            } catch (_: Exception) { /* fall through */ }
        }
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceFqn = "$packageName/${packageName}.service.ChildFocusAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(serviceFqn, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val ACTION_CLASSIFICATION_RESULT = "com.childfocus.CLASSIFICATION_RESULT"
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
    }
}

// ─── Root composable: bottom-nav shell ───────────────────────────────────────

private val PASSWORD_PROTECTED_ROUTES = setOf(
    Screen.Safety.route,
    Screen.WebBlocker.route,
    Screen.ScreenTime.route
)

@Composable
private fun ChildFocusApp(isWaiting: Boolean, onTurnOn: () -> Unit) {
    val navController = rememberNavController()

    var isParentUnlocked      by remember { mutableStateOf(false) }
    var pendingProtectedRoute by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
            ChildFocusBottomBar(
                navController = navController,
                onProtectedTabClick = { route ->
                    if (isParentUnlocked) {
                        navController.navigate(route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    } else {
                        pendingProtectedRoute = route
                    }
                }
            )
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding)) {

            NavHost(
                navController    = navController,
                startDestination = Screen.Home.route
            ) {
                composable(Screen.Home.route) {
                    LandingScreen(isWaiting = isWaiting, onTurnOn = onTurnOn)
                }
                composable(Screen.Safety.route) {
                    LandingScreen(isWaiting = isWaiting, onTurnOn = onTurnOn)
                }
                composable(Screen.WebBlocker.route) {
                    WebBlockerScreen()
                }
                composable(Screen.ScreenTime.route) {
                    ScreenTimeScreen()
                }
            }

            pendingProtectedRoute?.let { targetRoute ->
                PasswordGateScreen(
                    onSuccess = {
                        isParentUnlocked      = true
                        pendingProtectedRoute = null
                        navController.navigate(targetRoute) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    },
                    onCancel = {
                        pendingProtectedRoute = null
                    }
                )
            }
        }
    }
}

// ─── Bottom navigation bar ────────────────────────────────────────────────────

@Composable
private fun ChildFocusBottomBar(
    navController:        NavHostController,
    onProtectedTabClick:  (String) -> Unit
) {
    val backStack    by navController.currentBackStackEntryAsState()
    val currentRoute  = backStack?.destination?.route

    NavigationBar(
        containerColor = NavyMid,
        tonalElevation = 0.dp
    ) {
        NAV_ITEMS.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick  = {
                    if (!selected) {
                        if (screen.route in PASSWORD_PROTECTED_ROUTES) {
                            onProtectedTabClick(screen.route)
                        } else {
                            navController.navigate(screen.route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    }
                },
                icon  = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Teal,
                    selectedTextColor   = Teal,
                    indicatorColor      = Teal.copy(alpha = .15f),
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted
                )
            )
        }
    }
}

// ─── Parental password gate ───────────────────────────────────────────────────

@Composable
private fun PasswordGateScreen(
    onSuccess: () -> Unit,
    onCancel:  () -> Unit
) {
    var input     by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDark.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth(0.85f)
                .background(NavyMid, shape = RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Lock,
                contentDescription = null,
                tint               = Teal,
                modifier           = Modifier.height(40.dp)
            )

            Text(
                text       = "Parental Access",
                color      = Color.White,
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text     = "Enter your password to unlock all parental controls.",
                color    = Muted,
                fontSize = 14.sp
            )

            OutlinedTextField(
                value                = input,
                onValueChange        = { input = it; showError = false },
                label                = { Text("Password", color = Muted) },
                singleLine           = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError              = showError,
                supportingText       = if (showError) {
                    { Text("Incorrect password", color = ErrorRed) }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Teal,
                    unfocusedBorderColor = Muted,
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                    cursorColor          = Teal
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    if (isCorrectPassword(input)) {
                        onSuccess()
                    } else {
                        showError = true
                        input     = ""
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("Unlock", color = NavyDark, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyMid)
            ) {
                Text("Cancel", color = Muted)
            }
        }
    }
}