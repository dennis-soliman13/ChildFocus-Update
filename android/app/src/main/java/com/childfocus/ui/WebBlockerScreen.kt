package com.childfocus.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childfocus.service.WebBlockerManager
import kotlinx.coroutines.delay

// ─── Palette ─────────────────────────────────────────────────────────────────
private val NavyDark = Color(0xFF0D1B2A)
private val NavyMid  = Color(0xFF1B2D3E)
private val Teal     = Color(0xFF00C9A7)
private val RedAlert = Color(0xFFE63946)
private val OffWhite = Color(0xFFF0F4F8)
private val Muted    = Color(0xFF8BA3B8)

// ─── Presets ─────────────────────────────────────────────────────────────────
private data class Preset(val label: String, val domains: List<String>)

private val PRESETS = listOf(
    Preset("🔞 Adult",    listOf("pornhub.com","xvideos.com","xnxx.com","onlyfans.com","redtube.com")),
    Preset("🎰 Gambling", listOf("bet365.com","pokerstars.com","casino.com","draftkings.com","fanduel.com")),
    Preset("⚔️ Violence", listOf("liveleak.com","bestgore.com","goregrish.com")),
    Preset("🎮 Gaming",   listOf("roblox.com","fortnite.com","miniclip.com","poki.com","crazygames.com")),
    Preset("📱 Social",   listOf("tiktok.com","instagram.com","snapchat.com","twitter.com","x.com")),
)

internal const val DEFAULT_PIN   = "1234"
internal const val PREFS_NAME    = "web_blocker_prefs"
internal const val PREFS_PIN_KEY = "parent_pin"

// ─── Helper: check if WebBlockerAccessibilityService is enabled ───────────────
fun isWebBlockerServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabled.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name.contains("WebBlocker", ignoreCase = true)
    }
}

// ─── Entry point ──────────────────────────────────────────────────────────────
@Composable
fun WebBlockerScreen() {
    val context = LocalContext.current
    val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var isAuthenticated by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState    = isAuthenticated,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        label          = "auth"
    ) { authenticated ->
        if (authenticated) {
            WebBlockerDashboard(onLock = { isAuthenticated = false })
        } else {
            PinGateScreen(
                storedPin    = prefs.getString(PREFS_PIN_KEY, DEFAULT_PIN) ?: DEFAULT_PIN,
                onPinCorrect = { isAuthenticated = true }
            )
        }
    }
}

// ─── PIN gate screen ──────────────────────────────────────────────────────────
@Composable
internal fun PinGateScreen(
    storedPin    : String,
    onPinCorrect : () -> Unit,
    subtitle     : String = "Enter your PIN to manage blocked sites",
) {
    var pin      by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    val offsetX  = remember { Animatable(0f) }

    LaunchedEffect(hasError) {
        if (hasError) {
            repeat(4) {
                offsetX.animateTo( 10f, tween(50))
                offsetX.animateTo(-10f, tween(50))
            }
            offsetX.animateTo(0f, tween(50))
        }
    }

    fun submit() {
        if (pin == storedPin) { onPinCorrect() }
        else { hasError = true; pin = "" }
    }

    Box(
        Modifier.fillMaxSize().background(NavyDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .offset(x = offsetX.value.dp)
        ) {
            Icon(Icons.Default.Lock, null, tint = Teal, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(16.dp))
            Text("Parent Access", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OffWhite)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            // PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    i < pin.length -> Teal
                                    hasError       -> RedAlert.copy(alpha = .5f)
                                    else           -> NavyMid
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = hasError) {
                Text("Incorrect PIN — try again", color = RedAlert, fontSize = 12.sp)
            }
            Spacer(Modifier.height(28.dp))

            // Numpad
            listOf(
                listOf("1","2","3"),
                listOf("4","5","6"),
                listOf("7","8","9"),
                listOf("" ,"0","⌫"),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { key ->
                        Surface(
                            onClick  = {
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
                            enabled  = key.isNotEmpty(),
                            shape    = RoundedCornerShape(50),
                            color    = if (key.isNotEmpty()) NavyMid else Color.Transparent,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(key, fontSize = 22.sp, color = OffWhite, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ─── Main dashboard ───────────────────────────────────────────────────────────
@Composable
private fun WebBlockerDashboard(onLock: () -> Unit) {
    val context      = LocalContext.current
    // Safety net: init here too in case ChildFocusApp.onCreate() did not run first
    WebBlockerManager.init(context)

    var blockedSites by remember { mutableStateOf(WebBlockerManager.getBlockedSites().toList().sorted()) }
    var isEnabled    by remember { mutableStateOf(WebBlockerManager.isEnabled) }
    var newSite      by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }
    var serviceOn    by remember { mutableStateOf(isWebBlockerServiceEnabled(context)) }

    val focusRequester = remember { FocusRequester() }
    val focusManager   = LocalFocusManager.current

    fun refresh() {
        blockedSites = WebBlockerManager.getBlockedSites().toList().sorted()
    }

    // Poll service status every 2 seconds so banner updates when user enables it
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = isWebBlockerServiceEnabled(context)
            delay(2000)
        }
    }

    if (showPinDialog) {
        ChangePinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { showPinDialog = false }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(NavyDark)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(NavyMid, Color(0xFF243B55))))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, null, tint = Teal, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Web Blocker", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OffWhite,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { showPinDialog = true }) {
                        Icon(Icons.Default.Settings, "Change PIN", tint = Muted)
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.Lock, "Lock", tint = Muted)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Master toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isEnabled) "Blocking: ON" else "Blocking: OFF",
                        color = if (isEnabled) Teal else Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked         = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            WebBlockerManager.isEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor       = Teal,
                            checkedTrackColor       = Teal.copy(alpha = .3f),
                            uncheckedThumbColor     = Muted,
                            uncheckedTrackColor     = Muted.copy(alpha = .2f),
                        )
                    )
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Accessibility service status banner ───────────────────────
            item {
                if (!serviceOn) {
                    Surface(
                        shape  = RoundedCornerShape(12.dp),
                        color  = RedAlert.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, RedAlert.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = RedAlert, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Accessibility Service Not Enabled",
                                    color = RedAlert, fontWeight = FontWeight.Bold, fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The web blocker won't work until you enable it in Android Accessibility Settings.",
                                color = OffWhite.copy(alpha = 0.8f), fontSize = 12.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Steps: Settings → Accessibility → Installed Apps → ChildFocus Web Blocker → Enable",
                                color = Muted, fontSize = 11.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                                shape  = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Accessibility Settings", fontWeight = FontWeight.Bold, color = NavyDark)
                            }
                        }
                    }
                } else {
                    Surface(
                        shape  = RoundedCornerShape(12.dp),
                        color  = Teal.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Teal.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Accessibility Service Active — Blocking is running", color = Teal, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Add site input ────────────────────────────────────────────
            item {
                Text("Add Site to Block", color = Muted, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value             = newSite,
                        onValueChange     = { newSite = it },
                        placeholder       = { Text("e.g. example.com", color = Muted, fontSize = 13.sp) },
                        singleLine        = true,
                        keyboardOptions   = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions   = KeyboardActions(onDone = {
                            if (newSite.isNotBlank()) {
                                WebBlockerManager.addSite(newSite.trim())
                                newSite = ""
                                focusManager.clearFocus()
                                refresh()
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Teal,
                            unfocusedBorderColor = Muted.copy(alpha = .4f),
                            focusedTextColor     = OffWhite,
                            unfocusedTextColor   = OffWhite,
                            cursorColor          = Teal,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    )
                    Button(
                        onClick = {
                            if (newSite.isNotBlank()) {
                                WebBlockerManager.addSite(newSite.trim())
                                newSite = ""
                                focusManager.clearFocus()
                                refresh()
                            }
                        },
                        colors   = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape    = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.Add, "Add", tint = NavyDark)
                    }
                }
            }

            // ── Quick presets ─────────────────────────────────────────────
            item {
                Text("Quick Presets", color = Muted, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { preset ->
                                PresetChip(
                                    preset   = preset,
                                    onClick  = { preset.domains.forEach { WebBlockerManager.addSite(it) }; refresh() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Blocked list header ───────────────────────────────────────
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                ) {
                    Text("Blocked Sites", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = Teal.copy(alpha = .15f)) {
                        Text("${blockedSites.size}", color = Teal, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    if (blockedSites.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { WebBlockerManager.clearAll(); refresh() }) {
                            Text("Clear All", color = RedAlert, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────
            if (blockedSites.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, null, tint = Muted, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No sites blocked yet", color = Muted, fontSize = 14.sp)
                            Text("Add a domain above or use a quick preset", color = Muted.copy(alpha = .6f), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(blockedSites, key = { it }) { site ->
                    BlockedSiteRow(site = site, onRemove = { WebBlockerManager.removeSite(site); refresh() })
                }
            }
        }
    }
}

// ─── Change PIN dialog ────────────────────────────────────────────────────────
@Composable
private fun ChangePinDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context  = LocalContext.current
    val prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var current  by remember { mutableStateOf("") }
    var newPin   by remember { mutableStateOf("") }
    var confirm  by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = NavyMid,
        titleContentColor = OffWhite,
        title = { Text("Change Parent PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinTextField("Current PIN", current)        { current = it.filter(Char::isDigit).take(4) }
                PinTextField("New PIN (4 digits)", newPin)  { newPin  = it.filter(Char::isDigit).take(4) }
                PinTextField("Confirm New PIN", confirm)    { confirm = it.filter(Char::isDigit).take(4) }
                errorMsg?.let { Text(it, color = RedAlert, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val stored = prefs.getString(PREFS_PIN_KEY, DEFAULT_PIN) ?: DEFAULT_PIN
                when {
                    current != stored  -> errorMsg = "Current PIN is incorrect."
                    newPin.length != 4 -> errorMsg = "New PIN must be exactly 4 digits."
                    newPin != confirm  -> errorMsg = "PINs do not match."
                    else -> { prefs.edit().putString(PREFS_PIN_KEY, newPin).apply(); onConfirm() }
                }
            }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun PinTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value             = value,
        onValueChange     = onValueChange,
        label             = { Text(label, color = Muted, fontSize = 12.sp) },
        singleLine        = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions   = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Teal,
            unfocusedBorderColor = Muted.copy(alpha = .4f),
            focusedTextColor     = OffWhite,
            unfocusedTextColor   = OffWhite,
            cursorColor          = Teal,
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// ─── Preset chip ──────────────────────────────────────────────────────────────
@Composable
private fun PresetChip(preset: Preset, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(12.dp),
        color    = NavyMid,
        border   = BorderStroke(1.dp, Muted.copy(alpha = .2f)),
        modifier = modifier
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(preset.label.take(2), fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                preset.label.drop(2).trim(), color = OffWhite, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Blocked site row ─────────────────────────────────────────────────────────
@Composable
private fun BlockedSiteRow(site: String, onRemove: () -> Unit) {
    Card(
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = NavyMid),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Block, null, tint = RedAlert.copy(alpha = .7f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                site, color = OffWhite, fontSize = 14.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Remove $site", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}