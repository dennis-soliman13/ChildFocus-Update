package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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

class ChildFocusAccessibilityService : AccessibilityService() {

    companion object {
        // ── Change this ONE line to switch targets ────────────────────────
        // Emulator (Pixel AVD) → "10.0.2.2"
        // Physical (same WiFi) → your PC's local IP e.g. "192.168.1.x"
        private const val FLASK_HOST = "192.168.100.241"
        private const val FLASK_PORT = 5000
        private const val BASE_URL = "http://$FLASK_HOST:$FLASK_PORT"

        private const val TITLE_RESET_MS = 5 * 60 * 1000L
        private const val DEBOUNCE_MS = 1500L

        private const val PRIORITY_PLAYING = 3
        private const val PRIORITY_ACTIVE  = 2

        private val SKIP_TITLES = listOf(
            "Shorts", "Sponsored", "Advertisement", "Ad ·", "Skip Ads",
            "My Mix", "Trending", "Explore", "Subscriptions", "Library",
            "Home", "Video player", "Minimized player", "Minimize", "Cast",
            "More options", "Hide controls", "Enter fullscreen", "Rewind",
            "Fast forward", "Navigate up", "Voice search", "Choose Premium",
            "More actions", "YouTube makes for you", "Drag to reorder",
            "Action menu", "Options menu", "Overflow menu",
            "Close Repeat", "Shuffle Menu", "Sign up", "re playlists",
            "Queue", "ylists", "Add to queue", "Save to playlist", "Share",
            "Report", "Not interested", "Don't recommend channel", "Next:",
            "Subscribe", "Subscribed", "Join", "Bell", "notifications",
            "minutes, ", "seconds", "Go to channel",
            "Music for you", "TikTok Lite", "Top podcasts", "Recommended",
            "Continue watching", "Up next", "Playing next", "Autoplay is",
            "Pause autoplay", "Mix -", "Topic",
            "Why this ad", "Stop seeing this ad", "Visit advertiser",
            "Ad ", "Promoted", "Sponsored content",
            "K views", "M views", "B views", "months ago", "years ago",
            "days ago", "hours ago", "weeks ago", "See #", "videos ...more",
            "...more",
            "Add a comment", "@mention", "comment or @", "Reply", "replies",
            "Pinned comment", "View all comments", "Comments are turned off",
            "Top comments", "Newest first", "Sort comments", "likes",
            "like this", "liked by", "Liked by creator", "Show more replies",
            "Hide replies", "Load more comments", "Be the first to comment",
            "No comments yet",
            "See more videos using this sound", "using this sound",
            "Original audio", "Original sound", "Collaboration channels",
            "View product", "Shop now", "Swipe up", "Add yours", "Remix this",
            "(Official", "- Topic", "♪", "♫", "🎵", "🎶",
            "Premium Lite", "Try Premium", "YouTube Premium",
            "you'll want to try", "Ad-free", "Get Premium",
            "Feature not available", "not available for this video",
            // Notification / banner false positives
            "New content available", "content is available",
            "Subscriptions:", "new content",
        )

        /**
         * Short single-word UI terms that must only match as whole words,
         * not as substrings inside real title words (e.g. "Subscribe" must
         * not reject "subscriber", "seconds" must not reject "split seconds").
         */
        private val SKIP_TITLES_WHOLE_WORD = setOf(
            "Subscribe", "Subscribed", "Join", "Bell", "Share", "Reply",
            "Report", "Queue", "Topic", "Shorts", "Explore", "Library",
            "Home", "Cast", "Minimize", "likes", "seconds", "Recommended",
        )

        // Pre-compiled regex for whole-word SKIP_TITLES matching (case-insensitive)
        private val SKIP_TITLES_WHOLE_WORD_RE: Regex = run {
            val pattern = SKIP_TITLES_WHOLE_WORD
                .joinToString("|") { Regex.escape(it) }
            Regex("(?i)\\b($pattern)\\b")
        }

        private val SKIP_NODE_CLASSES = listOf(
            "android.widget.ImageButton",
            "android.widget.ImageView",
            "android.widget.ProgressBar",
            "android.widget.SeekBar",
            "android.widget.CheckBox",
            "android.widget.Switch",
            "android.widget.RadioButton",
        )

        private val CHANNEL_HANDLE_RE = Regex("^[A-Z][a-zA-Z0-9]{4,40}$")
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var currentJob: Job = Job().also { it.cancel() }
    @Volatile private var currentPriority = 0
    @Volatile private var currentTarget = ""

    private var lastSentTitle   = ""
    private var lastSentTimeMs  = 0L
    private var pendingTitle    = ""
    private var lastEventTimeMs = 0L

    @Volatile private var lastGuardText      = ""
    @Volatile private var lastGuardResult    = false
    @Volatile private var shortsPendingTitle = ""
    @Volatile private var lastExtractedShortsKey  = ""
    @Volatile private var lastShortsScreenHash   = 0
    @Volatile private var screenTimeLimitEnforced = false  // prevent toast spam

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val VIEWS_PATTERN = Pattern.compile(
        "([A-Z][^\\n]{10,150})\\s+[\\d.,]+[KMBkm]?\\s+views",
        Pattern.CASE_INSENSITIVE
    )
    private val AT_CHANNEL_PATTERN = Pattern.compile(
        "([A-Z][^\\n@]{10,150})\\s{1,4}@([\\w]{2,50})(?:\\s|$)"
    )
    private val URL_PATTERN = Pattern.compile(
        "(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // DATA CLASS
    // ═══════════════════════════════════════════════════════════════════════

    private data class ShortsInfo(val title: String, val channel: String)

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        println("[CF_SERVICE] ✓ Connected — monitoring YouTube + screen time")
        // Start the 60-second tick that enforces limits mid-session
        mainHandler.postDelayed(screenTimeTicker, 60_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // ── Screen-time enforcement ──────────────────────────────────────
        // TYPE_WINDOW_STATE_CHANGED fires for everything: dialogs, IME,
        // system overlays, notifications — not just app switches.
        // We filter to genuine Activity-level transitions only; overlays
        // and dialogs don't end their className with "Activity" so they
        // won't accidentally reset the foreground timer.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString()  ?: ""
            val isActivity = cls.endsWith("Activity") ||
                    cls.contains("Activity\$") ||
                    cls.contains(".Activity")
            if (pkg.isNotEmpty() && isActivity) {
                val overLimit = ScreenTimeManager.onAppForeground(this, pkg)
                if (overLimit) {
                    enforceScreenTimeLimit(pkg)
                    return
                }
            }
        }

        val now = System.currentTimeMillis()
        if (lastSentTitle.isNotEmpty() && (now - lastSentTimeMs) > TITLE_RESET_MS) {
            lastSentTitle          = ""
            lastSentTimeMs         = 0L
            lastExtractedShortsKey  = ""
            lastShortsScreenHash   = 0
        }

        val root    = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)
        root.recycle()

        // ── Guard: skip duplicate screen states ──────────────────────────
        if (allText == lastGuardText && lastGuardResult) return
        val isAdOrComment = isAdPlaying(allText) || isCommentSectionVisible(allText)
        lastGuardText   = allText
        lastGuardResult = isAdOrComment
        if (isAdOrComment) return

        // ── Shorts detection (checked first) ─────────────────────────────
        // isRealShortsScreen: "Shorts" tab label present AND at least two of
        // the persistent Shorts action buttons are visible — this rules out the
        // Subscriptions feed notification banner which also contains "Shorts".
        val isShorts = allText.contains("Shorts") &&
                !allText.contains("views", ignoreCase = true) &&
                isRealShortsScreen(allText)

        if (isShorts) {
            // Fast-path: skip node walk if screen content hasn't changed.
            val screenHash = allText.hashCode()
            if (screenHash != lastShortsScreenHash) {
                lastShortsScreenHash = screenHash
                val info = extractShortsInfo(allText)
                if (info != null &&
                    info.title != lastSentTitle &&
                    info.title != shortsPendingTitle
                ) {
                    shortsPendingTitle = info.title

                    // ── FIX: try to extract a real video ID from the URL ──
                    // The Shorts URL (shorts/XXXXXXXXXXX) is sometimes present
                    // in the accessibility tree. If we can grab it here we skip
                    // the unreliable title-based YouTube search entirely and go
                    // straight to /classify_full with the exact video ID.
                    val extractedId = tryExtractShortsVideoId(allText)
                    if (extractedId != null) {
                        println("[CF_SERVICE] ✓ [SHORTS] ID extracted from tree: $extractedId")
                        enqueue(extractedId, PRIORITY_PLAYING) { doHandleVideoId(extractedId) }
                    } else {
                        println("[CF_SERVICE] ✓ [SHORTS] title='${info.title}' channel='${info.channel}' (no ID in tree — using title search)")
                        enqueue(info.title, PRIORITY_ACTIVE) { doDispatchTitle(info.title, info.channel) }
                    }
                }
            }
            return   // always return on Shorts screen — never fall through to long-form strategies
        }

        // ── Strategy 0: Direct video ID from event text ──────────────────
        val eventText = event.text?.joinToString(" ") ?: ""
        val urlMatch  = URL_PATTERN.matcher(eventText)
        if (urlMatch.find()) {
            val videoId = urlMatch.group(1) ?: return
            enqueue(videoId, PRIORITY_PLAYING) { doHandleVideoId(videoId) }
            return
        }

        // ── Strategy 1: Video ID in full node tree ────────────────────────
        val urlInTree = URL_PATTERN.matcher(allText)
        if (urlInTree.find()) {
            val videoId = urlInTree.group(1) ?: return
            enqueue(videoId, PRIORITY_PLAYING) { doHandleVideoId(videoId) }
            return
        }

        // ── Strategy 3: Title before view count ──────────────────────────
        val viewsMatch = VIEWS_PATTERN.matcher(allText)
        if (viewsMatch.find()) {
            val t = viewsMatch.group(1)?.trim() ?: return
            if (isCleanTitle(t)) {
                enqueue(t, PRIORITY_ACTIVE) { doDispatchTitle(t) }
                return
            }
        }

        // ── Strategy 4: Title before @channel ────────────────────────────
        val atMatch = AT_CHANNEL_PATTERN.matcher(allText)
        if (atMatch.find()) {
            val t  = atMatch.group(1)?.trim() ?: return
            val ch = atMatch.group(2)?.trim() ?: ""
            if (isCleanTitle(t)) {
                enqueue(t, PRIORITY_ACTIVE) { doDispatchTitle(t, ch) }
                return
            }
        }
    }

    override fun onInterrupt() {
        currentJob.cancel()
        mainHandler.removeCallbacks(screenTimeTicker)
        ScreenTimeManager.onAppBackground(this)
        lastSentTitle      = ""
        lastSentTimeMs     = 0L
        pendingTitle       = ""
        lastEventTimeMs    = 0L
        currentPriority        = 0
        currentTarget          = ""
        shortsPendingTitle     = ""
        lastExtractedShortsKey  = ""
        lastShortsScreenHash   = 0
        screenTimeLimitEnforced = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(screenTimeTicker)
        ScreenTimeManager.onAppBackground(this)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHORTS VIDEO ID EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attempts to extract a Shorts video ID directly from the accessibility
     * tree text (e.g. from a "shorts/XXXXXXXXXXX" URL node).
     *
     * Returns the 11-character video ID if found, null otherwise.
     * This is the preferred fast path — it bypasses the title-based YouTube
     * search entirely and guarantees we classify the correct video.
     */
    private fun tryExtractShortsVideoId(allText: String): String? {
        val root = rootInActiveWindow ?: return null
        val nodes = collectTextNodes(root)
        root.recycle()

        // Search every node's text for a shorts/ URL pattern
        for (node in nodes) {
            val m = URL_PATTERN.matcher(node.text)
            if (m.find()) {
                val id = m.group(1) ?: continue
                // Sanity check: must be exactly 11 chars and look like a real ID
                if (id.length == 11 && id.matches(Regex("[a-zA-Z0-9_-]{11}"))) {
                    return id
                }
            }
        }

        // Also scan allText as a fallback (collectAllNodeText may include URLs
        // that collectTextNodes misses due to class filtering)
        val m = Pattern.compile("shorts/([a-zA-Z0-9_-]{11})").matcher(allText)
        if (m.find()) {
            val id = m.group(1) ?: return null
            if (id.length == 11) return id
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHORTS TITLE + CHANNEL EXTRACTION (node-based, coordinate-targeted)
    // ═══════════════════════════════════════════════════════════════════════

    private data class TextNode(val text: String, val top: Int, val bottom: Int)

    /**
     * Walks the raw node tree collecting every non-empty text/contentDesc
     * with its screen bounds. No class filtering — we need everything.
     */
    private fun collectTextNodes(node: AccessibilityNodeInfo): List<TextNode> {
        val result = mutableListOf<TextNode>()
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            val texts = mutableListOf<String>()
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }

            for (t in texts) {
                if (t.matches(Regex("[\\d.,:\\s]+[KMBkm]?"))) continue
                result += TextNode(text = t, top = rect.top, bottom = rect.bottom)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                result += collectTextNodes(child)
                child.recycle()
            }
        } catch (_: Exception) { }
        return result
    }

    private fun extractShortsInfo(allText: String): ShortsInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = collectTextNodes(root)
        root.recycle()

        if (nodes.isEmpty()) return null

        // ── Runtime screen dimensions ─────────────────────────────────────
        // All pixel thresholds are derived from the actual screen height so that
        // the function works correctly across:
        //   • phones vs tablets (different resolutions)
        //   • portrait vs landscape (screen height changes on rotation)
        val screenHeight = resources.displayMetrics.heightPixels

        // The Shorts overlay UI (title + channel row) always sits in the bottom
        // ~20–97% of the screen height regardless of device or orientation.
        //
        //  SETTLED_BOTTOM_MIN  — nodes above this are top-of-screen chrome
        //  SETTLED_BOTTOM_MAX  — nodes below this are off-screen / animating in
        //  CHANNEL_ROW_TARGET  — expected Y position of the @channel handle row
        //  NEIGHBOUR_TOLERANCE — how many px below channelNode.bottom we still trust
        val SETTLED_BOTTOM_MIN   = (screenHeight * 0.72).toInt()   // ~72% down
        val SETTLED_BOTTOM_MAX   = (screenHeight * 0.97).toInt()   // ~97% down
        val CHANNEL_ROW_TARGET   = (screenHeight * 0.87).toInt()   // ~87% down
        val NEIGHBOUR_TOLERANCE  = (screenHeight * 0.18).toInt()   // ~18% of height

        // ── Noise filter: drop pure UI chrome and play-button-like nodes ─
        // We keep @handle nodes and anything that looks like real content text.
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

        // ── Music label detector ──────────────────────────────────────────
        fun isMusicLabel(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("♪") || lower.contains("♫") ||
                    lower.contains("🎵") || lower.contains("🎶") ||
                    lower.contains("original audio") || lower.contains("original sound")
        }

        // ── Seek bar text detector ────────────────────────────────────────
        // e.g. "0 minutes 6 seconds of 0 minutes 8 seconds"
        val seekBarRe = Regex(
            "^\\d+\\s+minutes?\\s+\\d+\\s+seconds?\\s+of\\s+\\d+\\s+minutes?\\s+\\d+\\s+seconds?$",
            RegexOption.IGNORE_CASE
        )

        // ── Locate the @channel node ──────────────────────────────────────
        // Pick the @channel whose bottom is closest to CHANNEL_ROW_TARGET
        // (derived from real screen height — works for any device/orientation).
        // During swipe animations multiple @handles may appear; we want the one
        // anchored in the active overlay zone, not a neighbour sliding in/out.
        val channelNode = filtered
            .filter { it.text.startsWith("@") && it.bottom > 0 }
            .minByOrNull { Math.abs(it.bottom - CHANNEL_ROW_TARGET) }
            ?: return null

        // ── Collect candidate content nodes ──────────────────────────────
        // Rules:
        //  • not the @channel node itself
        //  • not another @handle
        //  • not a music label
        //  • not seek-bar text ("X minutes Y seconds of …")
        //  • bottom must be within the settled overlay band
        //  • must not be a neighbour video's node (bottom ≤ channelNode.bottom + tolerance)
        //  • top < bottom (reject inverted/animating nodes)
        //  • at least 3 chars
        //
        // SETTLED band explanation:
        //   During swipe animations the title node slides in from below — its
        //   bottom is inflated or its top > bottom (inverted rect). We only trust
        //   nodes that have landed inside the stable overlay zone.
        val maxAllowedBottom = minOf(channelNode.bottom + NEIGHBOUR_TOLERANCE, SETTLED_BOTTOM_MAX)

        val candidates = filtered
            .filter { it.text != channelNode.text && !it.text.startsWith("@") }
            .filterNot { it.text.trimStart().startsWith("#") }          // bare hashtag nodes
            .filterNot { it.text.lowercase().startsWith("search ") }    // YouTube search suggestions
            .filterNot { isMusicLabel(it.text) }
            .filterNot { it.text.contains(" · ") }                      // music strip "Song · Artist"
            .filterNot { seekBarRe.matches(it.text.trim()) }
            // Only accept nodes within the settled overlay band
            .filter { it.bottom in SETTLED_BOTTOM_MIN..maxAllowedBottom }
            .filter { it.top < it.bottom }   // reject inverted/animating nodes
            .filter { it.text.length >= 3 }
            .sortedByDescending { it.bottom }

        if (candidates.isEmpty()) return null

        // ── Detect music strip ────────────────────────────────────────────
        val hasMusicStrip = filtered
            .filter { it.top >= channelNode.top && it.bottom <= SETTLED_BOTTOM_MAX && isMusicLabel(it.text) }
            .isNotEmpty()

        // ── Positional pick ───────────────────────────────────────────────
        // Strip hashtags, zero-width chars, and any trailing token that looks
        // like a channel handle (CamelCase/alphanumeric, 4-40 chars, no spaces).
        // Keeping raw hashtags in the title sent to the server was polluting
        // YouTube search queries and causing wrong video resolution (e.g.
        // "Dopamine candle cleaning #wax #asmr RajendranAzam" would match
        // unrelated videos). The NB classifier gets the real description/tags
        // from the resolved video anyway, so stripping here is safe.
        val titleRaw = candidates[0].text

        // 1. Strip invisible zero-width chars
        var titleClean = titleRaw.replace(Regex("[​‌‍﻿]"), "").trim()

        // 2. Remove everything from the first '#' onward (hashtag block)
        val hashIdx = titleClean.indexOf('#')
        if (hashIdx > 0) titleClean = titleClean.substring(0, hashIdx).trim()

        // 3. Strip a trailing token that matches a channel-handle pattern:
        //    a run of alphanumeric chars (4-40), no spaces, at end of string.
        //    This catches handles like "RajendranAzam", "life-007fi", "DewaFFreal"
        //    that sometimes get appended when the node tree has the @channel
        //    text adjacent to the title node without a separator.
        titleClean = titleClean
            .replace(Regex("\\s+[A-Za-z][A-Za-z0-9_-]{3,39}$"), "")
            .trim()

        if (titleClean.length < 3) return null

        // ── Dedup: skip if same title+channel as last resolved result ─────
        val key = "${channelNode.text}|$titleClean"
        if (key == lastExtractedShortsKey) return null
        lastExtractedShortsKey = key

        println("[CF_SERVICE] ✓ [SHORTS] screenH=$screenHeight settled=$SETTLED_BOTTOM_MIN..$SETTLED_BOTTOM_MAX channelRow=$CHANNEL_ROW_TARGET")
        return ShortsInfo(title = titleClean, channel = channelNode.text)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GUARDS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns true only when the Shorts player is actually on screen.
     * Reads contentDescriptions directly from the unfiltered node tree so
     * action buttons (like/dislike/share) are always visible.
     */
    private fun isRealShortsScreen(allText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = collectTextNodes(root)
        root.recycle()
        val combined = nodes.joinToString(" ") { it.text }.lowercase()
        val shortsUiSignals = listOf(
            "like", "dislike", "comment", "share", "remix",
            "add yours", "mute", "pause", "play", "seek bar", "video progress"
        )
        val matchCount = shortsUiSignals.count { combined.contains(it) }
        return matchCount >= 2
    }

    private fun isAdPlaying(allText: String): Boolean =
        listOf("Skip Ads", "Skip ad", "Ad ·", "Why this ad",
            "Stop seeing this ad", "Visit advertiser", "Skip in")
            .any { allText.contains(it, ignoreCase = true) }

    private fun isCommentSectionVisible(allText: String): Boolean =
        listOf("Add a comment", "Top comments", "Newest first", "Sort comments",
            "Be the first to comment", "Pinned comment",
            "Show more replies", "Load more comments")
            .any { allText.contains(it, ignoreCase = true) }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun collectAllNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            val className    = node.className?.toString() ?: ""
            val isSkipped    = SKIP_NODE_CLASSES.any { className.endsWith(it.substringAfterLast('.')) }
            val textLen      = node.text?.length ?: 0
            val isButtonLike = node.isClickable && textLen in 1..25 && node.childCount == 0
            if (!isSkipped && !isButtonLike) {
                node.text?.let { sb.append(it).append("\n") }
                node.contentDescription?.let { sb.append(it).append("\n") }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb.append(collectAllNodeText(child))
                child.recycle()
            }
        } catch (_: Exception) { }
        return sb.toString()
    }

    /**
     * Returns true if the given skip-term matches [text] in a way that should
     * block it from being treated as a video title.
     *
     * Short single-word UI terms (defined in SKIP_TITLES_WHOLE_WORD) are matched
     * as whole words only, so "Subscribe" does NOT block "subscriber", and
     * "seconds" does NOT block "split seconds record".
     *
     * All other (multi-word or punctuated) SKIP_TITLES entries use the original
     * plain substring match because they are specific enough to avoid false
     * positives.
     */
    private fun skipTitleMatches(text: String, skipTerm: String): Boolean {
        return if (skipTerm in SKIP_TITLES_WHOLE_WORD) {
            SKIP_TITLES_WHOLE_WORD_RE.containsMatchIn(text)
                    // containsMatchIn checks all words in one pass; we only want to
                    // fire for THIS specific skipTerm, so verify it independently.
                    && Regex("(?i)\\b${Regex.escape(skipTerm)}\\b").containsMatchIn(text)
        } else {
            text.contains(skipTerm, ignoreCase = true)
        }
    }

    private fun isCleanTitle(text: String): Boolean {
        if (text.length < 8 || text.length > 200) return false
        if (SKIP_TITLES.any { skipTitleMatches(text, it) }) return false
        val lowerText = text.lowercase()
        if (lowerText.contains(" thousand views") || lowerText.contains("- play short") ||
            lowerText.contains("affiliate")       || lowerText.contains("shopee") ||
            lowerText.contains("lazada")           || lowerText.contains("best seller") ||
            lowerText.contains("buy now")          || lowerText.contains("order now") ||
            lowerText.contains("link in bio")      ||
            lowerText.contains("say goodbye to")   || lowerText.contains("one pair for") ||
            lowerText.contains("years younger")    || lowerText.contains("skin look") ||
            lowerText.contains("suitable for all") || lowerText.contains("all skin types") ||
            lowerText.startsWith("search ") ||
            (lowerText.startsWith("view ") && lowerText.contains("comment")) ||
            lowerText.contains("palawan")          || lowerText.contains("sangla") ||
            lowerText.startsWith("helps ")         ||
            lowerText.startsWith("get ") && text.length < 60 ||
            lowerText.startsWith("try ") && text.length < 50
        ) return false
        // Note: hashtag stripping is done before isCleanTitle is called for Shorts
        if (Regex("[A-Za-z]{1,4}-?\\d{4,}[A-Za-z0-9]*").containsMatchIn(text)) return false
        if (Regex("^[A-Z][a-zA-Z]+ [·•] [A-Z][a-zA-Z]+$").containsMatchIn(text)) return false
        if (CHANNEL_HANDLE_RE.matches(text.trim())) return false
        if (text.trim().split(Regex("\\s+")).size < 2) return false
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PAUSE CONTROL
    // ═══════════════════════════════════════════════════════════════════════

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Screen-time periodic ticker ───────────────────────────────────────
    // Runs every 60 s while the service is alive. Flushes the current
    // foreground window and re-checks the limit so the block fires
    // mid-session rather than only on the next app switch.
    private val screenTimeTicker = object : Runnable {
        override fun run() {
            val overLimit = ScreenTimeManager.tick(this@ChildFocusAccessibilityService)
            if (overLimit) {
                enforceScreenTimeLimit(ScreenTimeManager.getCurrentForegroundPkg())
            } else {
                // Not over limit — could be a new day after midnight reset.
                // Clear the toast gate so it fires again when the next limit is hit.
                screenTimeLimitEnforced = false
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    /**
     * Pauses YouTube then navigates home to fully remove the blocked video.
     * Must run on the main thread — dispatched via mainHandler from IO coroutines.
     */
    private fun blockYouTubeVideo() {
        mainHandler.post {
            // 1. Hit the play/pause button to stop playback
            val root = rootInActiveWindow
            if (root != null) {
                root.findAccessibilityNodeInfosByViewId(
                    "com.google.android.youtube:id/player_control_play_pause_replay_button"
                ).firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                root.recycle()
            }
            // 2. After a short delay, go to home screen so the video is off screen
            mainHandler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 300)
        }
    }

    /**
     * Called when a package has exceeded its configured daily limit.
     * Sends the user home and shows a toast — you can replace the toast
     * with a full BlockOverlayService call if preferred.
     */
    private fun enforceScreenTimeLimit(packageName: String) {
        mainHandler.post {
            // Always kick back to home — runs every time the app is (re)opened
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Toast shown only once per enforcement session to avoid spam
            if (!screenTimeLimitEnforced) {
                screenTimeLimitEnforced = true
                Toast.makeText(
                    this,
                    "⏱️ Daily screen time limit reached for this app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIORITY QUEUE
    // ═══════════════════════════════════════════════════════════════════════

    private fun enqueue(target: String, priority: Int, block: suspend () -> Unit) {
        synchronized(this) {
            when {
                target == currentTarget && currentJob.isActive -> Unit
                priority > currentPriority && currentJob.isActive -> {
                    currentJob.cancel()
                    startJob(target, priority, block)
                }
                !currentJob.isActive -> startJob(target, priority, block)
                else -> Unit
            }
        }
    }

    private fun startJob(target: String, priority: Int, block: suspend () -> Unit) {
        currentTarget   = target
        currentPriority = priority
        currentJob = scope.launch {
            try { block() }
            finally {
                synchronized(this@ChildFocusAccessibilityService) {
                    if (currentTarget == target) {
                        currentPriority = 0
                        currentTarget   = ""
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WORKERS
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun doHandleVideoId(videoId: String) {
        if (videoId == lastSentTitle) return
        lastSentTitle  = videoId
        lastSentTimeMs = System.currentTimeMillis()
        println("[CF_SERVICE] ✓ [PLAYING] $videoId")
        broadcastResult(videoId, "Analyzing", 0f, false)
        if (!currentJob.isActive) return
        classifyByUrl(
            videoId,
            "https://www.youtube.com/watch?v=$videoId",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )
    }

    private suspend fun doDispatchTitle(title: String, channel: String = "") {
        if (title.length < 8 || title == lastSentTitle) return
        pendingTitle    = title
        lastEventTimeMs = System.currentTimeMillis()
        delay(DEBOUNCE_MS)
        if (!currentJob.isActive) return
        if (pendingTitle != title) return
        if ((System.currentTimeMillis() - lastEventTimeMs) < DEBOUNCE_MS) return
        lastSentTitle      = title
        shortsPendingTitle = ""
        lastSentTimeMs     = System.currentTimeMillis()
        println("[CF_SERVICE] ✓ [ACTIVE] title='$title' channel='$channel'")
        broadcastResult(title, "Analyzing", 0f, false)
        if (!currentJob.isActive) return
        classifyByTitle(title, channel)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════════════════

    private fun classifyByTitle(title: String, channel: String = "") {
        try {
            val body = JSONObject().apply {
                put("title", title)
                if (channel.isNotEmpty()) put("channel", channel)
            }
            val request = Request.Builder()
                .url("$BASE_URL/classify_by_title")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return)
            handleClassificationResult(json)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_by_title: ${e.message}")
        }
    }

    private fun classifyByUrl(videoId: String, videoUrl: String, thumbUrl: String) {
        try {
            val body = JSONObject().apply {
                put("video_url", videoUrl)
                put("thumbnail_url", thumbUrl)
            }
            val request = Request.Builder()
                .url("$BASE_URL/classify_full")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return)
            handleClassificationResult(json)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_full: ${e.message}")
        }
    }

    private fun handleClassificationResult(json: JSONObject) {
        val label   = json.optString("oir_label", "Neutral")
        val score   = json.optDouble("score_final", 0.5)
        val cached  = json.optBoolean("cached", false)
        val videoId = json.optString("video_id", "unknown")
        println("[CF_SERVICE] $videoId → $label ($score) cached=$cached")

        // ── Pause YouTube immediately when overstimulating content detected ─
        val isBlocked = label.equals("Overstimulating", ignoreCase = true) ||
                label.equals("Overstimulation",  ignoreCase = true)
        if (isBlocked) {
            blockYouTubeVideo()
        }

        broadcastResult(videoId, label, score.toFloat(), cached)
    }

    private fun broadcastResult(videoId: String, label: String, score: Float, cached: Boolean) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id", videoId)
            putExtra("oir_label", label)
            putExtra("score_final", score)
            putExtra("cached", cached)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}