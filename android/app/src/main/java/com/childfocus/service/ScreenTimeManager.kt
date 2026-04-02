package com.childfocus.service

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks per-app foreground time for the current calendar day and checks it
 * against the limits saved by ScreenTimeScreen.kt.
 *
 * ─── How it fits together ───────────────────────────────────────────────────
 *  ScreenTimeScreen.kt  →  writes limits to "screen_time_prefs"
 *  ScreenTimeManager    →  reads those limits, writes today's usage to
 *                          "screen_time_usage", exposes isOverLimit()
 *  ChildFocusAccessibilityService  →  calls onAppForeground() / onAppBackground()
 *                                     on every TYPE_WINDOW_STATE_CHANGED event
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Thread-safety: all public methods are @Synchronized.
 */
object ScreenTimeManager {

    // ── SharedPreferences keys (must match ScreenTimeScreen.kt exactly) ───
    private const val CONFIG_PREFS    = "screen_time_prefs"
    private const val USAGE_PREFS     = "screen_time_usage"
    private const val KEY_TOTAL_LIMIT = "total_daily_limit_minutes"
    private const val KEY_DATE        = "usage_date"

    private fun limitKey(pkg: String)   = "limit_$pkg"
    private fun enabledKey(pkg: String) = "enabled_$pkg"
    private fun usageKey(pkg: String)   = "usage_ms_$pkg"
    private const val KEY_TOTAL_USAGE   = "usage_ms_total"

    // ── Packages whose screen time is managed (mirrors ScreenTimeScreen) ──
    private val TRACKED_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.facebook.katana",
        "com.roblox.client",
        "com.android.chrome",
        "com.netflix.mediaclient",
        "com.snapchat.android",
    )

    // ── In-memory foreground tracking ─────────────────────────────────────
    @Volatile private var foregroundPkg   = ""
    @Volatile private var foregroundStart = 0L   // epoch ms when current app entered fg

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Call every time a new package comes to the foreground
     * (TYPE_WINDOW_STATE_CHANGED with a new packageName).
     *
     * @return true if this app has now exceeded its configured daily limit.
     */
    @Synchronized
    fun onAppForeground(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()

        // Flush elapsed time for whatever was previously in the foreground.
        flushCurrent(context, now)

        // Only track packages the user has configured.
        return if (packageName in TRACKED_PACKAGES) {
            foregroundPkg   = packageName
            foregroundStart = now
            isOverLimit(context, packageName)
        } else {
            foregroundPkg   = ""
            foregroundStart = 0L
            false
        }
    }

    /**
     * Call when the monitored app goes to the background (home pressed, etc.).
     * The accessibility service can call this on any non-tracked package switch.
     */
    @Synchronized
    fun onAppBackground(context: Context) {
        flushCurrent(context, System.currentTimeMillis())
        foregroundPkg   = ""
        foregroundStart = 0L
    }

    /**
     * Periodic tick — call every ~60 s from the accessibility service.
     * Flushes the current session window and re-checks the limit so that the
     * block fires mid-session, not just when the user switches apps.
     *
     * @return true if the currently-foregrounded app is now over its limit.
     */
    @Synchronized
    fun tick(context: Context): Boolean {
        if (foregroundPkg.isEmpty()) return false
        val now = System.currentTimeMillis()
        // Flush and restart the window so we don't double-count on the next tick.
        flushCurrent(context, now)
        foregroundStart = now
        return isOverLimit(context, foregroundPkg)
    }

    /** How many milliseconds of the current app have been used today. */
    @Synchronized
    fun getUsageMs(context: Context, pkg: String): Long {
        maybeResetDay(context)
        return usagePrefs(context).getLong(usageKey(pkg), 0L)
    }

    /** How many minutes of the current app have been used today (rounded). */
    fun getUsageMinutes(context: Context, pkg: String): Int =
        (getUsageMs(context, pkg) / 60_000L).toInt()

    /** The package currently tracked as being in the foreground (empty if none). */
    fun getCurrentForegroundPkg(): String = foregroundPkg

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Returns true if [pkg] has a limit enabled AND usage >= limit. */
    private fun isOverLimit(context: Context, pkg: String): Boolean {
        val cfg = configPrefs(context)

        if (!cfg.getBoolean(enabledKey(pkg), false)) return false

        val limitMinutes = cfg.getInt(limitKey(pkg), 60)

        // 🔥 Support 10-second option
        val limitMs = when (limitMinutes) {
            -1 -> 10_000L   // 10 seconds
            else -> limitMinutes * 60_000L
        }

        if (limitMs <= 0) return false

        val usedMs = getUsageMs(context, pkg)

        println("[SCREEN TIME] $pkg used: ${usedMs / 1000}s / limit: ${limitMs / 1000}s")

        return usedMs >= limitMs
    }

    /** Flush [foregroundPkg]'s elapsed time up to [nowMs] into storage. */
    private fun flushCurrent(context: Context, nowMs: Long) {
        val pkg   = foregroundPkg
        val start = foregroundStart
        if (pkg.isEmpty() || start <= 0L) return
        val delta = nowMs - start
        if (delta > 0) addUsage(context, pkg, delta)
    }

    private fun addUsage(context: Context, pkg: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        maybeResetDay(context)
        val prefs = usagePrefs(context)
        val prev  = prefs.getLong(usageKey(pkg), 0L)
        val totalPrev = prefs.getLong(KEY_TOTAL_USAGE, 0L)
        prefs.edit()
            .putLong(usageKey(pkg), prev + deltaMs)
            .putLong(KEY_TOTAL_USAGE, totalPrev + deltaMs)
            .apply()
    }

    /** Clears all usage counters when the calendar date changes (midnight reset). */
    private fun maybeResetDay(context: Context) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val prefs = usagePrefs(context)
        if (prefs.getString(KEY_DATE, "") != today) {
            prefs.edit().clear().putString(KEY_DATE, today).apply()
        }
    }

    private fun configPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)

    private fun usagePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
}