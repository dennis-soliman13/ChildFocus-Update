package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

/**
 * ChildFocusAccessibilityService
 *
 * VIDEO ID EXTRACTION — FIX LOG
 * ─────────────────────────────
 * Bug: Shorts titled "You Grow Girl!" returned ID "_mtQ9AEFn9Q" which mapped to a
 *      completely different video on YouTube.
 *
 * Root Cause 1 — Title-based strategies (2/3/4) called /classify_by_title, which does a
 *   YouTube search (`ytsearch1:{title}`) and returns the first search result. That result
 *   is NOT guaranteed to be the video currently playing. A different video with a
 *   similar title (or simply the top result for that query) gets classified instead.
 *
 * Root Cause 2 — Strategy 1 scanned the ENTIRE accessibility tree text using
 *   collectAllNodeText(root), which includes recommendation card URLs. The first URL
 *   match in the concatenated string could belong to a recommendation, not the
 *   currently playing video.
 *
 * Fix 1 — Strategy 0b (NEW): Check event.source node before scanning the full tree.
 *   The source is the specific view that fired the accessibility event — it is almost
 *   always in the video player area, not in recommendation cards.
 *
 * Fix 2 — Strategy 1: extractPlayerVideoId() now scans the full tree but prefers
 *   URL matches that appear close to "views" text in the same block. The playing
 *   video area shows "X views · Y ago" while recommendation cards show their own
 *   view counts further away from their URL nodes.
 *
 * Fix 3 — Strategy 2 (Shorts): Changed to doDispatchTitleFast() → /classify_fast.
 *   Shorts titles are reliably extracted by extractShortsTitle(), but there is no
 *   Shorts URL in the accessibility tree (Shorts uses a swipe feed, not watch?v=).
 *   classifyFastByTitle() sends just the title to /classify_fast (NB-only).
 *   No YouTube search, no wrong video ID.
 *
 * Fix 4 — Strategies 3/4 (regular video title): classifyByTitle() now verifies the
 *   returned video_title against the detected title using Jaccard word-overlap
 *   similarity. If similarity < TITLE_SIMILARITY_THRESHOLD (0.35), the YouTube search
 *   returned the wrong video and the result is discarded. classifyFastByTitle() is
 *   called as the fallback (NB-only on the original detected title).
 */
class ChildFocusAccessibilityService : AccessibilityService() {

    companion object {
        // ── Change this ONE line to switch targets ────────────────────────
        // Emulator (Pixel AVD)     → "10.0.2.2"
        // Physical (same WiFi)     → your PC's local IP e.g. "192.168.1.x"
        private const val FLASK_HOST = "192.168.1.21"
        private const val FLASK_PORT = 5000
        private const val BASE_URL   = "http://$FLASK_HOST:$FLASK_PORT"

        private const val TITLE_RESET_MS = 5 * 60 * 1000L
        private const val DEBOUNCE_MS    = 1500L

        // ── Priority levels ───────────────────────────────────────────────
        // PLAYING = direct video ID from URL — most accurate, highest priority
        // ACTIVE  = title detected next to view count while video is playing
        private const val PRIORITY_PLAYING = 3
        private const val PRIORITY_ACTIVE  = 2

        // ── Title similarity threshold for verify-then-classify ──────────
        // If the title returned by /classify_by_title has less than this
        // word-overlap ratio with the detected title, the search returned
        // the wrong video. Fall back to /classify_fast (NB-only).
        private const val TITLE_SIMILARITY_THRESHOLD = 0.35

        // ── UI strings that are never real video titles ───────────────────
        private val SKIP_TITLES = listOf(
            // Player controls
            "Shorts", "Sponsored", "Advertisement", "Ad ·", "Skip Ads",
            "My Mix", "Trending", "Explore", "Subscriptions",
            "Library", "Home", "Video player", "Minimized player",
            "Minimize", "Cast", "More options", "Hide controls",
            "Enter fullscreen", "Rewind", "Fast forward", "Navigate up",
            "Voice search", "Choose Premium",
            // Action menus
            "More actions", "YouTube makes for you", "Drag to reorder",
            "Close Repeat", "Shuffle Menu", "Sign up", "re playlists",
            "Queue", "ylists", "Add to queue", "Save to playlist",
            "Share", "Report", "Not interested", "Don't recommend channel", "Next:",
            // Channel/account UI
            "Subscribe", "Subscribed", "Join", "Bell", "notifications",
            // Timestamp noise
            "minutes, ", "seconds", "Go to channel",
            // Feed noise
            "Music for you", "TikTok Lite", "Top podcasts", "Recommended",
            "Continue watching", "Up next", "Playing next",
            "Autoplay is", "Pause autoplay", "Mix -", "Topic",
            // Ad labels
            "Why this ad", "Stop seeing this ad", "Visit advertiser",
            "Ad ", "Promoted", "Sponsored content",
            // View count noise
            "K views", "M views", "B views",
            "months ago", "years ago", "days ago", "hours ago", "weeks ago",
            "See #", "videos ...more", "...more",
            // Comment UI
            "Add a comment", "@mention", "comment or @", "Reply", "replies",
            "Pinned comment", "View all comments", "Comments are turned off",
            "Top comments", "Newest first", "Sort comments",
            "likes", "like this", "liked by", "Liked by creator",
            "Show more replies", "Hide replies", "Load more comments",
            "Be the first to comment", "No comments yet",
            // Shorts UI
            "See more videos using this sound", "using this sound",
            "Original audio", "Original sound", "Collaboration channels",
            "View product", "Shop now", "Swipe up", "Add yours", "Remix this",
            // Music indicators
            "(Official", "- Topic", "♪", "♫", "🎵", "🎶",
            // YouTube Premium popups
            "Premium Lite", "Try Premium", "YouTube Premium",
            "you'll want to try", "Ad-free", "Get Premium",
            // Feature unavailable
            "Feature not available",
            "not available for this video",
        )

        private val SKIP_NODE_CLASSES = listOf(
            "android.widget.ImageButton", "android.widget.ImageView",
            "android.widget.ProgressBar", "android.widget.SeekBar",
            "android.widget.CheckBox", "android.widget.Switch",
            "android.widget.RadioButton",
        )

        private val CHANNEL_HANDLE_RE = Regex("^[A-Z][a-zA-Z0-9]{4,40}$")
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    // ── Priority Queue ────────────────────────────────────────────────────────
    @Volatile private var currentJob: Job = Job().also { it.cancel() }
    @Volatile private var currentPriority = 0
    @Volatile private var currentTarget   = ""

    private var lastSentTitle   = ""
    private var lastSentTimeMs  = 0L
    private var pendingTitle    = ""
    private var lastEventTimeMs = 0L

    // Guard dedup — skip re-checking same screen state
    @Volatile private var lastGuardText   = ""
    @Volatile private var lastGuardResult = false

    // Shorts spam lock — set immediately on first detection, cleared after dispatch
    @Volatile private var shortsPendingTitle = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Pattern 1: video title is always shown next to view count
    private val VIEWS_PATTERN = Pattern.compile(
        "([A-Z][^\\n]{10,150})\\s+[\\d.,]+[KMBkm]?\\s+views",
        Pattern.CASE_INSENSITIVE
    )

    // Pattern 2: title before @ChannelName during playback
    private val AT_CHANNEL_PATTERN = Pattern.compile(
        "([A-Z][^\\n@]{10,150})\\s{1,4}@[\\w]{2,50}(?:\\s|$)"
    )

    // Pattern 3: direct video ID from URL — most reliable
    private val URL_PATTERN = Pattern.compile("(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})")

    override fun onServiceConnected() {
        println("[CF_SERVICE] ✓ Connected — monitoring YouTube")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val now = System.currentTimeMillis()
        if (lastSentTitle.isNotEmpty() && (now - lastSentTimeMs) > TITLE_RESET_MS) {
            println("[CF_SERVICE] ↺ Reset title memory after timeout")
            lastSentTitle  = ""
            lastSentTimeMs = 0L
        }

        // ── Strategy 0: Direct video ID from event text ───────────────────────
        // Most reliable — YouTube fires window state change events with the URL
        // in the event text when navigating to a video.
        val eventText = event.text?.joinToString(" ") ?: ""
        val urlMatch  = URL_PATTERN.matcher(eventText)
        if (urlMatch.find()) {
            val videoId = urlMatch.group(1) ?: return
            enqueue(videoId, PRIORITY_PLAYING) { doHandleVideoId(videoId) }
            return
        }

        // ── Strategy 0b: Check event source node directly (NEW) ───────────────
        // event.source is the SPECIFIC view that changed, not the whole window.
        // Checking it first avoids picking up URLs from recommendation cards that
        // are present in the full tree but are NOT the currently playing video.
        val sourceNode = event.source
        if (sourceNode != null) {
            val sourceVideoId = extractVideoIdFromSourceNode(sourceNode)
            sourceNode.recycle()
            if (sourceVideoId != null) {
                enqueue(sourceVideoId, PRIORITY_PLAYING) { doHandleVideoId(sourceVideoId) }
                return
            }
        }

        val root    = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)

        // ── Guard: skip duplicate screen states ───────────────────────────────
        if (allText == lastGuardText && lastGuardResult) {
            root.recycle()
            return
        }
        val isAdOrComment = isAdPlaying(allText) || isCommentSectionVisible(allText)
        lastGuardText   = allText
        lastGuardResult = isAdOrComment
        if (isAdOrComment) {
            root.recycle()
            return
        }

        // ── Strategy 1: Video ID in tree — prefer playing area over feed ──────
        // extractPlayerVideoId() scans the tree but favors URLs that appear near
        // "views" text. The currently playing video title always sits above
        // "X views · Y ago" in the player area. Recommendation card URLs do not
        // have this proximity relationship in the concatenated string.
        val playerVideoId = extractPlayerVideoId(allText)
        root.recycle()

        if (playerVideoId != null) {
            enqueue(playerVideoId, PRIORITY_PLAYING) { doHandleVideoId(playerVideoId) }
            return
        }

        // ── Strategy 2: Shorts detection — NB-only (no YouTube search) ───────
        // Shorts fires TYPE_WINDOW_STATE_CHANGED on every swipe. The Shorts URL
        // (youtube.com/shorts/ID) does not appear in the tree — only the title does.
        //
        // FIX: Previously this called doDispatchTitle → classifyByTitle →
        //      YouTube search → WRONG VIDEO ID.
        // Now: doDispatchTitleFast → /classify_fast (NB on title, no download, no search).
        // The OIR label is determined from the actual title being displayed.
        val isShorts = allText.contains("Shorts") &&
                !allText.contains("views", ignoreCase = true)
        if (isShorts) {
            val shortsTitle = extractShortsTitle(allText)
            if (shortsTitle != null
                && isCleanTitle(shortsTitle)
                && shortsTitle != lastSentTitle
                && shortsTitle != shortsPendingTitle) {
                shortsPendingTitle = shortsTitle
                println("[CF_SERVICE] ✓ [SHORTS] $shortsTitle")
                enqueue(shortsTitle, PRIORITY_ACTIVE) { doDispatchTitleFast(shortsTitle) }
                return
            }
        }

        // ── Strategy 3: Title before view count (regular video playing) ───────
        // classifyByTitle() now includes title-similarity verification.
        // If the search returns the wrong video, it falls back to /classify_fast.
        val viewsMatch = VIEWS_PATTERN.matcher(allText)
        if (viewsMatch.find()) {
            val t = viewsMatch.group(1)?.trim() ?: return
            if (isCleanTitle(t)) {
                enqueue(t, PRIORITY_ACTIVE) { doDispatchTitle(t) }
                return
            }
        }

        // ── Strategy 4: Title before @channel ────────────────────────────────
        val atMatch = AT_CHANNEL_PATTERN.matcher(allText)
        if (atMatch.find()) {
            val t = atMatch.group(1)?.trim() ?: return
            if (isCleanTitle(t)) {
                enqueue(t, PRIORITY_ACTIVE) { doDispatchTitle(t) }
                return
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // URL EXTRACTION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks the event source node and its immediate children for a YouTube URL.
     *
     * The source node is the specific view that fired the accessibility event —
     * for navigation events this is the player/title area, NOT a recommendation card.
     * Limits recursion to 3 levels to avoid descending into unrelated subtrees.
     */
    private fun extractVideoIdFromSourceNode(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        try {
            val text = buildString {
                node.text?.let { append(it).append(" ") }
                node.contentDescription?.let { append(it) }
            }
            val m = URL_PATTERN.matcher(text)
            if (m.find()) return m.group(1)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = extractVideoIdFromSourceNode(child, depth + 1)
                child.recycle()
                if (result != null) return result
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * Extracts the video ID of the CURRENTLY PLAYING video from the full tree text.
     *
     * Strategy: Scan all URL matches in the concatenated tree text.
     * Prefer the match where "views" (or "ago") appears within 400 characters
     * BEFORE OR AFTER the match — this indicates the URL is co-located with
     * the player area, not buried in a recommendation card.
     *
     * If no view-correlated match is found, return null so title-based fallback
     * strategies can run rather than returning a potentially wrong recommendation ID.
     */
    private fun extractPlayerVideoId(allText: String): String? {
        val matcher = URL_PATTERN.matcher(allText)
        val viewsIndicators = listOf("views", " ago", "K views", "M views", "B views")

        while (matcher.find()) {
            val videoId   = matcher.group(1) ?: continue
            val matchStart = matcher.start()
            val matchEnd   = matcher.end()

            // Check a 400-char window around the URL match for view-count indicators
            val windowStart = maxOf(0, matchStart - 400)
            val windowEnd   = minOf(allText.length, matchEnd + 400)
            val window      = allText.substring(windowStart, windowEnd)

            val hasViewCount = viewsIndicators.any { window.contains(it, ignoreCase = true) }
            if (hasViewCount) {
                println("[CF_SERVICE] ✓ [TREE-VERIFIED] $videoId (view-count correlated)")
                return videoId
            }
        }

        // No view-correlated URL found — do NOT fall back to first match to avoid
        // returning recommendation card IDs. Let title strategies run instead.
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHORTS TITLE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun extractShortsTitle(allText: String): String? {
        val uiLabels = setOf(
            "shorts", "home", "explore", "subscriptions", "library",
            "like", "dislike", "comment", "share", "subscribe", "subscribed",
            "follow", "more", "pause", "play", "mute", "unmute",
            "youtube", "search", "remix", "add yours", "save",
            "video progress", "progress", "seek bar", "playback",
            "watch full video", "watch more", "watch now",
            "affiliate", "shopee", "lazada", "tiktok shop",
            "best seller", "shop now", "buy now", "order now",
            "add to cart", "check link", "link in bio",
            "view comments", "comments", "search",
        )
        val lines = allText.split("\n")
            .map { it.trim() }
            .filter { it.length >= 8 }
            .filter { line ->
                val lower = line.lowercase()
                !uiLabels.any { lower == it } &&
                        !line.matches(Regex("[\\d.,]+[KMBkm]?")) &&
                        !line.startsWith("#") &&
                        !line.matches(Regex("\\d+:\\d+.*")) &&
                        line.trim().split(" ").filter { it.isNotEmpty() }.size >= 3
            }
        return lines.firstOrNull { isCleanTitle(it) }
    }

    private fun searchNodeForShortsId(node: AccessibilityNodeInfo, pattern: Regex): String? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        for (str in listOf(text, desc)) {
            val match = pattern.find(str)
            if (match != null) return match.groupValues[1]
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchNodeForShortsId(child, pattern)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIORITY QUEUE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun enqueue(target: String, priority: Int, block: suspend () -> Unit) {
        synchronized(this) {
            when {
                target == currentTarget && currentJob.isActive -> Unit

                priority > currentPriority && currentJob.isActive -> {
                    println("[QUEUE] ⬆ P$priority cancels: ${currentTarget.take(35)}")
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
        println("[QUEUE] ▶ [P$priority]: ${target.take(40)}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WORKERS
    // ═══════════════════════════════════════════════════════════════════════════

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

    /**
     * Worker for title-based detection (Strategies 3 & 4 — regular video).
     * Calls classifyByTitle() which includes similarity verification:
     * if the YouTube search returns a different video, falls back to NB-only.
     */
    private suspend fun doDispatchTitle(title: String) {
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
        println("[CF_SERVICE] ✓ [ACTIVE] $title")

        broadcastResult(title, "Analyzing", 0f, false)
        if (!currentJob.isActive) return

        classifyByTitle(title)   // includes similarity verification
    }

    /**
     * Worker for Shorts title-based detection (Strategy 2).
     *
     * Uses /classify_fast (NB-only on title text) instead of /classify_by_title.
     * Reason: Shorts URLs are never exposed in the accessibility tree, so there is
     * no reliable way to get the correct video ID. Searching YouTube for a Shorts
     * title consistently returns mismatched videos because Shorts content is indexed
     * differently from regular search results.
     *
     * NB classification on the title alone is sufficient and accurate for the
     * overstimulation detection goal — the title reflects the content type.
     */
    private suspend fun doDispatchTitleFast(title: String) {
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
        println("[CF_SERVICE] ✓ [SHORTS-FAST] $title")

        broadcastResult(title, "Analyzing", 0f, false)
        if (!currentJob.isActive) return

        classifyFastByTitle(title)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUARDS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun isAdPlaying(allText: String): Boolean =
        listOf("Skip Ads", "Skip ad", "Ad ·", "Why this ad",
            "Stop seeing this ad", "Visit advertiser", "Skip in")
            .any { allText.contains(it, ignoreCase = true) }

    private fun isCommentSectionVisible(allText: String): Boolean =
        listOf("Add a comment", "Top comments", "Newest first",
            "Sort comments", "Be the first to comment",
            "Pinned comment", "Show more replies", "Load more comments")
            .any { allText.contains(it, ignoreCase = true) }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun collectAllNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            val className    = node.className?.toString() ?: ""
            val isSkipped    = SKIP_NODE_CLASSES.any { className.endsWith(it.substringAfterLast('.')) }
            val textLen      = node.text?.length ?: 0
            val isButtonLike = node.isClickable && textLen in 1..25 && node.childCount == 0
            if (!isSkipped && !isButtonLike) {
                node.text?.let               { sb.append(it).append("\n") }
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

    private fun isCleanTitle(text: String): Boolean {
        if (text.length < 8 || text.length > 200) return false
        if (SKIP_TITLES.any { text.contains(it, ignoreCase = true) }) return false
        val lowerText = text.lowercase()
        if (lowerText.contains("affiliate") ||
            lowerText.contains("shopee") ||
            lowerText.contains("lazada") ||
            lowerText.contains("best seller") ||
            lowerText.contains("tap to watch") ||
            lowerText.contains("watch live") ||
            lowerText.contains("tap to watch live") ||
            lowerText.contains("new content available") ||
            lowerText.contains("buy now") ||
            lowerText.contains("order now") ||
            lowerText.contains("link in bio") ||
            lowerText.contains("say goodbye to") ||
            lowerText.contains("one pair for") ||
            lowerText.contains("years younger") ||
            lowerText.contains("skin look") ||
            lowerText.contains("suitable for all") ||
            lowerText.contains("all skin types") ||
            lowerText.startsWith("search ") ||
            (lowerText.startsWith("view ") && lowerText.contains("comment")) ||
            lowerText.contains("palawan") ||
            lowerText.contains("sangla") ||
            lowerText.startsWith("helps ") ||
            lowerText.startsWith("get ") && text.length < 60 ||
            lowerText.startsWith("try ") && text.length < 50) {
            return false
        }
        if (Regex("[A-Za-z]{1,4}-?\\d{4,}[A-Za-z0-9]*").containsMatchIn(text)) return false
        if (Regex("^[A-Z][a-zA-Z]+ [·•] [A-Z][a-zA-Z]+$").containsMatchIn(text)) return false
        if (CHANNEL_HANDLE_RE.matches(text.trim())) return false
        if (text.trim().split(Regex("\\s+")).size < 2) return false
        return true
    }

    /**
     * Jaccard word-overlap similarity between two title strings.
     *
     * Strips words shorter than 3 characters (articles, conjunctions) to focus
     * on meaningful content words. Returns a value in [0.0, 1.0].
     *
     * Used by classifyByTitle() to verify the YouTube search returned the same
     * video that the accessibility service detected.
     *
     * Examples:
     *   "You Grow Girl spring motivation" vs "Never Give Up on Your Dreams" → ~0.0
     *   "Cocomelon ABC Song for kids"    vs "Cocomelon ABC Song"            → ~0.75
     */
    private fun jaccardSimilarity(a: String, b: String): Double {
        val aWords = a.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val bWords = b.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (aWords.isEmpty() && bWords.isEmpty()) return 1.0
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0
        val intersection = aWords.intersect(bWords).size.toDouble()
        val union        = aWords.union(bWords).size.toDouble()
        return intersection / union
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Classify a regular video by searching YouTube for its title.
     *
     * After getting the result from /classify_by_title, the returned video_title
     * is compared against the detected title using Jaccard similarity.
     * If similarity < TITLE_SIMILARITY_THRESHOLD (0.35), the search returned
     * a different video — classifyFastByTitle() is called as the fallback.
     *
     * This prevents the "wrong video classified" bug where YouTube search returns
     * a different video as the top result for a given title string.
     */
    private fun classifyByTitle(detectedTitle: String) {
        try {
            val body    = JSONObject().apply { put("title", detectedTitle) }
            val request = Request.Builder()
                .url("$BASE_URL/classify_by_title")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: return)

            // ── Similarity verification ───────────────────────────────────
            val returnedTitle = json.optString("video_title", "").trim()
            if (returnedTitle.isNotEmpty()) {
                val similarity = jaccardSimilarity(detectedTitle, returnedTitle)
                println(
                    "[CF_SERVICE] Title similarity: " +
                    "'${detectedTitle.take(40)}' vs '${returnedTitle.take(40)}' = " +
                    "%.2f".format(similarity)
                )
                if (similarity < TITLE_SIMILARITY_THRESHOLD) {
                    // YouTube search returned a different video.
                    // Fall back to NB-only classification on the original title.
                    println("[CF_SERVICE] ⚠ Mismatch — falling back to classify_fast for: ${detectedTitle.take(50)}")
                    classifyFastByTitle(detectedTitle)
                    return
                }
            }

            handleClassificationResult(json)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_by_title: ${e.message}")
            // Network failure — try NB-only as last resort
            classifyFastByTitle(detectedTitle)
        }
    }

    /**
     * NB-only classification using just the video title.
     *
     * Calls /classify_fast which runs the Naïve Bayes model on the title text.
     * No video download, no YouTube search, no possibility of wrong video ID.
     *
     * Used for:
     *   - All Shorts (Strategy 2): Shorts URL is never in the accessibility tree
     *   - Title-mismatch fallback: when classify_by_title returns a different video
     *   - Network error fallback: when classify_by_title fails
     *
     * The video_id broadcast is the title truncated to 40 chars. This is a
     * display identifier only — the overlay's exclude parameter and the ViewModel
     * state both handle non-ID strings gracefully.
     */
    private fun classifyFastByTitle(title: String) {
        try {
            val body    = JSONObject().apply { put("title", title) }
            val request = Request.Builder()
                .url("$BASE_URL/classify_fast")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: return)

            // /classify_fast returns: score_nb, oir_label (or label), status
            val label = json.optString("oir_label",
                        json.optString("label", "Neutral"))
            val score = json.optDouble("score_nb", 0.5).toFloat()

            println("[CF_SERVICE] ✓ [FAST] '${title.take(50)}' → $label ($score)")
            // Use title as the display identifier — no real video ID available
            broadcastResult(title, label, score, false)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_fast: ${e.message}")
        }
    }

    private fun classifyByUrl(videoId: String, videoUrl: String, thumbUrl: String) {
        try {
            val body = JSONObject().apply {
                put("video_url",     videoUrl)
                put("thumbnail_url", thumbUrl)
            }
            val request = Request.Builder()
                .url("$BASE_URL/classify_full")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: return)
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
        broadcastResult(videoId, label, score.toFloat(), cached)
    }

    private fun broadcastResult(videoId: String, label: String, score: Float, cached: Boolean) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",    videoId)
            putExtra("oir_label",   label)
            putExtra("score_final", score)
            putExtra("cached",      cached)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onInterrupt() {
        println("[CF_SERVICE] Interrupted")
        currentJob.cancel()
        lastSentTitle      = ""
        lastSentTimeMs     = 0L
        pendingTitle       = ""
        lastEventTimeMs    = 0L
        currentPriority    = 0
        currentTarget      = ""
        shortsPendingTitle = ""
    }
}
