package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.childfocus.R
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

// ══════════════════════════════════════════════════════════════════════════════
// DATA STRUCTURES
// ══════════════════════════════════════════════════════════════════════════════

/** Fusion constants fetched dynamically from GET /config and persisted locally. */
data class FusionConfig(
    val alpha_nb:        Float = 0.4f,
    val beta_heuristic:  Float = 0.6f,
    val threshold_block: Float = 0.60f, // v7 calibrated default
    val threshold_allow: Float = 0.22f, // v7 calibrated default
)

/** All signals extracted from the accessibility tree for one video. */
data class VideoMetadata(
    val titleRaw:          String,
    val titleForSearch:    String,
    val normalizedTitle:   String,
    val channelHandle:     String,   // "@Handle" or plain channel name (feed)
    val channelId:         String,   // "UCxxxxxxxx" if cached, else ""
    val isVerifiedChannel: Boolean,
    val durationSeconds:   Int,      // -1 = unknown (Shorts feed), 0 = no signal, >0 = known
    val videoId:           String,   // "" if unavailable
    val videoIdSource:     String,   // "event_time" | "timed_retry" | "none"
    val thumbnailUrl:      String,   // "" if videoId unavailable
    val description:       String,
    val isMFK:             Boolean,
    val isCOPPA:           Boolean,
    val isAgeGated:        Boolean,
    val isGeorestricted:   Boolean,
    val isShorts:          Boolean,  // true for Shorts contexts (feed + active)
)

/** Internal node with on-screen coordinates, used for positional Shorts extraction. */
private data class TextNode(val text: String, val top: Int, val bottom: Int)

/** Cache entry stored per normalizedTitle. */
private data class CacheEntry(
    val oirLabel:    String,
    val score:       Float,
    val tier:        Int,
    val confidence:  String,
    val videoId:     String,
    val timestampMs: Long,
)

/** What kind of YouTube screen the service is observing. */
private enum class YoutubeContext {
    SHORTS_ACTIVE, SHORTS_FEED, LONG_FORM_ACTIVE, LONG_FORM_FEED, UNKNOWN
}

// ══════════════════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════════════════

class ChildFocusAccessibilityService : AccessibilityService() {

    // ── Network ───────────────────────────────────────────────────────────────

    /** Main client for classification & suggestions. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Dedicated short-timeout client for /config only (v7). */
    private val configHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Dedicated client for Tier 3 /classify_fast calls.
     *
     * FIX: The previous code used the main `http` client (120s read timeout)
     * for Tier 3, then checked elapsed time AFTER the call returned.
     * This meant the 8s TIER3_TIMEOUT_MS check was cosmetic — OkHttp would
     * block for up to 120s before the check ever ran (confirmed in logcat:
     * "[TIER_3] timeout after 44214ms").
     *
     * Solution: enforce the deadline at the HTTP layer, not after the fact.
     * A SocketTimeoutException thrown here is caught by the existing
     * catch(e: Exception) block in executeTier3(), which already falls
     * through to Tier 4 — so no other code needs to change.
     */
    private val tier3Http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(TIER3_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    // ── Coroutines ────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Only one Tier 1 and one Tier 3 per video. */
    @Volatile private var tier1Job: Job = Job().also { it.cancel() }
    @Volatile private var tier3Job: Job = Job().also { it.cancel() }

    // ── Config ────────────────────────────────────────────────────────────────

    @Volatile private var fusionConfig         = FusionConfig()
    @Volatile private var configLastFetchedMs  = 0L
    @Volatile private var configFailCount      = 0
    private val CONFIG_TTL_MS                  = 300_000L   // 5 min
    private val CONFIG_FAIL_CAP                = 3

    // ── Cache ────────────────────────────────────────────────────────────────

    private val classificationCache = mutableMapOf<String, CacheEntry>()
    private val CACHE_TTL_MS        = 3_600_000L   // 1 hour

    // Channel ID cache (key = handle or plain name, value = Pair(channelId, expiryMs))
    private val channelIdCache = mutableMapOf<String, Pair<String, Long>>()
    private val CHANNEL_TTL_MS = 86_400_000L   // 24 hours

    // ── Volatile state ───────────────────────────────────────────────────────

    @Volatile private var eventTimeVideoId:            String  = ""
    @Volatile private var newShortsNavDetected:        Boolean = false
    @Volatile private var lastSeekPositionSec:         Int     = -1
    @Volatile private var lastKnownDurationSec:        Int     = 0
    @Volatile private var lastSentDedupKey:            String  = ""  // "{context}:{normalizedTitle}"
    @Volatile private var lastSentTimeMs:              Long    = 0L
    @Volatile private var lastFeedExtractionMs:        Long    = 0L
    @Volatile private var lastGuardText:               String  = ""
    @Volatile private var lastGuardResult:             Boolean = false
    @Volatile private var lastShortsScreenHash:        Int     = 0
    @Volatile private var currentContext:              YoutubeContext = YoutubeContext.UNKNOWN

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Overlays / WindowManager ─────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var blockedOverlayView: View?     = null
    private var stage2BannerView: View?       = null

    // ── Patterns ─────────────────────────────────────────────────────────────

    private val URL_PATTERN = Pattern.compile(
        "(?:shorts/|v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"
    )
    private val SEEKBAR_PATTERN = Regex(
        "(\\d+)\\s+minutes?\\s+(\\d+)\\s+seconds?\\s+of\\s+(\\d+)\\s+minutes?\\s+(\\d+)\\s+seconds?",
        RegexOption.IGNORE_CASE
    )
    private val VIEWS_PATTERN = Pattern.compile(
        "([A-Z][^\\n]{10,150})\\s+[\\d.,]+[KMBkm]?\\s+views",
        Pattern.CASE_INSENSITIVE
    )
    private val AT_CHANNEL_PATTERN = Pattern.compile(
        "([A-Z][^\\n@]{10,150})\\s{1,4}@[\\w]{2,50}(?:\\s|$)"
    )
    private val CHANNEL_HANDLE_RE = Regex("^[A-Z][a-zA-Z0-9]{4,40}$")

    companion object {
        // Change host as needed for emulator vs device
        private const val FLASK_HOST = "192.168.1.13"
        private const val FLASK_PORT = 5000
        private const val BASE_URL   = "http://$FLASK_HOST:$FLASK_PORT"

        private const val TITLE_RESET_MS         = 5 * 60 * 1000L
        private const val TIER3_TIMEOUT_MS        = 8_000L
        // TIER3_READ_TIMEOUT_SEC is the OkHttp-layer timeout used by tier3Http.
        // Must stay in sync with TIER3_TIMEOUT_MS (8 000 ms = 8 s + 1 s margin).
        private const val TIER3_READ_TIMEOUT_SEC  = 9L
        private const val TIER1_TIMEOUT_MS        = 120_000L
        private const val FEED_DEDUP_MS           = 2_000L

        // ── Comment-engagement phrase patterns (FIX: Problem 1) ──────────────
        // YouTube's comment section surfaces engagement prompts like:
        //   "Like this comment along with 14k other people"
        //   "Like this comment along with 391 other people"
        // These leak into allText and pass isCleanTitle() because they look
        // like normal sentences. Block them here at the source.
        private val COMMENT_ENGAGEMENT_PATTERNS = listOf(
            Regex("like this comment along with", RegexOption.IGNORE_CASE),
            Regex("\\d+[km]?\\s+other people",   RegexOption.IGNORE_CASE),
            Regex("comment along with \\d",       RegexOption.IGNORE_CASE),
            Regex("^\\d+[,.]?\\d*[km]?\\s+(likes?|comments?|views?)$",
                RegexOption.IGNORE_CASE),
        )

        private val SKIP_WHOLE_WORD = setOf(
            "Subscribe", "Subscribed", "Join", "Bell", "Share",
            "Reply", "Report", "Queue", "Topic", "Shorts",
            "Explore", "Library", "Home", "Cast", "Minimize",
            "likes", "seconds", "Recommended", "LIVE",
        )
        private val SKIP_WHOLE_WORD_RE: Regex = run {
            val pattern = SKIP_WHOLE_WORD.joinToString("|") { Regex.escape(it) }
            Regex("(?i)\\b($pattern)\\b")
        }
        private val SKIP_SUBSTRING = listOf(
            "Sponsored", "Advertisement", "Ad ·", "Skip Ads",
            "play Short", "play video", "More actions", "Action menu",
            "Explore Menu", "My Mix", "Trending", "Video player",
            "Minimized player", "More options", "Hide controls",
            "Enter fullscreen", "Voice search", "Choose Premium",
            "Drag to reorder", "Add to queue", "Save to playlist",
            "Not interested", "Don't recommend channel",
            "thousand views", "million views", "K views", "M views",
            "months ago", "years ago", "days ago", "hours ago",
            "weeks ago", "See #", "videos ...more", "...more",
            "Add a comment", "@mention", "comment or @", "replies",
            "Pinned comment", "View all comments", "Top comments",
            "Newest first", "Sort comments", "Liked by creator",
            "Show more replies", "Load more comments",
            "Be the first to comment", "No comments yet",
            "See more videos using this sound",
            "Original audio", "Original sound",
            "View product", "Shop now", "Swipe up", "Add yours",
            "Remix this", "(Official", "- Topic",
            "♪", "♫", "🎵", "🎶", "Premium Lite", "Try Premium",
            "YouTube Premium", "Ad-free", "Get Premium",
            "Feature not available",
            "New content available", "Subscriptions:", "new content",
            "Tap to watch live", "SHORTS", "filters",
            // FIX: comment engagement text leaking as video titles (Problem 1)
            // e.g. "Like this comment along with 14k other people"
            "like this comment", "other people", "comment along with",
        )
        private val SKIP_NODE_CLASSES = listOf(
            "ImageButton", "ImageView", "ProgressBar", "SeekBar",
            "CheckBox", "Switch", "RadioButton",
        )
        private val AD_SIGNALS = listOf(
            "Skip Ads", "Skip ad", "Ad ·", "Why this ad",
            "Stop seeing this ad", "Visit advertiser", "Skip in",
            "Sponsored", "Promoted",
        )
        private val COMMENT_SIGNALS = listOf(
            "Add a comment", "Top comments", "Newest first", "Sort comments",
            "Be the first to comment", "Pinned comment",
            "Show more replies", "Load more comments",
            // FIX (Problem 1): YouTube renders per-comment engagement prompts
            // directly in the accessibility tree. When the user scrolls to the
            // comment section these strings appear in allText BEFORE the
            // standard signals above (e.g. "Add a comment") are visible.
            // Adding them here ensures isCommentSectionVisible() returns true
            // early and the event is discarded before extraction begins.
            "like this comment", "other people", "comment along with",
        )
        private val SHORTS_UI_SIGNALS = listOf(
            "like", "dislike", "comment", "share", "remix",
            "add yours", "mute", "pause", "play", "seek bar", "video progress",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        println("[CF_SERVICE] ✓ Connected — ChildFocus v7 monitoring YouTube")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fusionConfig = loadConfigFromPrefs() // immediate, no network
        syncFusionConfig()                   // async refresh
    }

    override fun onInterrupt() {
        tier1Job.cancel()
        tier3Job.cancel()
        resetVolatileState()
        removeBlockedOverlay()
        removeBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
        tier1Job.cancel()
        tier3Job.cancel()
        resetVolatileState()
        removeBlockedOverlay()
        removeBanner()
    }

    private fun resetVolatileState() {
        eventTimeVideoId        = ""
        newShortsNavDetected    = false
        lastSeekPositionSec     = -1
        lastKnownDurationSec    = 0
        lastSentDedupKey        = ""
        lastSentTimeMs          = 0L
        lastFeedExtractionMs    = 0L
        lastGuardText           = ""
        lastGuardResult         = false
        lastShortsScreenHash    = 0
        currentContext          = YoutubeContext.UNKNOWN
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 0 — EVENT GATE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != "com.google.android.youtube") return

        val now = System.currentTimeMillis()
        if (lastSentDedupKey.isNotEmpty() &&
            (now - lastSentTimeMs) > TITLE_RESET_MS
        ) {
            lastSentDedupKey     = ""
            lastSentTimeMs       = 0L
            lastShortsScreenHash = 0
        }

        val eventType = event.eventType

        // Phase 0A — event-time video ID extraction
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            syncFusionConfig()
            phase0A_extractEventTimeVideoId(event)
        }

        // Throttle content-changed events
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if ((now - lastSentTimeMs) < 500L) return
        }

        // Process only the configured event types
        if (eventType !in listOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            )
        ) return

        val root = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)
        root.recycle()

        // Phase 2 — ad / comment guard
        if (allText == lastGuardText && lastGuardResult) return
        val isAdOrComment = isAdPlaying(allText) || isCommentSectionVisible(allText)
        lastGuardText   = allText
        lastGuardResult = isAdOrComment
        if (isAdOrComment) return

        // Phase 1 — context detection
        currentContext = detectContext(allText)
        if (currentContext == YoutubeContext.UNKNOWN) return

        // Scroll events: only feed extraction
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            when (currentContext) {
                YoutubeContext.SHORTS_FEED   -> handleShortsFeed(allText)
                YoutubeContext.LONG_FORM_FEED -> handleLongFormFeed(allText)
                else -> { /* ignore */ }
            }
            return
        }

        // Active contexts
        when (currentContext) {
            YoutubeContext.SHORTS_ACTIVE    -> handleShortsActive(allText)
            YoutubeContext.LONG_FORM_ACTIVE -> handleLongFormActive(allText)
            YoutubeContext.SHORTS_FEED,
            YoutubeContext.LONG_FORM_FEED   -> { /* feed handled in scroll */ }
            YoutubeContext.UNKNOWN          -> Unit
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 0A — EVENT-TIME VIDEO ID EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun phase0A_extractEventTimeVideoId(event: AccessibilityEvent) {
        // V1 — event text
        val eventText = event.text?.joinToString(" ") ?: ""
        URL_PATTERN.matcher(eventText).let { m ->
            if (m.find()) { eventTimeVideoId = m.group(1) ?: ""; return }
        }

        // V2 — event contentDescription
        val eventDesc = event.contentDescription?.toString() ?: ""
        URL_PATTERN.matcher(eventDesc).let { m ->
            if (m.find()) { eventTimeVideoId = m.group(1) ?: ""; return }
        }

        // V3 — window titles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            windows?.forEach { window ->
                val title = window.title?.toString() ?: ""
                val m     = URL_PATTERN.matcher(title)
                if (m.find()) {
                    eventTimeVideoId = m.group(1) ?: ""
                    window.recycle()
                    return
                }
                window.recycle()
            }
        }

        // V4 — class name navigation signal
        val className = event.className?.toString() ?: ""
        val isShortsNav = listOf(
            "WatchWhileActivity", "ShortsActivity", "ReelWatchActivity"
        ).any { className.contains(it) }
        if (isShortsNav) {
            newShortsNavDetected = true
            launchTimedRetry()
        }
    }

    /** V5 — timed retry: tree populates async after navigation. */
    private fun launchTimedRetry() {
        scope.launch(Dispatchers.IO) {
            listOf(600L, 1200L, 2500L).forEach { delayMs ->
                delay(delayMs)
                val root = rootInActiveWindow ?: return@forEach
                val text = collectAllNodeText(root)
                root.recycle()
                val m = URL_PATTERN.matcher(text)
                if (m.find()) {
                    val id = m.group(1) ?: return@forEach
                    println("[CF_SERVICE] ✓ [0A_RETRY_${delayMs}ms] videoId=$id")
                    eventTimeVideoId        = id
                    newShortsNavDetected    = false
                    return@launch
                }
            }
            newShortsNavDetected = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 1 — CONTEXT DETECTION (v7 rules)
    // ══════════════════════════════════════════════════════════════════════════

    private fun detectContext(allText: String): YoutubeContext {
        val lower = allText.lowercase()

        // SHORTS_FEED — "play Short" marker
        if (lower.contains("play short")) {
            // ensure not active Shorts (no reel_time_bar player)
            if (!hasSeekBarInTree()) return YoutubeContext.SHORTS_FEED
        }

        // LONG_FORM_FEED — "play video" marker
        if (lower.contains("play video")) {
            return YoutubeContext.LONG_FORM_FEED
        }

        // SHORTS_ACTIVE — reel_time_bar + real Shorts UI, not feed
        val hasReelSeek = lower.contains("reel_time_bar") || hasSeekBarInTree()
        if (hasReelSeek &&
            !lower.contains("play short") &&
            isRealShortsScreen(allText)
        ) {
            return YoutubeContext.SHORTS_ACTIVE
        }

        // LONG_FORM_ACTIVE — player controls and not feed markers
        if ((lower.contains("player_control_play_pause_replay_button") ||
                    lower.contains("watchwhileactivity")) &&
            !lower.contains("play short") &&
            !lower.contains("play video")
        ) {
            return YoutubeContext.LONG_FORM_ACTIVE
        }

        return YoutubeContext.UNKNOWN
    }

    private fun hasSeekBarInTree(): Boolean {
        val root = rootInActiveWindow ?: return false
        val result = findSeekBarNode(root) != null
        root.recycle()
        return result
    }

    private fun findSeekBarNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val rid = node.viewIdResourceName ?: ""
            if (rid.contains("reel_time_bar") ||
                node.className?.toString()?.contains("SeekBar") == true
            ) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findSeekBarNode(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        } catch (_: Exception) {}
        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 2 — AD / COMMENT DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun isAdPlaying(allText: String) =
        AD_SIGNALS.any { allText.contains(it, ignoreCase = true) }

    private fun isCommentSectionVisible(allText: String) =
        COMMENT_SIGNALS.any { allText.contains(it, ignoreCase = true) }

    // ══════════════════════════════════════════════════════════════════════════
    // SHORTS ACTIVE HANDLER (4C + cache + Tiers)
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleShortsActive(allText: String) {
        val screenHash = allText.hashCode()
        if (screenHash == lastShortsScreenHash) return
        lastShortsScreenHash = screenHash

        val metadata = extractShortsMetadata() ?: return

        // SeekBar-based video-change detection
        if (isVideoChangeDetected(metadata.durationSeconds)) {
            eventTimeVideoId     = ""
            lastSentDedupKey     = ""
            println("[CF_SERVICE] ✓ [SEEKBAR] New Shorts video detected, dur=${metadata.durationSeconds}s")
        }

        val dedupKey = "SHORTS_ACTIVE:${metadata.normalizedTitle}"
        if (dedupKey == lastSentDedupKey) return
        if (!isCleanTitle(metadata.titleRaw)) return

        lastSentDedupKey = dedupKey
        lastSentTimeMs   = System.currentTimeMillis()

        println("[CF_SERVICE] ✓ [SHORTS_ACTIVE] title='${metadata.titleRaw}' " +
                "ch='${metadata.channelHandle}' dur=${metadata.durationSeconds}s " +
                "videoId='${metadata.videoId}' src='${metadata.videoIdSource}'")

        broadcastAnalyzing(metadata.videoId.ifEmpty { metadata.titleRaw.take(40) })

        // Restriction fast-path (Tier 2)
        if (metadata.isAgeGated || (metadata.isMFK && metadata.isGeorestricted)) {
            val score = applyRestrictionModifiers(fusionConfig.threshold_block, metadata)
            deliverResult(
                videoId    = metadata.videoId.ifEmpty { metadata.normalizedTitle },
                label      = "Overstimulating",
                score      = score,
                tier       = 2,
                confidence = "RESTRICTION_ONLY",
                cached     = false,
                metadata   = metadata,
            )
            return
        }

        // Cache lookup (Tier 0)
        val cached = getCachedResult(metadata.normalizedTitle)
        if (cached != null) {
            println("[CF_SERVICE] ✓ [TIER_0][CACHE_HIT] ${metadata.normalizedTitle}")
            broadcastResult(
                videoId    = metadata.videoId.ifEmpty { cached.videoId },
                label      = cached.oirLabel,
                score      = cached.score,
                tier       = 0,
                confidence = cached.confidence,
                cached     = true,
            )
            if (cached.oirLabel == "Overstimulating") {
                fetchAndShowSuggestions(cached.videoId.ifEmpty { metadata.videoId }, cached.score)
            }
            return
        }

        // Launch T3 (primary UX) + T1 (background)
        launchShortsClassification(metadata)
    }

    private fun isVideoChangeDetected(newDurationSec: Int): Boolean {
        if (lastSeekPositionSec > 5 && newDurationSec != lastKnownDurationSec) {
            lastKnownDurationSec = newDurationSec
            lastSeekPositionSec  = 0
            return true
        }
        if (lastKnownDurationSec == 0 && newDurationSec > 0) {
            lastKnownDurationSec = newDurationSec
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHORTS FEED HANDLER (4A — Button content-desc parser, cache-warming only)
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleShortsFeed(allText: String) {
        val now = System.currentTimeMillis()
        if (now - lastFeedExtractionMs < FEED_DEDUP_MS) return
        lastFeedExtractionMs = now

        val root = rootInActiveWindow ?: return
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        root.recycle()

        for (btn in buttons) {
            try {
                val cdesc = btn.contentDescription?.toString() ?: continue
                if (!cdesc.endsWith(" - play Short")) continue
                if (cdesc.startsWith("Tap to watch live")) continue
                if (cdesc == "More actions") continue

                val body  = cdesc.removeSuffix(" - play Short")
                val parts = body.split(", ")
                if (parts.size < 4) continue

                val timeStr  = parts.last()                 // e.g. "2 years ago"
                val channel  = parts[parts.size - 2]
                val viewsStr = parts[parts.size - 3]
                val titleRaw = parts.dropLast(3).joinToString(", ")

                if (!isCleanTitle(titleRaw)) continue

                val normTitle = normalizeTitle(titleRaw)
                val dedupKey  = "SHORTS_FEED:$normTitle"
                if (dedupKey == lastSentDedupKey) continue
                lastSentDedupKey = dedupKey
                lastSentTimeMs   = now

                // Shorts feed: no duration from SeekBar; use -1 for unknown
                val metadata = VideoMetadata(
                    titleRaw          = titleRaw,
                    titleForSearch    = titleRaw,
                    normalizedTitle   = normTitle,
                    channelHandle     = channel,   // plain name, no "@"
                    channelId         = "",
                    isVerifiedChannel = false,
                    durationSeconds   = -1,        // unknown (critical for backend)
                    videoId           = "",
                    videoIdSource     = "none",
                    thumbnailUrl      = "",
                    description       = "",
                    isMFK             = false,
                    isCOPPA           = false,
                    isAgeGated        = false,
                    isGeorestricted   = false,
                    isShorts          = true,
                )

                println("[CF_SERVICE] ✓ [SHORTS_FEED] title='$titleRaw' channel='$channel'")
                // Cache warming only: Tier 1 in background, no UX
                tier1Job = scope.launch { executeTier1(metadata, isShorts = true, fromFeed = true) }

            } finally {
                btn.recycle()
            }
        }
    }

    private fun collectButtons(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        try {
            if (node.className?.toString()?.contains("Button") == true) {
                // Clone reference; caller will recycle
                out.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectButtons(child, out)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LONG-FORM ACTIVE HANDLER (4D)
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleLongFormActive(allText: String) {
        var videoId = eventTimeVideoId
        if (videoId.isEmpty()) {
            val m = URL_PATTERN.matcher(allText)
            if (m.find()) videoId = m.group(1) ?: ""
        }

        val title = extractLongFormTitle(allText) ?: return
        val normTitle = normalizeTitle(title)

        val dedupKey = "LONG_FORM_ACTIVE:$normTitle"
        if (dedupKey == lastSentDedupKey) return
        if (!isCleanTitle(title)) return

        lastSentDedupKey = dedupKey
        lastSentTimeMs   = System.currentTimeMillis()
        eventTimeVideoId = ""

        println("[CF_SERVICE] ✓ [LONG_FORM_ACTIVE] videoId='$videoId' title='$title'")
        broadcastAnalyzing(videoId.ifEmpty { title.take(40) })

        val cached = getCachedResult(normTitle)
        if (cached != null) {
            println("[CF_SERVICE] ✓ [TIER_0][CACHE_HIT] $normTitle")
            broadcastResult(
                videoId    = videoId.ifEmpty { cached.videoId },
                label      = cached.oirLabel,
                score      = cached.score,
                tier       = 0,
                confidence = cached.confidence,
                cached     = true,
            )
            if (cached.oirLabel == "Overstimulating") {
                fetchAndShowSuggestions(videoId.ifEmpty { cached.videoId }, cached.score)
            }
            return
        }

        val thumbUrl = if (videoId.isNotEmpty())
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""

        val metadata = VideoMetadata(
            titleRaw          = title,
            titleForSearch    = title,
            normalizedTitle   = normTitle,
            channelHandle     = extractChannel(allText),
            channelId         = "",
            isVerifiedChannel = false,
            durationSeconds   = 0,
            videoId           = videoId,
            videoIdSource     = if (videoId.isNotEmpty()) "event_time" else "none",
            thumbnailUrl      = thumbUrl,
            description       = "",
            isMFK             = allText.contains("made for kids", ignoreCase = true),
            isCOPPA           = false,
            isAgeGated        = allText.contains("age-restricted", ignoreCase = true),
            isGeorestricted   = allText.contains("not available in your country", ignoreCase = true),
            isShorts          = false,
        )

        tier1Job.cancel()
        tier1Job = scope.launch { executeTier1(metadata, isShorts = false, fromFeed = false) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LONG-FORM FEED HANDLER (4B — Button + ViewGroup patterns, cache-warming)
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleLongFormFeed(allText: String) {
        val now = System.currentTimeMillis()
        if (now - lastFeedExtractionMs < FEED_DEDUP_MS) return
        lastFeedExtractionMs = now

        val root = rootInActiveWindow ?: return
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectButtons(root, buttons)

        // Pattern A — Button content-desc " - play video"
        for (btn in buttons) {
            try {
                val cdesc = btn.contentDescription?.toString() ?: continue
                if (!cdesc.endsWith(" - play video")) continue
                if (cdesc.startsWith("Tap to watch live")) continue
                if (cdesc == "More actions" || cdesc == "Action menu" || cdesc == "Explore Menu") continue
                if (cdesc.endsWith(" - play Short")) continue

                val body   = cdesc.removeSuffix(" - play video")
                val splitDD = body.split(" - - ", limit = 2)
                if (splitDD.size != 2) continue
                val titleAndDuration = splitDD[0]
                val channelAndMeta   = splitDD[1]

                val lastDash = titleAndDuration.lastIndexOf(" - ")
                val titleRaw = if (lastDash > 0)
                    titleAndDuration.substring(0, lastDash)
                else titleAndDuration

                if (!isCleanTitle(titleRaw)) continue

                val channelParts = channelAndMeta.split(" - ")
                val channel = channelParts.firstOrNull() ?: ""

                val durationStr = if (lastDash > 0)
                    titleAndDuration.substring(lastDash + 3) else ""
                val durationSec = parseDurationString(durationStr)

                val normTitle = normalizeTitle(titleRaw)
                val dedupKey  = "LONG_FORM_FEED:$normTitle"
                if (dedupKey == lastSentDedupKey) continue
                lastSentDedupKey = dedupKey
                lastSentTimeMs   = now

                val metadata = VideoMetadata(
                    titleRaw          = titleRaw,
                    titleForSearch    = titleRaw,
                    normalizedTitle   = normTitle,
                    channelHandle     = channel,
                    channelId         = "",
                    isVerifiedChannel = false,
                    durationSeconds   = durationSec,
                    videoId           = "",
                    videoIdSource     = "none",
                    thumbnailUrl      = "",
                    description       = "",
                    isMFK             = false,
                    isCOPPA           = false,
                    isAgeGated        = false,
                    isGeorestricted   = false,
                    isShorts          = false,
                )

                println("[CF_SERVICE] ✓ [LONG_FORM_FEED] title='$titleRaw' channel='$channel'")
                tier1Job = scope.launch { executeTier1(metadata, isShorts = false, fromFeed = true) }

            } finally {
                btn.recycle()
            }
        }

        root.recycle()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4C — SHORTS METADATA EXTRACTION (active Shorts)
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractShortsMetadata(): VideoMetadata? {
        val root  = rootInActiveWindow ?: return null
        val nodes = collectTextNodes(root)
        root.recycle()
        if (nodes.isEmpty()) return null

        val screenH = resources.displayMetrics.heightPixels
        val settledMin  = (screenH * 0.72).toInt()
        val settledMax  = (screenH * 0.97).toInt()
        val channelRowT = (screenH * 0.87).toInt()
        val neighbourT  = (screenH * 0.18).toInt()

        val uiExact = setOf(
            "shorts", "home", "explore", "subscriptions", "library", "you",
            "like", "dislike", "comment", "share", "subscribe", "subscribed",
            "follow", "more", "pause", "play", "mute", "unmute", "youtube",
            "search", "remix", "add yours", "save", "reels", "create",
            "video progress", "progress", "seek bar", "playback",
        )
        val uiContains = listOf(
            "skip ad", "why this ad", "visit advertiser",
            "new content available", "subscriptions:",
            "see more videos", "feature not available",
            "like this video", "dislike this video", "share this video",
            "comments disabled", "subscribe to @",
            "please wait", "action menu", "go to channel",
        )
        val filtered = nodes.filter { n ->
            val lower = n.text.lowercase()
            !uiExact.any { lower == it } &&
                    !uiContains.any { lower.contains(it) } &&
                    n.text.length >= 2
        }

        val channelNode = filtered
            .filter { it.text.startsWith("@") && it.bottom > 0 }
            .minByOrNull { abs(it.bottom - channelRowT) }
            ?: return null

        val channelHandle  = channelNode.text.substringBefore(",").trim()
        val isVerified     = channelNode.text.contains("official", ignoreCase = true) ||
                channelNode.text.contains("verified", ignoreCase = true)

        val seekNode  = nodes.firstOrNull {
            it.text.matches(
                Regex(
                    "\\d+\\s+minutes?\\s+\\d+\\s+seconds?\\s+of\\s+\\d+\\s+minutes?\\s+\\d+\\s+seconds?",
                    RegexOption.IGNORE_CASE
                )
            )
        }
        val durationSec = seekNode?.let { parseSeekDuration(it.text) } ?: 0
        if (seekNode != null) {
            val currentSec = parseSeekCurrent(seekNode.text)
            if (lastSeekPositionSec >= 0) lastSeekPositionSec = currentSec
        }

        val maxBottom = minOf(channelNode.bottom + neighbourT, settledMax)
        val seekRe    = Regex(
            "^\\d+\\s+minutes?\\s+\\d+\\s+seconds?\\s+of.*",
            RegexOption.IGNORE_CASE
        )
        val candidates = filtered
            .filter { it.text != channelNode.text && !it.text.startsWith("@") }
            .filterNot { it.text.trimStart().startsWith("#") }
            .filterNot { it.text.lowercase().startsWith("search ") }
            .filterNot { isMusicLabel(it.text) }
            .filterNot { it.text.contains(" · ") }
            .filterNot { seekRe.matches(it.text.trim()) }
            .filter { it.bottom in settledMin..maxBottom }
            .filter { it.top < it.bottom }
            .filter { it.text.length >= 3 }
            .sortedByDescending { it.bottom }

        val rawTitle = candidates.firstOrNull()?.text
            ?.replace(Regex("[​‌‍﻿]"), "")
            ?.trim() ?: return null
        if (rawTitle.length < 3) return null

        val allText = nodes.joinToString(" ") { it.text }
        val isMFK         = allText.contains("made for kids", ignoreCase = true)
        val isAgeGated    = allText.contains("age-restricted", ignoreCase = true)
        val isGeoRestrict = allText.contains("not available in your country", ignoreCase = true)
        val isCOPPA       = isMFK && allText.contains("comments are turned off", ignoreCase = true)

        val cachedChannelId = channelIdCache[channelHandle]
            ?.takeIf { it.second > System.currentTimeMillis() }?.first ?: ""

        val videoId    = eventTimeVideoId
        val videoIdSrc = if (videoId.isNotEmpty()) "event_time" else "none"
        val thumbUrl   = if (videoId.isNotEmpty())
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""

        return VideoMetadata(
            titleRaw          = rawTitle,
            titleForSearch    = rawTitle,
            normalizedTitle   = normalizeTitle(rawTitle),
            channelHandle     = channelHandle,
            channelId         = cachedChannelId,
            isVerifiedChannel = isVerified,
            durationSeconds   = durationSec,
            videoId           = videoId,
            videoIdSource     = videoIdSrc,
            thumbnailUrl      = thumbUrl,
            description       = "",
            isMFK             = isMFK,
            isCOPPA           = isCOPPA,
            isAgeGated        = isAgeGated,
            isGeorestricted   = isGeoRestrict,
            isShorts          = true,
        )
    }

    private fun parseSeekDuration(text: String): Int {
        val m = SEEKBAR_PATTERN.find(text) ?: return 0
        val totalMin = m.groupValues[3].toIntOrNull() ?: 0
        val totalSec = m.groupValues[4].toIntOrNull() ?: 0
        return totalMin * 60 + totalSec
    }

    private fun parseSeekCurrent(text: String): Int {
        val m = SEEKBAR_PATTERN.find(text) ?: return 0
        val curMin = m.groupValues[1].toIntOrNull() ?: 0
        val curSec = m.groupValues[2].toIntOrNull() ?: 0
        return curMin * 60 + curSec
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4D — LONG-FORM TITLE + CHANNEL EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractLongFormTitle(allText: String): String? {
        val viewsMatch = VIEWS_PATTERN.matcher(allText)
        if (viewsMatch.find()) {
            val t = viewsMatch.group(1)?.trim() ?: ""
            if (isCleanTitle(t)) return t
        }
        val atMatch = AT_CHANNEL_PATTERN.matcher(allText)
        if (atMatch.find()) {
            val t = atMatch.group(1)?.trim() ?: ""
            if (isCleanTitle(t)) return t
        }
        return null
    }

    private fun extractChannel(allText: String): String {
        val m = AT_CHANNEL_PATTERN.matcher(allText)
        if (m.find()) {
            val idx = allText.indexOf("@", m.end())
            if (idx >= 0) {
                val end = allText.indexOfFirst(idx) { it == ' ' || it == '\n' }
                return if (end > idx) allText.substring(idx, end) else "@unknown"
            }
        }
        return ""
    }

    private fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return -1
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 5 — isCleanTitle
    // ══════════════════════════════════════════════════════════════════════════

    private fun isCleanTitle(text: String): Boolean {
        if (text.length < 8 || text.length > 300) return false

        // FIX (Problem 1): Reject comment engagement text that passes the
        // length check and other filters. These regex patterns cover
        // number-varies forms like "Like this comment along with 5.2K other
        // people" that substring matching cannot fully pre-enumerate.
        if (COMMENT_ENGAGEMENT_PATTERNS.any { it.containsMatchIn(text) }) return false

        for (term in SKIP_WHOLE_WORD) {
            if (SKIP_WHOLE_WORD_RE.containsMatchIn(text) &&
                Regex("(?i)\\b${Regex.escape(term)}\\b").containsMatchIn(text)
            ) return false
        }
        for (term in SKIP_SUBSTRING) {
            if (text.contains(term, ignoreCase = true)) return false
        }

        val lower = text.lowercase()
        if (lower.contains("affiliate")     || lower.contains("shopee") ||
            lower.contains("lazada")        || lower.contains("best seller") ||
            lower.contains("buy now")       || lower.contains("order now") ||
            lower.contains("link in bio")   ||
            lower.contains("say goodbye to")|| lower.contains("years younger") ||
            lower.contains("skin look")     || lower.contains("suitable for all") ||
            lower.contains("all skin types")||
            lower.startsWith("search ")     ||
            lower.startsWith("tap to watch live") ||
            (lower.startsWith("view ") && lower.contains("comment")) ||
            lower.startsWith("helps ")      ||
            (lower.startsWith("get ") && text.length < 60) ||
            (lower.startsWith("try ") && text.length < 50)
        ) return false

        if (Regex("[A-Za-z]{1,4}-?\\d{4,}[A-Za-z0-9]*").containsMatchIn(text)) return false
        if (Regex("^[A-Z][a-zA-Z]+ [·•] [A-Z][a-zA-Z]+$").containsMatchIn(text)) return false
        if (CHANNEL_HANDLE_RE.matches(text.trim())) return false
        if (text.trim().split(Regex("\\s+")).size < 2) return false

        return true
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 6 — CLEANING
    // ══════════════════════════════════════════════════════════════════════════

    private fun normalizeTitle(title: String): String =
        title.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun cleanForClassification(text: String): String =
        text
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[\\uD800-\\uDFFF]"), " ")
            .replace(Regex("[\\u2600-\\u27FF]"), " ")
            .replace(Regex("[\\u2300-\\u23FF]"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s.,!?'#@]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 7 — CLASSIFICATION PIPELINE
    // ══════════════════════════════════════════════════════════════════════════

    private fun launchShortsClassification(metadata: VideoMetadata) {
        tier1Job.cancel()
        tier3Job.cancel()

        tier3Job = scope.launch { executeTier3(metadata) }
        tier1Job = scope.launch { executeTier1(metadata, isShorts = true, fromFeed = false) }
    }

    // ── TIER 1 — Full classification (classify_full / classify_by_title) ─────

    private suspend fun executeTier1(
        metadata: VideoMetadata,
        isShorts: Boolean,
        fromFeed: Boolean,
    ) {
        syncFusionConfig()
        try {
            val responseJson: JSONObject =
                if (metadata.videoId.isNotEmpty()) {
                    // Case A — video ID available
                    val body = JSONObject().apply {
                        put("video_url",     "https://www.youtube.com/watch?v=${metadata.videoId}")
                        put("thumbnail_url", metadata.thumbnailUrl)
                        put("hint_title",    metadata.titleForSearch)
                    }
                    postJson("/classify_full", body)
                } else {
                    // Case B — no video ID (Shorts typical, feed cards)
                    val body = JSONObject().apply {
                        put("title",            metadata.titleForSearch)
                        put("channel",          metadata.channelHandle)
                        put("channel_id",       metadata.channelId)
                        put("duration_seconds", metadata.durationSeconds)
                        put(
                            "is_verified",
                            metadata.isVerifiedChannel
                        )
                        put(
                            "is_shorts",
                            metadata.isShorts || (metadata.durationSeconds in 1..60)
                        )
                    }
                    postJson("/classify_by_title", body)
                }

            val rawScore = responseJson.optDouble("score_final", 0.5).toFloat()
            var score    = applyRestrictionModifiers(rawScore, metadata)
            val label    = scoreTolabel(score)

            println("[CF_SERVICE] ✓ [TIER_1] ${metadata.titleRaw.take(40)} → $label ($score)")

            val prior = getCachedResult(metadata.normalizedTitle)
            if (prior != null && prior.oirLabel != label) {
                println("[CF_SERVICE] ⚠ [TIER_LABEL_MISMATCH] Tier3=${prior.oirLabel} Tier1=$label")
            }

            val videoIdFromResp = responseJson.optString("video_id", metadata.videoId)
            val finalVideoId = videoIdFromResp.ifEmpty {
                metadata.videoId.ifEmpty { metadata.normalizedTitle }
            }

            // For feed: cache only, no UX
            if (fromFeed) {
                classificationCache[metadata.normalizedTitle] = CacheEntry(
                    oirLabel    = label,
                    score       = score,
                    tier        = 1,
                    confidence  = if (metadata.videoId.isNotEmpty()) "FULL" else "PARTIAL",
                    videoId     = finalVideoId,
                    timestampMs = System.currentTimeMillis(),
                )
                return
            }

            deliverResult(
                videoId    = finalVideoId,
                label      = label,
                score      = score,
                tier       = 1,
                confidence = if (metadata.videoId.isNotEmpty()) "FULL" else "PARTIAL",
                cached     = false,
                metadata   = metadata,
            )
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ [TIER_1_FAIL] ${e.message}")
            if (!isShorts && getCachedResult(metadata.normalizedTitle) == null) {
                executeTier4(metadata) // Long-form fallback
            }
        }
    }

    // ── TIER 3 — NB + on-device thumbnail fusion (primary Shorts UX) ─────────

    private suspend fun executeTier3(metadata: VideoMetadata) {
        syncFusionConfig()
        try {
            val body = JSONObject().apply {
                put("title",       metadata.titleForSearch)
                put("tags",        JSONArray())
                put("description", cleanForClassification(metadata.description))
            }

            // FIX (Problem 2): Use tier3Http (9s read timeout) instead of the
            // main `http` client (120s).  The old code called postJson() — which
            // uses `http` — then checked elapsed time AFTER the call returned.
            // Because OkHttp would block for up to 120s before returning, the
            // TIER3_TIMEOUT_MS guard was never reached in time (confirmed in
            // logcat: "[TIER_3] timeout after 44214ms").
            //
            // Now the deadline is enforced at the socket layer.  A
            // SocketTimeoutException bubbles up to the catch block below and
            // falls through to Tier 4 — no other changes needed.
            val startMs  = System.currentTimeMillis()
            val response = postJsonWithClient(tier3Http, "/classify_fast", body)
            val elapsed  = System.currentTimeMillis() - startMs
            println("[CF_SERVICE] ✓ [TIER_3] classify_fast returned in ${elapsed}ms")

            val nbScore    = response.optDouble("score_nb", 0.5).toFloat()
            val thumbScore = computeThumbnailIntensityLocal(metadata.thumbnailUrl)

            val fusedScore = (fusionConfig.alpha_nb * nbScore +
                    fusionConfig.beta_heuristic * thumbScore)
                .coerceIn(0f, 1f)

            val score = applyRestrictionModifiers(fusedScore, metadata)
            val label = scoreTolabel(score)

            println("[CF_SERVICE] ✓ [TIER_3] nb=$nbScore thumb=$thumbScore fused=$score → $label")

            deliverResult(
                videoId    = metadata.videoId.ifEmpty { metadata.normalizedTitle },
                label      = label,
                score      = score,
                tier       = 3,
                confidence = "PARTIAL",
                cached     = false,
                metadata   = metadata,
            )
        } catch (e: java.net.SocketTimeoutException) {
            // Explicit timeout branch — logs a clear message distinct from
            // other network errors so it is easy to spot during thesis testing.
            val elapsed = System.currentTimeMillis()
            println("[CF_SERVICE] ⚠ [TIER_3] socket timeout after " +
                    "${TIER3_READ_TIMEOUT_SEC}s — falling through to Tier 5 banner")
            executeTier5_timeout(metadata)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ [TIER_3_FAIL] ${e.message}")
            executeTier4(metadata)
        }
    }

    // ── TIER 4 — Local scoring fallback ───────────────────────────────────────

    private fun executeTier4(metadata: VideoMetadata) {
        val classifierInput = cleanForClassification(
            "${metadata.titleRaw} ${metadata.channelHandle} ${metadata.description.take(300)}"
        )
        val textScore = computeLocalOirScore(classifierInput)
        val satScore  = 0.5f
        val fusedScore = (fusionConfig.alpha_nb * textScore +
                fusionConfig.beta_heuristic * satScore)
            .coerceIn(0f, 1f)
        val score = applyRestrictionModifiers(fusedScore, metadata)
        val label = scoreTolabel(score)

        println("[CF_SERVICE] ✓ [TIER_4] text=$textScore fused=$score → $label")

        deliverResult(
            videoId    = metadata.videoId.ifEmpty { metadata.normalizedTitle },
            label      = label,
            score      = score,
            tier       = 4,
            confidence = "LOW",
            cached     = false,
            metadata   = metadata,
        )
    }

    // ── TIER 5 — Timeout / unresolved (Shorts only: passive Stage 2 banner) ──

    private fun executeTier5_timeout(metadata: VideoMetadata) {
        println("[CF_SERVICE] ⚠ [TIER_5][SHORTS_TIMEOUT] ${metadata.titleRaw.take(40)}")
        showStage2Banner()
    }

    private fun computeLocalOirScore(text: String): Float {
        val overstimulating = setOf(
            "surprise", "unboxing", "compilation", "fast", "crazy", "insane",
            "extreme", "challenge", "slime", "satisfying", "asmr", "mukbang",
            "prank", "gross", "wow", "omg", "epic", "shocking", "viral", "trending",
        )
        val educational = setOf(
            "learn", "educational", "lesson", "tutorial", "phonics", "alphabet",
            "numbers", "science", "history", "geography", "math", "reading",
            "spelling", "story", "kindergarten", "preschool",
        )
        val tokens   = text.lowercase().split(Regex("\\s+"))
        val overCnt  = tokens.count { it in overstimulating }
        val eduCnt   = tokens.count { it in educational }
        return ((overCnt - eduCnt).toFloat() / 5f)
            .coerceIn(-1f, 1f)
            .let { (it + 1f) / 2f }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RESTRICTION MODIFIERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyRestrictionModifiers(score: Float, meta: VideoMetadata): Float {
        var s = score
        if (meta.isGeorestricted) return s   // cannot analyze further
        if (meta.isMFK && meta.isAgeGated) return fusionConfig.threshold_block
        if (meta.isAgeGated) s = s.coerceAtLeast(fusionConfig.threshold_block - 0.05f)
        if (meta.isMFK)      s = s.coerceAtLeast(fusionConfig.threshold_allow + 0.05f)
        return s.coerceIn(0f, 1f)
    }

    private fun scoreTolabel(score: Float): String = when {
        score >= fusionConfig.threshold_block -> "Overstimulating"
        score <= fusionConfig.threshold_allow -> "Educational"
        else                                  -> "Neutral"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 8 — UX RESPONSE + CACHE + BROADCAST
    // ══════════════════════════════════════════════════════════════════════════

    private fun deliverResult(
        videoId:    String,
        label:      String,
        score:      Float,
        tier:       Int,
        confidence: String,
        cached:     Boolean,
        metadata:   VideoMetadata,
    ) {
        classificationCache[metadata.normalizedTitle] = CacheEntry(
            oirLabel    = label,
            score       = score,
            tier        = tier,
            confidence  = confidence,
            videoId     = videoId,
            timestampMs = System.currentTimeMillis(),
        )

        broadcastResult(
            videoId    = videoId,
            label      = label,
            score      = score,
            tier       = tier,
            confidence = confidence,
            cached     = cached,
        )

        if (label == "Overstimulating") {
            fetchAndShowSuggestions(videoId, score)
        }

        println("[CF_SERVICE] ✓ [T$tier/$confidence] $label ($score) → $videoId cached=$cached")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UX ACTIONS + OVERLAYS
    // ══════════════════════════════════════════════════════════════════════════

    /** Show Stage 1 full-screen block overlay using overlay_blocked.xml. */
    private fun showBlockedOverlay(score: Float, suggestionsJson: String?) {
        mainHandler.post {
            try {
                removeBlockedOverlay()

                val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val view     = inflater.inflate(R.layout.overlay_blocked, null)

                val tvBlocked = view.findViewById<TextView>(R.id.tvBlocked)
                val tvScore   = view.findViewById<TextView>(R.id.tvScore)
                val pb        = view.findViewById<ProgressBar>(R.id.pbSuggestions)
                val llSug     = view.findViewById<LinearLayout>(R.id.llSuggestions)
                val btnClose  = view.findViewById<Button>(R.id.btnClose)

                tvBlocked.text = getString(R.string.blocked_title)
                tvScore.text   = "Overstimulating score: ${(score * 100).toInt()}%"

                btnClose.setOnClickListener {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    removeBlockedOverlay()
                }

                if (!suggestionsJson.isNullOrEmpty()) {
                    pb.visibility = View.GONE
                    populateSuggestionButtons(llSug, suggestionsJson)
                } else {
                    pb.visibility = View.VISIBLE
                }

                val params = WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    format = PixelFormat.TRANSLUCENT
                    flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_FULLSCREEN
                    width  = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    gravity = Gravity.CENTER
                }

                blockedOverlayView = view
                windowManager?.addView(view, params)
            } catch (e: Exception) {
                println("[CF_SERVICE] ✗ [OVERLAY_BLOCKED] ${e.message}")
            }
        }
    }

    private fun removeBlockedOverlay() {
        mainHandler.post {
            try {
                blockedOverlayView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (_: Exception) {
            } finally {
                blockedOverlayView = null
            }
        }
    }

    /** Passive Stage 2 banner (no block, v7 Tier 5). */
    private fun showStage2Banner() {
        mainHandler.post {
            try {
                removeBanner()

                val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val view     = inflater.inflate(R.layout.overlay_banner_stage2, null)

                val params = WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    format = PixelFormat.TRANSLUCENT
                    flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    width  = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.TOP
                }

                stage2BannerView = view
                windowManager?.addView(view, params)
                mainHandler.postDelayed({ removeBanner() }, 4000L)
            } catch (e: Exception) {
                println("[CF_SERVICE] ✗ [BANNER_STAGE2] ${e.message}")
            }
        }
    }

    private fun removeBanner() {
        mainHandler.post {
            try {
                stage2BannerView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (_: Exception) {
            } finally {
                stage2BannerView = null
            }
        }
    }

    private fun fetchAndShowSuggestions(excludeVideoId: String, score: Float) {
        scope.launch(Dispatchers.IO) {
            var suggestionsJson: String? = null
            try {
                val url = "$BASE_URL/safe_suggestions?limit=3&exclude=$excludeVideoId"
                val response = http.newCall(Request.Builder().url(url).build()).execute()
                suggestionsJson = response.body?.string()
            } catch (e: Exception) {
                println("[CF_SERVICE] ✗ [SUGGESTIONS] ${e.message}")
            }

            showBlockedOverlay(score, suggestionsJson)

            if (!suggestionsJson.isNullOrEmpty()) {
                val intent = Intent("com.childfocus.SAFE_SUGGESTIONS").apply {
                    putExtra("suggestions_json", suggestionsJson)
                }
                LocalBroadcastManager.getInstance(this@ChildFocusAccessibilityService)
                    .sendBroadcast(intent)
            }
        }
    }

    private fun populateSuggestionButtons(container: LinearLayout, suggestionsJson: String) {
        try {
            container.removeAllViews()
            val arr = JSONArray(suggestionsJson)
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val title = obj.optString("title", "Watch this instead")
                val url   = obj.optString("url", "")

                val btn = Button(this).apply {
                    text = title
                    setAllCaps(false)
                    setOnClickListener {
                        try {
                            if (url.isNotEmpty()) {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            }
                            removeBlockedOverlay()
                        } catch (e: Exception) {
                            println("[CF_SERVICE] ✗ [OPEN_SUGGESTION] ${e.message}")
                        }
                    }
                }
                container.addView(btn)
            }
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ [POPULATE_SUG] ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FUSION CONFIG SYNC (v7 — dedicated client + SharedPreferences)
    // ══════════════════════════════════════════════════════════════════════════

    private fun saveConfigToPrefs(config: FusionConfig) {
        val prefs = getSharedPreferences("cf_config", MODE_PRIVATE)
        prefs.edit()
            .putFloat("alpha_nb",        config.alpha_nb)
            .putFloat("beta_heuristic",  config.beta_heuristic)
            .putFloat("threshold_block", config.threshold_block)
            .putFloat("threshold_allow", config.threshold_allow)
            .apply()
    }

    private fun loadConfigFromPrefs(): FusionConfig {
        val prefs = getSharedPreferences("cf_config", MODE_PRIVATE)
        return FusionConfig(
            alpha_nb        = prefs.getFloat("alpha_nb",        0.4f),
            beta_heuristic  = prefs.getFloat("beta_heuristic",  0.6f),
            threshold_block = prefs.getFloat("threshold_block", 0.60f),
            threshold_allow = prefs.getFloat("threshold_allow", 0.22f),
        )
    }

    private fun syncFusionConfig() {
        val now = System.currentTimeMillis()
        if (now - configLastFetchedMs < CONFIG_TTL_MS) return
        if (configFailCount >= CONFIG_FAIL_CAP) {
            if (now - configLastFetchedMs < CONFIG_TTL_MS * 5) return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val response = configHttp.newCall(
                    Request.Builder().url("$BASE_URL/config").build()
                ).execute()
                val json   = JSONObject(response.body?.string() ?: return@launch)
                val fusion = json.getJSONObject("fusion")
                fusionConfig = FusionConfig(
                    alpha_nb        = fusion.getDouble("alpha_nb").toFloat(),
                    beta_heuristic  = fusion.getDouble("beta_heuristic").toFloat(),
                    threshold_block = fusion.getDouble("threshold_block").toFloat(),
                    threshold_allow = fusion.getDouble("threshold_allow").toFloat(),
                )
                configLastFetchedMs = now
                configFailCount     = 0
                saveConfigToPrefs(fusionConfig)
                println("[CF_SERVICE] ✓ [CONFIG] $fusionConfig")
            } catch (e: Exception) {
                configFailCount++
                println(
                    "[CF_SERVICE] ⚠ [CONFIG_FAIL] ${e.message} " +
                            "(fail $configFailCount/$CONFIG_FAIL_CAP) — using persisted values"
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CACHE + NETWORK + BROADCAST HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun getCachedResult(normalizedTitle: String): CacheEntry? {
        val entry = classificationCache[normalizedTitle] ?: return null
        val age   = System.currentTimeMillis() - entry.timestampMs
        return if (age < CACHE_TTL_MS) entry else null.also {
            classificationCache.remove(normalizedTitle)
        }
    }

    /**
     * Default POST helper — uses the main [http] client (120 s read timeout).
     * Appropriate for Tier 1 calls (/classify_full, /classify_by_title) which
     * legitimately take up to 60 s on first analysis.
     */
    private fun postJson(path: String, body: JSONObject): JSONObject =
        postJsonWithClient(http, path, body)

    /**
     * POST helper with a caller-supplied [OkHttpClient].
     *
     * FIX (Problem 2): Tier 3 previously called postJson() — which always used
     * the 120 s main client — and then checked elapsed time AFTER the blocking
     * call returned.  Because OkHttp blocks until the socket timeout fires, the
     * in-code TIER3_TIMEOUT_MS guard was effectively dead (logcat confirmed
     * "[TIER_3] timeout after 44214ms").
     *
     * executeTier3() now passes [tier3Http] (9 s read timeout) here so the
     * deadline is enforced at the socket layer.  A SocketTimeoutException
     * propagates to executeTier3()'s catch block, which routes to Tier 5.
     * No other call sites are affected.
     */
    private fun postJsonWithClient(
        client: OkHttpClient,
        path:   String,
        body:   JSONObject,
    ): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        return JSONObject(response.body?.string() ?: "{}")
    }

    private fun broadcastAnalyzing(videoId: String) {
        broadcastResult(videoId, "Analyzing", 0f, 0, "NONE", false)
    }

    private fun broadcastResult(
        videoId:    String,
        label:      String,
        score:      Float,
        tier:       Int,
        confidence: String,
        cached:     Boolean,
    ) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",    videoId)
            putExtra("oir_label",   label)
            putExtra("score_final", score)
            putExtra("cached",      cached)
            putExtra("tier",        tier)
            putExtra("confidence",  confidence)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THUMBNAIL INTENSITY + NODE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun computeThumbnailIntensityLocal(url: String): Float {
        if (url.isEmpty()) return 0f
        return try {
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            val bytes    = response.body?.bytes() ?: return 0f
            val bm       = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return 0f
            val hsv    = FloatArray(3)
            var total  = 0f
            var count  = 0
            val step   = 4
            for (x in 0 until bm.width step step) {
                for (y in 0 until bm.height step step) {
                    android.graphics.Color.colorToHSV(bm.getPixel(x, y), hsv)
                    total += hsv[1]
                    count++
                }
            }
            bm.recycle()
            if (count > 0) (total / count).coerceIn(0f, 1f) else 0f
        } catch (e: Exception) {
            println("[CF_SERVICE] ⚠ [THUMB] ${e.message}")
            0f
        }
    }

    private fun collectAllNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            val className    = node.className?.toString() ?: ""
            val isSkipped    = SKIP_NODE_CLASSES.any {
                className.endsWith(it.substringAfterLast('.'))
            }
            val textLen      = node.text?.length ?: 0
            val isButtonLike = node.isClickable && textLen in 1..25 && node.childCount == 0
            if (!isSkipped && !isButtonLike) {
                node.text?.let { sb.append(it).append('\n') }
                node.contentDescription?.let { sb.append(it).append('\n') }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb.append(collectAllNodeText(child))
                child.recycle()
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo): List<TextNode> {
        val result = mutableListOf<TextNode>()
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val texts = mutableListOf<String>()
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { texts += it }
            for (t in texts) {
                if (!t.matches(Regex("[\\d.,:\\s]+[KMBkm]?"))) {
                    result += TextNode(t, rect.top, rect.bottom)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                result += collectTextNodes(child)
                child.recycle()
            }
        } catch (_: Exception) {}
        return result
    }

    private fun isRealShortsScreen(allText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = collectTextNodes(root)
        root.recycle()
        val combined = nodes.joinToString(" ") { it.text }.lowercase()
        val matchCount = SHORTS_UI_SIGNALS.count { combined.contains(it) }
        return matchCount >= 2
    }

    private fun isMusicLabel(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("♪") || lower.contains("♫") ||
                lower.contains("🎵") || lower.contains("🎶") ||
                lower.contains("original audio") || lower.contains("original sound")
    }

    // Duration parser for long-form feed pattern
    private fun parseDurationString(text: String): Int {
        val longRe = Regex(
            "(\\d+)\\s+minutes?[,\\s]+(\\d+)\\s+seconds?",
            RegexOption.IGNORE_CASE
        )
        longRe.find(text)?.let {
            val min = it.groupValues[1].toIntOrNull() ?: 0
            val sec = it.groupValues[2].toIntOrNull() ?: 0
            return min * 60 + sec
        }
        val shortRe = Regex("(\\d+):(\\d{2})")
        shortRe.find(text)?.let {
            val min = it.groupValues[1].toIntOrNull() ?: 0
            val sec = it.groupValues[2].toIntOrNull() ?: 0
            return min * 60 + sec
        }
        val minOnly = Regex("(\\d+)\\s+minutes?", RegexOption.IGNORE_CASE)
        minOnly.find(text)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        return 0
    }
}
