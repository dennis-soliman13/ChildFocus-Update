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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.childfocus.ui.LandingScreen
import com.childfocus.ui.SafetyModeScreen
import com.childfocus.ui.ScreenTimeScreen       // add this screen in your ui package
import com.childfocus.ui.WebBlockerScreen
import com.childfocus.ui.theme.ChildFocusTheme
import com.childfocus.viewmodel.SafetyViewModel

// ─── Nav destinations ─────────────────────────────────────────────────────────
//
// FIX: renamed property from `label` → `title` so it never collides with the
//      `label` named parameter of NavigationBarItem { label = { … } }.
//      That collision was the source of all three "Unresolved reference" errors.
//
private sealed class Screen(
    val route: String,
    val title: String,          // ← was `label`; renamed to `title`
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
//
// The password is stored as a simple constant here so that every protected
// feature (Safety Mode toggle, Web Blocker settings, Screen Time limits) shares
// the same gate.  Swap this out for SharedPreferences / encrypted storage if
// you want the parent to be able to change the PIN at runtime.
//
internal const val PARENTAL_PASSWORD = "1234"   // ← change to your desired PIN

/**
 * Returns true when [input] matches the system-wide parental password.
 * Centralised here so all screens call the same check.
 */
internal fun isCorrectPassword(input: String): Boolean =
    input == PARENTAL_PASSWORD

// ─────────────────────────────────────────────────────────────────────────────

/**
 * MainActivity — v8
 *
 * Changes from v7:
 *   • Fixed "Unresolved reference 'label'" by renaming the sealed-class
 *     property to `title` (the named lambda param inside NavigationBarItem
 *     shadows any outer `label`).
 *   • Added a system-wide parental password gate:
 *       – Turning Safety Mode ON requires the password.
 *       – Navigating to Web Blocker or Screen Time tabs requires the password.
 *       – One shared [PasswordGateScreen] composable handles the prompt.
 *   • Added a fourth tab: Screen Time.
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
                    // Full-screen safety takeover — turning it OFF is password-gated
                    // inside SafetyModeScreen itself (pass the checker lambda).
                    SafetyModeScreen(
                        classifyState  = classifyState,
                        onTurnOff      = { viewModel.turnOffSafetyMode() },
                        onDismissBlock = { viewModel.dismissBlock() }
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

// Routes that require the parental password before showing their content.
private val PASSWORD_PROTECTED_ROUTES = setOf(
    Screen.Safety.route,
    Screen.WebBlocker.route,
    Screen.ScreenTime.route
)

@Composable
private fun ChildFocusApp(isWaiting: Boolean, onTurnOn: () -> Unit) {
    val navController = rememberNavController()

    // Once the parent enters the correct password, this flips to true for the
    // entire session — they can freely switch between Safety Mode, Web Blocker,
    // and Screen Time without being asked again.
    var isParentUnlocked by remember { mutableStateOf(false) }

    // Holds the destination route the parent was trying to reach when the gate
    // appeared. null means the gate is not visible.
    var pendingProtectedRoute by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
            ChildFocusBottomBar(
                navController = navController,
                onProtectedTabClick = { route ->
                    if (isParentUnlocked) {
                        // Already unlocked this session — navigate straight away.
                        navController.navigate(route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    } else {
                        // Show the gate once; remember where we wanted to go.
                        pendingProtectedRoute = route
                    }
                }
            )
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding)) {

            // ── Main nav graph ────────────────────────────────────────────────
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

            // ── Password gate overlay (shown only once per session) ───────────
            pendingProtectedRoute?.let { targetRoute ->
                PasswordGateScreen(
                    onSuccess = {
                        isParentUnlocked      = true   // unlock ALL protected tabs
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
                            // Ask for the parental password first.
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
                // FIX: use `screen.title` (renamed property) — no more clash
                //      with the NavigationBarItem lambda param named `label`.
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

/**
 * Full-screen overlay that asks for the parental password before granting
 * access to [featureName].  Shared by Safety Mode, Web Blocker, and
 * Screen Time — any feature that should be parent-only.
 */
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