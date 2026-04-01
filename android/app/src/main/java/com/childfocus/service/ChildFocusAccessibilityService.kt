package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.random.Random

// ══════════════════════════════════════════════════════════════════════════════
// DATA STRUCTURES
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Fusion constants fetched dynamically from GET /config.
 * Never hardcode these — they change between calibration profiles.
 */
data class FusionConfig(
    val alpha_nb:        Float = 0.4f,
    val beta_heuristic:  Float = 0.6f,
    val threshold_block: Float = 0.75f,
    val threshold_allow: Float = 0.35f,
)

/** All signals extracted from the accessibility tree for one video. */
data class VideoMetadata(
    val titleRaw:          String,
    val titleForSearch:    String,   // titleRaw kept with hashtags
    val normalizedTitle:   String,
    val channelHandle:     String,   // "@Handle"
    val channelId:         String,   // "UCxxxxxxxx" if cached, else ""
    val isVerifiedChannel: Boolean,
    val durationSeconds:   Int,      // from SeekBar or MediaSession; 0 if unknown
    val videoId:           String,   // "" if unavailable (Shorts static tree)
    val videoIdSource:     String,   // "event_time" | "timed_retry" | "none"
    val thumbnailUrl:      String,
    val description:       String,
    val isMFK:             Boolean,
    val isCOPPA:           Boolean,
    val isAgeGated:        Boolean,
    val isGeorestricted:   Boolean,
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Coroutines ────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO)

    /** Job guard so only one Tier 1 and one Tier 3 run per video. */
    @Volatile private var tier1Job: Job = Job().also { it.cancel() }
    @Volatile private var tier3Job: Job = Job().also { it.cancel() }

    // ── Config ────────────────────────────────────────────────────────────────

    @Volatile private var fusionConfig         = FusionConfig()
    @Volatile private var configLastFetchedMs  = 0L
    private val CONFIG_TTL_MS                  = 300_000L

    // ── In-memory classification cache (key = normalizedTitle) ───────────────

    private val classificationCache = mutableMapOf<String, CacheEntry>()
    private val CACHE_TTL_MS        = 3_600_000L   // 1 hour

    // ── Channel ID cache (key = "@Handle", value = Pair(channelId, expiryMs)) ─

    private val channelIdCache = mutableMapOf<String, Pair<String, Long>>()
    private val CHANNEL_TTL_MS = 86_400_000L   // 24 hours

    // ── Volatile service state ────────────────────────────────────────────────

    @Volatile private var eventTimeVideoId:            String  = ""
    @Volatile private var newShortsNavDetected:        Boolean = false
    @Volatile private var lastSeekPositionSec:         Int     = -1
    @Volatile private var lastKnownDurationSec:        Int     = 0
    @Volatile private var lastSentNormalizedTitle:     String  = ""
    @Volatile private var lastSentTimeMs:              Long    = 0L
    @Volatile private var lastGuardText:               String  = ""
    @Volatile private var lastGuardResult:             Boolean = false
    @Volatile private var lastShortsScreenHash:        Int     = 0
    @Volatile private var lastExtractedShortsKey:      String  = ""
    @Volatile private var currentContext:              YoutubeContext = YoutubeContext.UNKNOWN

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Compiled patterns ─────────────────────────────────────────────────────

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
        // ── Change ONE line to switch targets ──────────────────────────────────
        // Emulator (Pixel AVD) → "10.0.2.2"
        // Physical device (same WiFi) → your PC's LAN IP, e.g. "192.168.1.16"
        private const val FLASK_HOST = "192.168.1.13"
        private const val FLASK_PORT = 5000
        private const val BASE_URL   = "http://$FLASK_HOST:$FLASK_PORT"

        private const val TITLE_RESET_MS    = 5 * 60 * 1000L
        private const val TIER3_TIMEOUT_MS  = 8_000L
        private const val TIER1_TIMEOUT_MS  = 120_000L

        // Skip terms — whole-word match for single words
        private val SKIP_WHOLE_WORD = setOf(
            "Subscribe", "Subscribed", "Join", "Bell", "Share", "Reply",
            "Report", "Queue", "Topic", "Shorts", "Explore", "Library",
            "Home", "Cast", "Minimize", "likes", "seconds", "Recommended",
        )
        private val SKIP_WHOLE_WORD_RE: Regex = run {
            val pattern = SKIP_WHOLE_WORD.joinToString("|") { Regex.escape(it) }
            Regex("(?i)\\b($pattern)\\b")
        }
        // Skip terms — substring match for multi-word terms
        private val SKIP_SUBSTRING = listOf(
            "Sponsored", "Advertisement", "Ad ·", "Skip Ads", "My Mix",
            "Trending", "Video player", "Minimized player", "More options",
            "Hide controls", "Enter fullscreen", "Voice search", "Choose Premium",
            "More actions", "Drag to reorder", "Close Repeat", "Shuffle Menu",
            "re playlists", "Add to queue", "Save to playlist",
            "Not interested", "Don't recommend channel", "Next:", "notifications",
            "minutes, ", "Go to channel", "Music for you", "Top podcasts",
            "Continue watching", "Up next", "Playing next", "Autoplay is",
            "Pause autoplay", "Mix -", "Why this ad", "Stop seeing this ad",
            "Visit advertiser", "Promoted", "Sponsored content",
            "K views", "M views", "B views", "months ago", "years ago",
            "days ago", "hours ago", "weeks ago", "See #", "videos ...more",
            "...more", "Add a comment", "@mention", "comment or @", "replies",
            "Pinned comment", "View all comments", "Comments are turned off",
            "Top comments", "Newest first", "Sort comments", "like this",
            "liked by", "Liked by creator", "Show more replies", "Hide replies",
            "Load more comments", "Be the first to comment", "No comments yet",
            "See more videos using this sound", "using this sound",
            "Original audio", "Original sound", "Collaboration channels",
            "View product", "Shop now", "Swipe up", "Add yours", "Remix this",
            "(Official", "- Topic", "♪", "♫", "🎵", "🎶",
            "Premium Lite", "Try Premium", "YouTube Premium",
            "you'll want to try", "Ad-free", "Get Premium",
            "Feature not available", "not available for this video",
            "New content available", "content is available",
            "Subscriptions:", "new content",
        )
        private val SKIP_NODE_CLASSES = listOf(
            "ImageButton", "ImageView", "ProgressBar", "SeekBar",
            "CheckBox", "Switch", "RadioButton",
        )
        private val AD_SIGNALS = listOf(
            "Skip Ads", "Skip ad", "Ad ·", "Why this ad",
            "Stop seeing this ad", "Visit advertiser", "Skip in",
        )
        private val COMMENT_SIGNALS = listOf(
            "Add a comment", "Top comments", "Newest first", "Sort comments",
            "Be the first to comment", "Pinned comment",
            "Show more replies", "Load more comments",
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
        println("[CF_SERVICE] ✓ Connected — ChildFocus v6 monitoring YouTube")
        syncFusionConfig()
    }

    override fun onInterrupt() {
        tier1Job.cancel()
        tier3Job.cancel()
        resetVolatileState()
    }

    private fun resetVolatileState() {
        eventTimeVideoId        = ""
        newShortsNavDetected    = false
        lastSeekPositionSec     = -1
        lastKnownDurationSec    = 0
        lastSentNormalizedTitle = ""
        lastSentTimeMs          = 0L
        lastGuardText           = ""
        lastGuardResult         = false
        lastShortsScreenHash    = 0
        lastExtractedShortsKey  = ""
        currentContext          = YoutubeContext.UNKNOWN
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 0 — EVENT GATE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Reset stale "lastSentTitle" guard after 5 minutes
        val now = System.currentTimeMillis()
        if (lastSentNormalizedTitle.isNotEmpty() &&
            (now - lastSentTimeMs) > TITLE_RESET_MS
        ) {
            lastSentNormalizedTitle = ""
            lastSentTimeMs          = 0L
            lastExtractedShortsKey  = ""
            lastShortsScreenHash    = 0
        }

        val eventType = event.eventType

        // Phase 0A — only on TYPE_WINDOW_STATE_CHANGED
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            syncFusionConfig()
            phase0A_extractEventTimeVideoId(event)
        }

        // Throttle content-changed events
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if ((now - lastSentTimeMs) < 500L) return
        }

        // Gate: only process the relevant event types
        if (eventType !in listOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            )
        ) return

        val root    = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)
        root.recycle()

        // Phase 2 — ad / comment guard (uses cached check)
        if (allText == lastGuardText && lastGuardResult) return
        val isAdOrComment = isAdPlaying(allText) || isCommentSectionVisible(allText)
        lastGuardText   = allText
        lastGuardResult = isAdOrComment
        if (isAdOrComment) return

        // Phase 1 — context detection
        currentContext = detectContext(allText)
        if (currentContext == YoutubeContext.UNKNOWN) return

        // For scroll events, only do feed extraction
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            currentContext != YoutubeContext.SHORTS_ACTIVE &&
            currentContext != YoutubeContext.LONG_FORM_ACTIVE
        ) return

        // Dispatch to Phase 4 by context
        when (currentContext) {
            YoutubeContext.SHORTS_ACTIVE   -> handleShortsActive(allText, event)
            YoutubeContext.LONG_FORM_ACTIVE -> handleLongFormActive(allText, event)
            YoutubeContext.SHORTS_FEED,
            YoutubeContext.LONG_FORM_FEED  -> { /* cache-warming only, not in scope here */ }
            YoutubeContext.UNKNOWN          -> Unit
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 0A — EVENT-TIME VIDEO ID EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun phase0A_extractEventTimeVideoId(event: AccessibilityEvent) {
        // V1 — event text list
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
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

    /**
     * V5 — Timed retry: the accessibility tree populates async after navigation.
     * Retry at 600ms, 1200ms, 2500ms.
     */
    private fun launchTimedRetry() {
        scope.launch(Dispatchers.IO) {
            val delays = listOf(600L, 1200L, 2500L)
            for (ms in delays) {
                delay(ms)
                val root = rootInActiveWindow ?: continue
                val text = collectAllNodeText(root)
                root.recycle()
                val m = URL_PATTERN.matcher(text)
                if (m.find()) {
                    val id = m.group(1) ?: continue
                    println("[CF_SERVICE] ✓ [0A_RETRY_${ms}ms] videoId=$id")
                    eventTimeVideoId     = id
                    newShortsNavDetected = false
                    return@launch
                }
            }
            newShortsNavDetected = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 1 — CONTEXT DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun detectContext(allText: String): YoutubeContext {
        val lower = allText.lowercase()

        // SHORTS_ACTIVE: Shorts player UI signals present + real screen check
        val hasShorts    = lower.contains("shorts")
        val hasViews     = lower.contains("views")
        val hasReelSeek  = lower.contains("reel_time_bar") || hasSeekBarInTree()
        if (hasShorts && !hasViews && (hasReelSeek || isRealShortsScreen(allText))) {
            return YoutubeContext.SHORTS_ACTIVE
        }

        // LONG_FORM_ACTIVE: play/pause button or WatchWhileActivity
        if (lower.contains("player_control_play_pause") ||
            lower.contains("watchwhileactivity")) {
            return YoutubeContext.LONG_FORM_ACTIVE
        }

        // LONG_FORM_FEED: feed-style layout
        if (lower.contains("views") && !lower.contains("shorts")) {
            return YoutubeContext.LONG_FORM_FEED
        }

        // SHORTS_FEED
        if (hasShorts) return YoutubeContext.SHORTS_FEED

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
            if (rid.contains("reel_time_bar") || node.className?.toString()?.contains("SeekBar") == true) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findSeekBarNode(child)
                if (found != null) { child.recycle(); return found }
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
    // SHORTS ACTIVE HANDLER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleShortsActive(allText: String, event: AccessibilityEvent) {

        // Fast-path: skip if screen content unchanged
        val screenHash = allText.hashCode()
        if (screenHash == lastShortsScreenHash) return
        lastShortsScreenHash = screenHash

        // Phase 4C — extract all signals
        val metadata = extractShortsMetadata() ?: return

        // SeekBar video-change detection
        if (isVideoChangeDetected(metadata.durationSeconds)) {
            // New video — reset state
            eventTimeVideoId    = ""
            lastSentNormalizedTitle = ""
            lastExtractedShortsKey  = ""
            println("[CF_SERVICE] ✓ [SEEKBAR] New Shorts video detected, dur=${metadata.durationSeconds}s")
        }

        // Dedup: same normalizedTitle as the last one sent — skip
        if (metadata.normalizedTitle == lastSentNormalizedTitle) return

        // Phase 5 — validate
        if (!isCleanTitle(metadata.titleRaw)) return

        // Same title+channel key as last extracted — skip
        val key = "${metadata.channelHandle}|${metadata.normalizedTitle}"
        if (key == lastExtractedShortsKey) return
        lastExtractedShortsKey  = key
        lastSentNormalizedTitle = metadata.normalizedTitle
        lastSentTimeMs          = System.currentTimeMillis()

        println("[CF_SERVICE] ✓ [SHORTS] title='${metadata.titleRaw}' " +
                "ch='${metadata.channelHandle}' dur=${metadata.durationSeconds}s " +
                "videoId='${metadata.videoId}' src='${metadata.videoIdSource}'")

        broadcastAnalyzing(metadata.videoId.ifEmpty { metadata.titleRaw.take(40) })

        // Phase 7 — Tier 2 restriction fast-path
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

        // Phase 3 — cache lookup
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
            if (cached.oirLabel == "Overstimulating") blockYouTubeVideo()
            return
        }

        // Phase 7 — SHORTS: launch Tier 3 (primary UX) ‖ Tier 1 (background)
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
    // LONG-FORM ACTIVE HANDLER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleLongFormActive(allText: String, event: AccessibilityEvent) {

        // Try event-time video ID first, then tree scan
        var videoId = eventTimeVideoId
        if (videoId.isEmpty()) {
            val m = URL_PATTERN.matcher(allText)
            if (m.find()) videoId = m.group(1) ?: ""
        }

        // Extract title from tree
        val title = extractLongFormTitle(allText) ?: return
        val normTitle = normalizeTitle(title)

        if (normTitle == lastSentNormalizedTitle) return
        if (!isCleanTitle(title)) return

        lastSentNormalizedTitle = normTitle
        lastSentTimeMs          = System.currentTimeMillis()
        eventTimeVideoId        = ""

        println("[CF_SERVICE] ✓ [LONG_FORM] videoId='$videoId' title='$title'")
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
            if (cached.oirLabel == "Overstimulating") blockYouTubeVideo()
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
        )

        // Long-form: Tier 1 is primary
        tier1Job.cancel()
        tier1Job = scope.launch { executeTier1(metadata, isShorts = false) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4C — SHORTS METADATA EXTRACTION (coordinate-based)
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

        // Noise filter
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

        // Locate @channel node closest to channelRowTarget
        val channelNode = filtered
            .filter { it.text.startsWith("@") && it.bottom > 0 }
            .minByOrNull { abs(it.bottom - channelRowT) }
            ?: return null

        val channelHandle  = channelNode.text.substringBefore(",").trim()
        val isVerified     = channelNode.text.contains("official", ignoreCase = true) ||
                             channelNode.text.contains("verified", ignoreCase = true)

        // SeekBar duration
        val seekNode  = nodes.firstOrNull { it.text.matches(Regex(
            "\\d+\\s+minutes?\\s+\\d+\\s+seconds?\\s+of\\s+\\d+\\s+minutes?\\s+\\d+\\s+seconds?",
            RegexOption.IGNORE_CASE
        )) }
        val durationSec = seekNode?.let { parseSeekDuration(it.text) } ?: 0
        if (seekNode != null) {
            val currentSec = parseSeekCurrent(seekNode.text)
            if (lastSeekPositionSec >= 0) lastSeekPositionSec = currentSec
        }

        // Candidate title nodes (settled overlay band)
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

        val titleRaw = candidates.firstOrNull()?.text
            ?.replace(Regex("[​‌‍﻿]"), "")
            ?.trim() ?: return null

        if (titleRaw.length < 3) return null

        // Restriction signals from the all-node text
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
            titleRaw          = titleRaw,
            titleForSearch    = titleRaw,
            normalizedTitle   = normalizeTitle(titleRaw),
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
    // PHASE 4D — LONG-FORM TITLE EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractLongFormTitle(allText: String): String? {
        // Strategy: VIEWS pattern captures the title before the view count
        val viewsMatch = VIEWS_PATTERN.matcher(allText)
        if (viewsMatch.find()) {
            val t = viewsMatch.group(1)?.trim() ?: ""
            if (isCleanTitle(t)) return t
        }
        // Strategy: @channel pattern
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
            val idx = allText.indexOf("@", m.end()) // find "@Handle" after title
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
        if (text.length < 8 || text.length > 200) return false

        // Whole-word single-term skip
        for (term in SKIP_WHOLE_WORD) {
            if (SKIP_WHOLE_WORD_RE.containsMatchIn(text) &&
                Regex("(?i)\\b${Regex.escape(term)}\\b").containsMatchIn(text)
            ) return false
        }
        // Substring skip
        for (term in SKIP_SUBSTRING) {
            if (text.contains(term, ignoreCase = true)) return false
        }

        val lower = text.lowercase()
        if (lower.contains(" thousand views") || lower.contains("- play short") ||
            lower.contains("affiliate")        || lower.contains("shopee") ||
            lower.contains("lazada")            || lower.contains("best seller") ||
            lower.contains("buy now")           || lower.contains("order now") ||
            lower.contains("link in bio")       ||
            lower.contains("say goodbye to")    || lower.contains("years younger") ||
            lower.contains("skin look")         || lower.contains("suitable for all") ||
            lower.contains("all skin types")    ||
            lower.startsWith("search ")         ||
            (lower.startsWith("view ") && lower.contains("comment")) ||
            lower.startsWith("helps ")          ||
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

    /** Launch Tier 3 ‖ Tier 1 in parallel. Tier 3 controls live UX. */
    private fun launchShortsClassification(metadata: VideoMetadata) {
        tier1Job.cancel()
        tier3Job.cancel()

        // Tier 3 — fast path (primary UX for Shorts)
        tier3Job = scope.launch {
            executeTier3(metadata)
        }
        // Tier 1 — full analysis (background, may upgrade result)
        tier1Job = scope.launch {
            executeTier1(metadata, isShorts = true)
        }
    }

    // ── TIER 1 — Full classification (audiovisual + NB fusion) ───────────────

    private suspend fun executeTier1(metadata: VideoMetadata, isShorts: Boolean) {
        syncFusionConfig()
        try {
            val responseJson: JSONObject
            if (metadata.videoId.isNotEmpty()) {
                // Case A — video ID available
                val body = JSONObject().apply {
                    put("video_url",     "https://www.youtube.com/watch?v=${metadata.videoId}")
                    put("thumbnail_url", metadata.thumbnailUrl)
                    put("hint_title",    metadata.titleForSearch)
                }
                responseJson = postJson("/classify_full", body)
            } else {
                // Case B — no video ID (typical for Shorts)
                val body = JSONObject().apply {
                    put("title",            metadata.titleForSearch)
                    put("channel",          metadata.channelHandle)
                    put("channel_id",       metadata.channelId)
                    put("duration_seconds", metadata.durationSeconds)
                    put("is_verified",      metadata.isVerifiedChannel)
                }
                responseJson = postJson("/classify_by_title", body)
            }

            val rawScore = responseJson.optDouble("score_final", 0.5).toFloat()
            val score    = applyRestrictionModifiers(rawScore, metadata)
            val label    = scoreTolabel(score)

            println("[CF_SERVICE] ✓ [TIER_1] ${metadata.titleRaw.take(40)} → $label ($score)")

            // Check for label upgrade vs Tier 3 result (log discrepancy)
            val prior = getCachedResult(metadata.normalizedTitle)
            if (prior != null && prior.oirLabel != label) {
                println("[CF_SERVICE] ⚠ [TIER_LABEL_MISMATCH] Tier3=${prior.oirLabel} Tier1=$label")
            }

            deliverResult(
                videoId    = metadata.videoId.ifEmpty { responseJson.optString("video_id", "") },
                label      = label,
                score      = score,
                tier       = 1,
                confidence = if (metadata.videoId.isNotEmpty()) "FULL" else "PARTIAL",
                cached     = false,
                metadata   = metadata,
            )
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ [TIER_1_FAIL] ${e.message}")
            // If Tier 3 has not resolved yet (Shorts), fall through to Tier 4
            if (!isShorts && getCachedResult(metadata.normalizedTitle) == null) {
                executeTier4(metadata)
            }
        }
    }

    // ── TIER 3 — NB + on-device thumbnail fusion (primary Shorts UX) ─────────

    private suspend fun executeTier3(metadata: VideoMetadata) {
        syncFusionConfig()
        try {
            val body = JSONObject().apply {
                put("title",       metadata.titleForSearch)
                put("tags",        org.json.JSONArray())
                put("description", cleanForClassification(metadata.description))
            }

            val startMs  = System.currentTimeMillis()
            val response = postJson("/classify_fast", body)
            val elapsed  = System.currentTimeMillis() - startMs

            if (elapsed > TIER3_TIMEOUT_MS) {
                println("[CF_SERVICE] ⚠ [TIER_3] timeout after ${elapsed}ms")
                executeTier4(metadata)
                return
            }

            val nbScore    = response.optDouble("score_nb", 0.5).toFloat()
            val thumbScore = computeThumbnailIntensityLocal(metadata.thumbnailUrl)

            val fusedScore = (fusionConfig.alpha_nb * nbScore +
                              fusionConfig.beta_heuristic * thumbScore)
                .coerceIn(0f, 1f)

            val score = applyRestrictionModifiers(fusedScore, metadata)
            val label = scoreTolabel(score)

            println("[CF_SERVICE] ✓ [TIER_3] nb=$nbScore thumb=$thumbScore " +
                    "fused=$score → $label")

            deliverResult(
                videoId    = metadata.videoId.ifEmpty { metadata.normalizedTitle },
                label      = label,
                score      = score,
                tier       = 3,
                confidence = "PARTIAL",
                cached     = false,
                metadata   = metadata,
            )
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ [TIER_3_FAIL] ${e.message}")
            executeTier4(metadata)
        }
    }

    // ── TIER 4 — Local keyword scoring + screen saturation ───────────────────

    private fun executeTier4(metadata: VideoMetadata) {
        val textScore = computeLocalOirScore(
            cleanForClassification(
                "${metadata.titleRaw} ${metadata.channelHandle} ${metadata.description.take(300)}"
            )
        )
        // No thumbnail available in this tier; use 0.5 neutral
        val satScore   = 0.5f
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

    /** Local keyword-based OIR score [0, 1] — used when backend unreachable. */
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
        if (meta.isGeorestricted) return s  // cannot analyze
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
        // Write to in-memory cache
        classificationCache[metadata.normalizedTitle] = CacheEntry(
            oirLabel    = label,
            score       = score,
            tier        = tier,
            confidence  = confidence,
            videoId     = videoId,
            timestampMs = System.currentTimeMillis(),
        )

        // Broadcast to MainActivity/SafetyModeScreen
        broadcastResult(
            videoId    = videoId,
            label      = label,
            score      = score,
            tier       = tier,
            confidence = confidence,
            cached     = cached,
        )

        // Block YouTube if overstimulating
        if (label == "Overstimulating") {
            blockYouTubeVideo()
            fetchAndShowSuggestions(videoId)
        }

        println("[CF_SERVICE] ✓ [T$tier/$confidence] $label ($score) → $videoId cached=$cached")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UX ACTIONS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Pauses YouTube then navigates to the home screen, removing the blocked
     * video from the screen. Must run on the main thread.
     */
    private fun blockYouTubeVideo() {
        mainHandler.post {
            val root = rootInActiveWindow
            if (root != null) {
                root.findAccessibilityNodeInfosByViewId(
                    "com.google.android.youtube:id/player_control_play_pause_replay_button"
                ).firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                root.recycle()
            }
            mainHandler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 300L)
        }
    }

    /**
     * Fetches safe suggestions in the background.
     * The overlay UI (overlay_blocked.xml) uses these to populate suggestion buttons.
     * In this implementation the suggestions are broadcast as a JSON extra;
     * the SafetyModeScreen / a future overlay service reads them.
     */
    private fun fetchAndShowSuggestions(excludeVideoId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/safe_suggestions?limit=3&exclude=$excludeVideoId"
                val response = http.newCall(
                    Request.Builder().url(url).build()
                ).execute()
                val body = response.body?.string() ?: return@launch
                val intent = Intent("com.childfocus.SAFE_SUGGESTIONS").apply {
                    putExtra("suggestions_json", body)
                }
                LocalBroadcastManager.getInstance(this@ChildFocusAccessibilityService)
                    .sendBroadcast(intent)
            } catch (e: Exception) {
                println("[CF_SERVICE] ✗ [SUGGESTIONS] ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FUSION CONFIG SYNC
    // ══════════════════════════════════════════════════════════════════════════

    private fun syncFusionConfig() {
        val now = System.currentTimeMillis()
        if (now - configLastFetchedMs < CONFIG_TTL_MS) return
        scope.launch(Dispatchers.IO) {
            try {
                val response = http.newCall(
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
                println("[CF_SERVICE] ✓ [CONFIG] $fusionConfig")
            } catch (e: Exception) {
                println("[CF_SERVICE] ⚠ [CONFIG_FAIL] ${e.message} — using last known values")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun getCachedResult(normalizedTitle: String): CacheEntry? {
        val entry = classificationCache[normalizedTitle] ?: return null
        val age   = System.currentTimeMillis() - entry.timestampMs
        return if (age < CACHE_TTL_MS) entry else null.also {
            classificationCache.remove(normalizedTitle)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NETWORK HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        return JSONObject(response.body?.string() ?: "{}")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BROADCAST HELPERS
    // ══════════════════════════════════════════════════════════════════════════

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
    // ON-DEVICE THUMBNAIL INTENSITY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Downloads the thumbnail and computes mean HSV saturation.
     * Returns 0f if the URL is empty or download fails.
     */
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

    // ══════════════════════════════════════════════════════════════════════════
    // NODE TREE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Collects all visible text from the accessibility node tree as a flat string.
     * Skips pure image/progress nodes to reduce noise.
     */
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

    /**
     * Collects TextNode records (text + on-screen Y bounds) for positional
     * Shorts extraction. Includes every non-empty node.
     */
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

    /**
     * Returns true when the Shorts player overlay is on screen.
     * Requires at least 2 of the persistent Shorts action signals.
     */
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
}
