    package com.childfocus.ui

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
    import androidx.compose.ui.graphics.Brush
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.vector.ImageVector
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

    // ── Root screen with bottom navigation ────────────────────────────────────────

    @Composable
    fun SafetyModeScreen(
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
                    SafetyTab.WEB_BLOCKER -> WebBlockerScreen()
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