package com.childfocus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childfocus.viewmodel.ClassifyState

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class SafetyTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    WEB_BLOCKER("Web Blocker", Icons.Default.Language),
    SCREEN_TIME("Screen Time", Icons.Default.Timer)
}

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
fun SafetyModeScreen(
    classifyState: ClassifyState,
    onTurnOff: () -> Unit,
    onDismissBlock: () -> Unit,
    /** Called only after the user correctly enters the PIN in the close dialog */
    onConfirmedClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs   = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val storedPin = prefs.getString(PREFS_PIN_KEY, DEFAULT_PIN) ?: DEFAULT_PIN

    val isAuthenticated    = SessionAuthManager.isAuthenticated
    val showingCloseDialog = SessionAuthManager.isShowingCloseConfirm

    // ── Close-confirm PIN dialog ───────────────────────────────────────────────
    // Shown on top of whatever screen is current when the user tries to
    // back out / swipe away the app from Recents.
    if (showingCloseDialog) {
        CloseConfirmPinDialog(
            storedPin = storedPin,
            onConfirmed = {
                SessionAuthManager.confirmClose()
                onConfirmedClose()          // Activity calls finishAndRemoveTask()
            },
            onDismiss = { SessionAuthManager.cancelClose() }
        )
    }

    // ── Main auth gate ────────────────────────────────────────────────────────
    AnimatedContent(
        targetState    = isAuthenticated,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        label          = "global_auth"
    ) { authenticated ->
        if (authenticated) {
            AuthenticatedSafetyScreen(
                classifyState  = classifyState,
                onTurnOff      = onTurnOff,
                onDismissBlock = onDismissBlock
            )
        } else {
            PinGateScreen(
                storedPin    = storedPin,
                subtitle     = "Enter your PIN to access Safety Mode",
                onPinCorrect = { SessionAuthManager.authenticate() }
            )
        }
    }
}

// ── Close-confirm PIN dialog ──────────────────────────────────────────────────
// Shown when the user presses Back or swipes the app away from Recents.
// They must type the correct PIN to actually close the app.

@Composable
private fun CloseConfirmPinDialog(
    storedPin   : String,
    onConfirmed : () -> Unit,
    onDismiss   : () -> Unit
) {
    var pin      by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    val offsetX  = remember { Animatable(0f) }

    LaunchedEffect(hasError) {
        if (hasError) {
            repeat(4) {
                offsetX.animateTo( 8f, tween(50))
                offsetX.animateTo(-8f, tween(50))
            }
            offsetX.animateTo(0f, tween(50))
        }
    }

    fun submit() {
        if (pin == storedPin) onConfirmed()
        else { hasError = true; pin = "" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF0D2137),
        title = {
            Text(
                text       = "🔒 Close ChildFocus?",
                color      = Color(0xFF4FC3F7),
                fontWeight = FontWeight.Bold,
                fontSize   = 17.sp
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = offsetX.value.dp)
            ) {
                Text(
                    text      = "Enter your PIN to close the app.\nThis keeps your child protected.",
                    color     = Color(0xFF90CAF9),
                    fontSize  = 13.sp,
                    textAlign = TextAlign.Center
                )

                // PIN dot indicators
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        i < pin.length -> Color(0xFF4FC3F7)
                                        hasError       -> Color(0xFFE63946).copy(alpha = .5f)
                                        else           -> Color(0xFF1B2D3E)
                                    }
                                )
                        )
                    }
                }

                AnimatedVisibility(visible = hasError) {
                    Text("Incorrect PIN — try again", color = Color(0xFFE63946), fontSize = 12.sp)
                }

                // Numpad
                listOf(
                    listOf("1","2","3"),
                    listOf("4","5","6"),
                    listOf("7","8","9"),
                    listOf("" ,"0","⌫"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { key ->
                            Surface(
                                onClick = {
                                    when (key) {
                                        ""  -> Unit
                                        "⌫" -> { if (pin.isNotEmpty()) pin = pin.dropLast(1); hasError = false }
                                        else -> {
                                            if (pin.length < 4) {
                                                pin += key; hasError = false
                                                if (pin.length == 4) submit()
                                            }
                                        }
                                    }
                                },
                                shape    = RoundedCornerShape(50),
                                color    = if (key.isEmpty()) Color.Transparent else Color(0xFF1B2D3E),
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (key.isNotEmpty()) {
                                        Text(key, fontSize = 20.sp, color = Color(0xFFF0F4F8), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {},   // PIN entry auto-submits on 4th digit
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8BA3B8))
            }
        }
    )
}

// ── Authenticated shell (bottom nav + tabs) ───────────────────────────────────

@Composable
private fun AuthenticatedSafetyScreen(
    classifyState: ClassifyState,
    onTurnOff: () -> Unit,
    onDismissBlock: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(SafetyTab.HOME) }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0D1B2A), Color(0xFF0A2540))
    )

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0D1B2A),
                tonalElevation = 0.dp
            ) {
                SafetyTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick  = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector        = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(text = tab.label, fontSize = 11.sp)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Color(0xFF4FC3F7),
                            selectedTextColor   = Color(0xFF4FC3F7),
                            unselectedIconColor = Color(0xFF546E7A),
                            unselectedTextColor = Color(0xFF546E7A),
                            indicatorColor      = Color(0xFF1E3A5F)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgGradient)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                SafetyTab.HOME        -> HomeTabContent(
                    classifyState  = classifyState,
                    onTurnOff      = onTurnOff,
                    onDismissBlock = onDismissBlock
                )
                SafetyTab.WEB_BLOCKER -> WebBlockerDashboard(
                    onLock = { SessionAuthManager.lock() }
                )
                SafetyTab.SCREEN_TIME -> ScreenTimeScreen()
            }
        }
    }
}

// ── Home tab ──────────────────────────────────────────────────────────────────

@Composable
private fun HomeTabContent(
    classifyState: ClassifyState,
    onTurnOff: () -> Unit,
    onDismissBlock: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Lock,
                contentDescription = "Protected",
                tint               = Color(0xFF4FC3F7),
                modifier           = Modifier.size(72.dp)
            )
            Text(
                text          = "PROTECTED",
                fontSize      = 28.sp,
                fontWeight    = FontWeight.Bold,
                color         = Color(0xFF4FC3F7),
                letterSpacing = 4.sp
            )
            Text(
                text      = "ChildFocus is actively monitoring\nYouTube for your child",
                color     = Color(0xFF90CAF9),
                fontSize  = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusCard(classifyState = classifyState, onDismissBlock = onDismissBlock)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick  = onTurnOff,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFEF9A9A)
                )
            ) {
                Icon(
                    imageVector        = Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text       = "Turn Off Safety Mode",
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp
                )
            }
        }
    }
}

// ── Status card ───────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(
    classifyState: ClassifyState,
    onDismissBlock: () -> Unit
) {
    val (bgColor, textColor, title, subtitle) = when (classifyState) {
        is ClassifyState.Idle -> StatusInfo(
            bg    = Color(0xFF1E3A5F),
            text  = Color(0xFF90CAF9),
            title = "Watching...",
            sub   = "Waiting for YouTube activity"
        )
        is ClassifyState.Analyzing -> StatusInfo(
            bg    = Color(0xFF1E3A5F),
            text  = Color(0xFFFFD54F),
            title = "Analyzing",
            sub   = classifyState.videoId.take(60)
        )
        is ClassifyState.Allowed -> StatusInfo(
            bg    = Color(0xFF1B3A2A),
            text  = Color(0xFF81C784),
            title = "✓ ${classifyState.label}",
            sub   = "Content is safe"
        )
        is ClassifyState.Error -> StatusInfo(
            bg    = Color(0xFF2A1A3E),
            text  = Color(0xFFCE93D8),
            title = "⚠ Classification Error",
            sub   = classifyState.videoId.take(60)
        )
        is ClassifyState.Blocked -> StatusInfo(
            bg    = Color(0xFF3E1A1A),
            text  = Color(0xFFEF9A9A),
            title = "⛔ Overstimulating Content Blocked",
            sub   = "Score: ${"%.2f".format(classifyState.score)}"
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = bgColor
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text       = title,
                color      = textColor,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                textAlign  = TextAlign.Center
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text      = subtitle,
                    color     = textColor.copy(alpha = 0.8f),
                    fontSize  = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            if (classifyState is ClassifyState.Blocked) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDismissBlock) {
                    Text("Dismiss", color = Color(0xFFEF9A9A))
                }
            }
        }
    }
}

private data class StatusInfo(
    val bg: Color,
    val text: Color,
    val title: String,
    val sub: String
)