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
// Authentication is now managed globally by SafetyModeScreen via
// SessionAuthManager.  By the time this composable is displayed the parent is
// already authenticated, so we jump straight to the dashboard.
// The `onLock` lambda is wired to SessionAuthManager.lock() by the caller.
@Composable
fun WebBlockerScreen() {
    // No local isAuthenticated — the global gate in SafetyModeScreen handles it.
    WebBlockerDashboard(onLock = { SessionAuthManager.lock() })
}

// ─── PIN gate screen ──────────────────────────────────────────────────────────
// Still kept here (internal) so SafetyModeScreen can call it directly.
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
                            shape    = RoundedCornerShape(50),
                            color    = if (key.isEmpty()) Color.Transparent else NavyMid,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (key.isNotEmpty()) {
                                    Text(key, fontSize = 22.sp, color = OffWhite, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ─── Dashboard (previously shown after local auth) ───────────────────────────
@Composable
internal fun WebBlockerDashboard(onLock: () -> Unit) {
    val context      = LocalContext.current
    val prefs        = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var blockedSites by remember { mutableStateOf(WebBlockerManager.getBlockedSites().toList()) }
    var newDomain    by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(isWebBlockerServiceEnabled(context)) }

    fun refresh() { blockedSites = WebBlockerManager.getBlockedSites().toList() }

    // Re-check the accessibility service every second while the screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            serviceEnabled = isWebBlockerServiceEnabled(context)
            delay(1_000)
        }
    }

    if (showPinDialog) {
        ChangePinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { showPinDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0D1B2A), Color(0xFF0A1A2E)))
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "🌐 Web Blocker", fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = Teal
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Block harmful websites on this device",
                        fontSize = 13.sp, color = Muted
                    )
                }
                // Settings / Change PIN
                IconButton(onClick = { showPinDialog = true }) {
                    Icon(Icons.Default.Settings, "Settings", tint = Muted)
                }
                // Lock session
                IconButton(onClick = onLock) {
                    Icon(Icons.Default.Lock, "Lock", tint = Muted)
                }
            }
        }

        // ── Service status banner ─────────────────────────────────────────────
        item {
            val (bannerColor, bannerText, bannerIcon) = if (serviceEnabled)
                Triple(Color(0xFF0D2E1E), "Accessibility service is active", Icons.Default.CheckCircle)
            else
                Triple(Color(0xFF2E1A0D), "Accessibility service is OFF — tap to enable", Icons.Default.Warning)

            Surface(
                onClick  = {
                    if (!serviceEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
                shape    = RoundedCornerShape(12.dp),
                color    = bannerColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        bannerIcon, null,
                        tint     = if (serviceEnabled) Teal else Color(0xFFFFB74D),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(bannerText, color = OffWhite, fontSize = 13.sp)
                }
            }
        }

        // ── Add domain input ──────────────────────────────────────────────────
        item {
            val focusRequester = remember { FocusRequester() }
            val focusManager   = LocalFocusManager.current

            Row(
                verticalAlignment  = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value         = newDomain,
                    onValueChange = { newDomain = it.trim() },
                    placeholder   = { Text("example.com", color = Muted.copy(alpha = .5f)) },
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction    = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newDomain.isNotBlank()) {
                            WebBlockerManager.addSite(newDomain.lowercase())
                            newDomain = ""
                            focusManager.clearFocus()
                            refresh()
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Teal,
                        unfocusedBorderColor = Muted.copy(alpha = .3f),
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
                        if (newDomain.isNotBlank()) {
                            WebBlockerManager.addSite(newDomain.lowercase())
                            newDomain = ""
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

        // ── Quick presets ─────────────────────────────────────────────────────
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

        // ── Blocked list header ───────────────────────────────────────────────
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

        // ── Empty state ───────────────────────────────────────────────────────
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