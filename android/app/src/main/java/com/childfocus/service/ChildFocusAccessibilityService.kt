package com.childfocus.service
import com.childfocus.R

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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.net.Uri
import android.os.Handler
import android.os.Looper


/**
 * ChildFocusAccessibilityService
 *
 * VIDEO ID EXTRACTION — FIX LOG
 * ─────────────────────────────
 * Fix 1 — Strategy 0b: Check event.source before scanning full tree.
 * Fix 2 — Strategy 1: extractPlayerVideoId() prefers view-count-correlated URLs.
 * Fix 3 — Strategy 2 (Shorts): doDispatchTitleFast → /classify_fast (NB-only).
 * Fix 4 — Strategies 3/4: Jaccard similarity verification on /classify_by_title.
 * Fix 5 — normalizeTitle() dedup: strips " | channel" and " - subtitle" suffixes
 *          so the same video detected at different title lengths maps to the same
 *          lastSentTitle entry and is classified only once.
 * Fix 6 — Early lock: lastSentTitle is now set BEFORE delay() in both workers.
 *          Root cause of triple-classification: YouTube fires the full player title
 *          ("Title - Subtitle | Channel") during the 1500ms debounce window. At
 *          that point lastSentTitle was still empty, so the normalize check passed
 *          and a second job was started. Setting lastSentTitle = normalizeTitle(title)
 *          before delay() closes that race window.
 * Fix 7 — Overlay Bug 1: item.getString("label") → optString("label", "Educational")
 *          /safe_suggestions never returns a "label" field; getString threw JSONException
 *          silently, causing suggestion cards to never appear.
 * Fix 8 — Overlay Bug 2: classifyFastByTitle() now calls showBlockOverlay() so
 *          overstimulating Shorts also trigger the block overlay (previously skipped
 *          because Shorts bypass handleClassificationResult).
 * Fix 9 — Overlay Bug 3: removeOverlay() wrapped in try-catch to prevent
 *          IllegalArgumentException when view is already detached.
 * Fix 10 — Overlay Bug 4: lastBlockedVideoId guard prevents re-showing overlay for
 *           the same video in the same session (e.g., screen-on resume).
 * Fix 11 — Shorts ID: extractShortsVideoId() with title-proximity cross-verification.
 *           Extracts shorts/ URL only if it appears within SHORTS_ID_PROXIMITY_CHARS
 *           of the already-extracted playing title. Rejects thumbnails / feed IDs
 *           that are spatially far from the title in the accessibility tree dump.
 *           Falls back gracefully to NB-only (doDispatchTitleFast) when no verified
 *           ID is found — same behavior as before, no regression.
 */
class ChildFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val FLASK_HOST = "192.168.1.21"
        private const val FLASK_PORT = 5000
        private const val BASE_URL = "http://$FLASK_HOST:$FLASK_PORT"

        private const val TITLE_RESET_MS = 5 * 60 * 1000L
        private const val DEBOUNCE_MS = 1500L

        private const val PRIORITY_PLAYING = 3
        private const val PRIORITY_ACTIVE = 2

        private const val TITLE_SIMILARITY_THRESHOLD = 0.35

        /**
         * Max character distance between the playing Short's title and a shorts/ URL
         * in the flattened accessibility tree for the ID to be considered "verified".
         *
         * YouTube's Shorts player renders the title and the video URL close together
         * in the view hierarchy. Thumbnail cards in the surrounding feed are typically
         * 500–1000 chars away in the flattened dump. 300 chars is a safe cut-off:
         * tight enough to reject feed thumbnails, loose enough to survive minor tree
         * variations across YouTube versions.
         */
        private const val SHORTS_ID_PROXIMITY_CHARS = 300

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
            // Live stream overlays on Shorts thumbnails
            "Tap to watch live", "Tap to watch", "Watch live",
            "New content available",
            // Shorts shelf labels — "play Short" (without s) bypasses the "Shorts" check
            "play Short", "play short",
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

    @Volatile
    private var currentJob: Job = Job().also { it.cancel() }
    @Volatile
    private var currentPriority = 0
    @Volatile
    private var currentTarget = ""

    // Stores the NORMALIZED title of the last video that entered the pipeline.
    // Set BEFORE the debounce delay (early lock) so duplicates during the 1500ms
    // window are blocked. Raw titles are never stored here — always normalized.
    private var lastSentTitle = ""
    private var lastSentTimeMs = 0L
    private var pendingTitle = ""
    private var lastEventTimeMs = 0L

    @Volatile
    private var lastGuardText = ""
    @Volatile
    private var lastGuardResult = false

    @Volatile
    private var shortsPendingTitle = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Overlay state ─────────────────────────────────────────────────────────
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    // Prevents re-showing overlay for the same video in the same session
    private var lastBlockedVideoId = ""

    private val VIEWS_PATTERN = Pattern.compile(
        "([A-Z][^\\n]{10,150})\\s+[\\d.,]+[KMBkm]?\\s+views",
        Pattern.CASE_INSENSITIVE
    )

    private val AT_CHANNEL_PATTERN = Pattern.compile(
        "([A-Z][^\\n@]{10,150})\\s{1,4}@[\\w]{2,50}(?:\\s|$)"
    )

    private val URL_PATTERN = Pattern.compile("(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})")

    // Dedicated Shorts URL pattern — only matches shorts/ prefix to avoid
    // matching long-form v= or youtu.be/ URLs during Shorts ID extraction
    private val SHORTS_URL_PATTERN = Pattern.compile("shorts/([a-zA-Z0-9_-]{11})")

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        println("[CF_SERVICE] ✓ Connected — monitoring YouTube")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val now = System.currentTimeMillis()
        if (lastSentTitle.isNotEmpty() && (now - lastSentTimeMs) > TITLE_RESET_MS) {
            println("[CF_SERVICE] ↺ Reset title memory after timeout")
            lastSentTitle = ""
            lastSentTimeMs = 0L
        }

        // ── Strategy 0: Direct video ID from event text ───────────────────────
        val eventText = event.text?.joinToString(" ") ?: ""
        val urlMatch = URL_PATTERN.matcher(eventText)
        if (urlMatch.find()) {
            val videoId = urlMatch.group(1) ?: return
            enqueue(videoId, PRIORITY_PLAYING) { doHandleVideoId(videoId) }
            return
        }

        // ── Strategy 0b: Check event source node ──────────────────────────────
        val sourceNode = event.source
        if (sourceNode != null) {
            val sourceVideoId = extractVideoIdFromSourceNode(sourceNode)
            sourceNode.recycle()
            if (sourceVideoId != null) {
                enqueue(sourceVideoId, PRIORITY_PLAYING) { doHandleVideoId(sourceVideoId) }
                return
            }
        }

        val root = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)

        // ── Guard: skip duplicate screen states ───────────────────────────────
        if (allText == lastGuardText && lastGuardResult) {
            root.recycle()
            return
        }
        val isAdOrComment = isAdPlaying(allText) || isCommentSectionVisible(allText)
        lastGuardText = allText
        lastGuardResult = isAdOrComment
        if (isAdOrComment) {
            root.recycle()
            return
        }

        // ── Strategy 1: Video ID in tree (long-form, view-count correlated) ───
        val playerVideoId = extractPlayerVideoId(allText)
        root.recycle()

        if (playerVideoId != null) {
            enqueue(playerVideoId, PRIORITY_PLAYING) { doHandleVideoId(playerVideoId) }
            return
        }

        // ── Strategy 2: Shorts ────────────────────────────────────────────────
        // Shorts don't have view counts so Strategy 1 always misses them.
        // We first extract the playing title (existing logic), then attempt to
        // find a co-located shorts/ URL as a cross-verification. If a verified
        // ID is found we use the full heuristic+NB pipeline; otherwise we fall
        // back to NB-only via title — same behavior as before.
        val isShorts = allText.contains("Shorts") &&
                !allText.contains("views", ignoreCase = true)
        if (isShorts) {
            val shortsTitle = extractShortsTitle(allText)
            if (shortsTitle != null
                && isCleanTitle(shortsTitle)
                && normalizeTitle(shortsTitle) != lastSentTitle
                && normalizeTitle(shortsTitle) != normalizeTitle(shortsPendingTitle)
            ) {
                shortsPendingTitle = shortsTitle

                // ── Strategy 2a: Try to get a verified Shorts video ID ─────────
                // extractShortsVideoId() requires the shorts/ URL to appear within
                // SHORTS_ID_PROXIMITY_CHARS of the title in the tree dump.
                // This prevents picking up thumbnail IDs from the surrounding feed.
                val shortsVideoId = extractShortsVideoId(allText, shortsTitle)
                if (shortsVideoId != null) {
                    println("[CF_SERVICE] ✓ [SHORTS-ID-VERIFIED] $shortsVideoId near '$shortsTitle'")
                    enqueue(shortsVideoId, PRIORITY_PLAYING) { doHandleVideoId(shortsVideoId) }
                    return
                }

                // ── Strategy 2b: Fallback — NB-only via title ─────────────────
                println("[CF_SERVICE] ✓ [SHORTS] $shortsTitle (no verified ID, NB-only)")
                enqueue(shortsTitle, PRIORITY_ACTIVE) { doDispatchTitleFast(shortsTitle) }
                return
            }
        }

        // ── Strategy 3: Title before view count ───────────────────────────────
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
    // URL EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

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
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Strategy 1: Long-form video ID extraction.
     *
     * Requires the URL to appear within 400 chars of a view-count indicator
     * ("views", "K views", etc.) in the accessibility tree dump. This prevents
     * picking up recommendation/feed URLs that happen to appear in the same tree.
     */
    private fun extractPlayerVideoId(allText: String): String? {
        val matcher = URL_PATTERN.matcher(allText)
        val viewsIndicators = listOf("views", " ago", "K views", "M views", "B views")
        while (matcher.find()) {
            val videoId = matcher.group(1) ?: continue
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            val windowStart = maxOf(0, matchStart - 400)
            val windowEnd = minOf(allText.length, matchEnd + 400)
            val window = allText.substring(windowStart, windowEnd)
            if (viewsIndicators.any { window.contains(it, ignoreCase = true) }) {
                println("[CF_SERVICE] ✓ [TREE-VERIFIED] $videoId (view-count correlated)")
                return videoId
            }
        }
        return null
    }

    /**
     * Strategy 2a: Shorts video ID extraction with title-proximity verification.
     *
     * PROBLEM: The accessibility tree for Shorts contains many shorts/ URLs —
     * the currently playing Short AND every thumbnail in the vertical feed below.
     * Without verification, the first URL found could belong to any of those.
     *
     * SOLUTION: We only accept a shorts/ URL if it appears within
     * SHORTS_ID_PROXIMITY_CHARS characters of [knownTitle] in the flattened tree.
     *
     * WHY THIS WORKS:
     * - YouTube's Shorts player renders the title and the share/like buttons (which
     *   carry the video URL) as adjacent nodes in the same view group.
     * - Feed thumbnails below the player are in a separate RecyclerView subtree,
     *   typically 500–1000 chars away in the flattened dump.
     * - 300 chars is tight enough to reject those thumbnails, yet loose enough to
     *   survive minor variations across YouTube versions and Android API levels.
     *
     * FALLBACK: Returns null if no co-located ID is found. Callers must fall back
     * to NB-only classification (Strategy 2b) — no regression from previous behavior.
     */
    private fun extractShortsVideoId(allText: String, knownTitle: String): String? {
        // Find the position of the known title in the tree dump first.
        // Use lowercase for a case-insensitive positional search — we just need
        // the character offset, not the text itself.
        val titlePos = allText.indexOf(knownTitle, ignoreCase = true)
        if (titlePos == -1) {
            // Title not found in dump (rare — can happen with Unicode edge cases).
            // Fall back gracefully rather than scanning blindly.
            println("[CF_SERVICE] ⚠ [SHORTS-ID] Title not found in allText, skipping ID extraction")
            return null
        }

        val matcher = SHORTS_URL_PATTERN.matcher(allText)
        while (matcher.find()) {
            val videoId = matcher.group(1) ?: continue
            val urlPos = matcher.start()
            val distance = Math.abs(urlPos - titlePos)

            println(
                "[CF_SERVICE] [SHORTS-ID] Candidate $videoId at pos $urlPos, " +
                        "title at $titlePos, distance=$distance"
            )

            if (distance <= SHORTS_ID_PROXIMITY_CHARS) {
                // This URL is co-located with the playing title — high confidence
                // it belongs to the currently playing Short.
                return videoId
            }
            // Otherwise: URL is too far away — likely a feed thumbnail. Continue scanning.
        }

        // No co-located ID found — caller will fall back to NB-only.
        println("[CF_SERVICE] ⚠ [SHORTS-ID] No verified ID within ${SHORTS_ID_PROXIMITY_CHARS}c of title")
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
        currentTarget = target
        currentPriority = priority
        currentJob = scope.launch {
            try {
                block()
            } finally {
                synchronized(this@ChildFocusAccessibilityService) {
                    if (currentTarget == target) {
                        currentPriority = 0
                        currentTarget = ""
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
        lastSentTitle = videoId
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
     * Worker for regular video title detection (Strategies 3 & 4).
     *
     * EARLY LOCK: lastSentTitle = normalizeTitle(title) is set BEFORE delay().
     * This prevents the full player title ("Title - Subtitle | Channel") from
     * firing a second classification during the 1500ms debounce window.
     */
    private suspend fun doDispatchTitle(title: String) {
        val normalized = normalizeTitle(title)
        if (title.length < 8 || normalized == lastSentTitle) return

        // Early lock — claim before debounce
        lastSentTitle = normalized
        lastSentTimeMs = System.currentTimeMillis()

        pendingTitle = title
        lastEventTimeMs = System.currentTimeMillis()

        delay(DEBOUNCE_MS)

        if (!currentJob.isActive) return
        if (pendingTitle != title) return
        if ((System.currentTimeMillis() - lastEventTimeMs) < DEBOUNCE_MS) return

        shortsPendingTitle = ""
        println("[CF_SERVICE] ✓ [ACTIVE] $title")

        broadcastResult(title, "Analyzing", 0f, false)
        if (!currentJob.isActive) return

        classifyByTitle(title)
    }

    /**
     * Worker for Shorts title detection (Strategy 2b — NB-only fallback).
     *
     * Only reached when extractShortsVideoId() finds no co-located URL.
     * EARLY LOCK: Same reason as doDispatchTitle(). When a Short is detected in
     * the search feed and the user clicks it within 1500ms, YouTube fires the full
     * player title "Title - Subtitle | Channel" before the debounce expires. The
     * early lock prevents that event from starting a second classification.
     */
    private suspend fun doDispatchTitleFast(title: String) {
        val normalized = normalizeTitle(title)
        if (title.length < 8 || normalized == lastSentTitle) return

        // Early lock — claim before debounce
        lastSentTitle = normalized
        lastSentTimeMs = System.currentTimeMillis()

        pendingTitle = title
        lastEventTimeMs = System.currentTimeMillis()

        delay(DEBOUNCE_MS)

        if (!currentJob.isActive) return
        if (pendingTitle != title) return
        if ((System.currentTimeMillis() - lastEventTimeMs) < DEBOUNCE_MS) return

        shortsPendingTitle = ""
        println("[CF_SERVICE] ✓ [SHORTS-FAST] $title")

        broadcastResult(title, "Analyzing", 0f, false)
        if (!currentJob.isActive) return

        classifyFastByTitle(title)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUARDS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun isAdPlaying(allText: String): Boolean =
        listOf(
            "Skip Ads", "Skip ad", "Ad ·", "Why this ad",
            "Stop seeing this ad", "Visit advertiser", "Skip in"
        )
            .any { allText.contains(it, ignoreCase = true) }

    private fun isCommentSectionVisible(allText: String): Boolean =
        listOf(
            "Add a comment", "Top comments", "Newest first",
            "Sort comments", "Be the first to comment",
            "Pinned comment", "Show more replies", "Load more comments"
        )
            .any { allText.contains(it, ignoreCase = true) }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun collectAllNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            val className = node.className?.toString() ?: ""
            val isSkipped = SKIP_NODE_CLASSES.any { className.endsWith(it.substringAfterLast('.')) }
            val textLen = node.text?.length ?: 0
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
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    /**
     * Normalizes a title for dedup comparison ONLY — never sent to Flask.
     *
     * Strips the " | Channel" and " - Subtitle" suffixes YouTube appends in the
     * full player title, then lowercases. Ensures the same video detected at
     * different points (feed card, player loading, full title) maps to one entry.
     *
     * Examples:
     *   "You Grow Girl! - Spring Motivation with Sixteen | Numberblocks" → "you grow girl!"
     *   "you grow girl! - spring motivation with sixteen"                → "you grow girl!"
     *   "WINE 11 - play Short"                                           → "wine 11"
     */
    private fun normalizeTitle(raw: String): String =
        raw.substringBefore(" | ")
            .substringBefore(" - ")
            .lowercase()
            .trim()

    private fun isCleanTitle(text: String): Boolean {
        // ── NEW FILTERS (must be FIRST) ───────────────────────────────────────
        // Block subtitle fragments: "throughout your app", "so you can rapidly build"
        if (text.isNotEmpty() && text[0].isLowerCase()) return false

        // Block caption sentences: "Firebase is here to help.", "AI is changing the world."
        if (text.trimEnd().endsWith(".")) return false

        // ── EXISTING FILTERS (unchanged) ──────────────────────────────────────
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
            lowerText.startsWith("try ") && text.length < 50
        ) {
            return false
        }

        if (Regex("[A-Za-z]{1,4}-?\\d{4,}[A-Za-z0-9]*").containsMatchIn(text)) return false
        if (Regex("^[A-Z][a-zA-Z]+ [·•] [A-Z][a-zA-Z]+$").containsMatchIn(text)) return false
        if (CHANNEL_HANDLE_RE.matches(text.trim())) return false
        if (text.trim().split(Regex("\\s+")).size < 2) return false

        return true
    }


    private fun jaccardSimilarity(a: String, b: String): Double {
        val aWords = a.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val bWords = b.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (aWords.isEmpty() && bWords.isEmpty()) return 1.0
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0
        val intersection = aWords.intersect(bWords).size.toDouble()
        val union = aWords.union(bWords).size.toDouble()
        return intersection / union
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════════════════════

    private fun classifyByTitle(detectedTitle: String) {
        try {
            val body = JSONObject().apply { put("title", detectedTitle) }
            val request = Request.Builder()
                .url("$BASE_URL/classify_by_title")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return)

            val returnedTitle = json.optString("video_title", "").trim()
            if (returnedTitle.isNotEmpty()) {
                val similarity = jaccardSimilarity(detectedTitle, returnedTitle)
                println(
                    "[CF_SERVICE] Title similarity: " +
                            "'${detectedTitle.take(40)}' vs '${returnedTitle.take(40)}' = " +
                            "%.2f".format(similarity)
                )
                if (similarity < TITLE_SIMILARITY_THRESHOLD) {
                    println(
                        "[CF_SERVICE] ⚠ Mismatch — falling back to classify_fast for: ${
                            detectedTitle.take(50)
                        }"
                    )
                    classifyFastByTitle(detectedTitle)
                    return
                }
            }
            handleClassificationResult(json)
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_by_title: ${e.message}")
            classifyFastByTitle(detectedTitle)
        }
    }

    private fun classifyFastByTitle(title: String) {
        try {
            val body = JSONObject().apply { put("title", title) }
            val request = Request.Builder()
                .url("$BASE_URL/classify_fast")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return)

            val label = json.optString("oir_label", json.optString("label", "Neutral"))
            val score = json.optDouble("score_nb", 0.5).toFloat()

            println("[CF_SERVICE] ✓ [FAST] '${title.take(50)}' → $label ($score)")
            broadcastResult(title, label, score, false)

            // Overlay for overstimulating Shorts (they bypass handleClassificationResult)
            if (label.equals("Overstimulating", ignoreCase = true) && score >= 0.75) {
                mainHandler.post { showBlockOverlay(title, score) }
            }

        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_fast: ${e.message}")
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
        val label = json.optString("oir_label", "Neutral")
        val score = json.optDouble("score_final", 0.5)
        val cached = json.optBoolean("cached", false)
        val videoId = json.optString("video_id", "unknown")

        println("[CF_SERVICE] $videoId → $label ($score) cached=$cached")
        broadcastResult(videoId, label, score.toFloat(), cached)

        if (label.equals("Overstimulating", ignoreCase = true) && score >= 0.75) {
            mainHandler.post {
                showBlockOverlay(videoId, score.toFloat())
            }
        }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // OVERLAY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showBlockOverlay(videoId: String, score: Float) {
        // Don't stack overlays
        if (overlayView != null) return
        // Don't re-show for the same video in the same session
        if (videoId == lastBlockedVideoId) return

        // Permission check — open settings if missing
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_blocked, null, false)

        // Show OIR score for transparency
        overlayView?.findViewById<TextView>(R.id.tvScore)?.text =
            "OIR Score: ${"%.2f".format(score)} (threshold: 0.75)"

        // Dismiss button
        overlayView?.findViewById<Button>(R.id.btnClose)?.setOnClickListener {
            removeOverlay()
        }

        lastBlockedVideoId = videoId
        windowManager.addView(overlayView, params)

        // Fetch safe suggestions in background
        fetchAndShowSuggestions(videoId)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                println("[CF_SERVICE] ⚠ removeOverlay: view already detached")
            } finally {
                overlayView = null
            }
        }
    }

    private fun fetchAndShowSuggestions(excludeVideoId: String) {
        scope.launch {
            try {
                val url = "$BASE_URL/safe_suggestions?limit=3&exclude=$excludeVideoId"
                val request = Request.Builder().url(url).get().build()
                val response = http.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: return@launch)

                val suggestionsArray = json.optJSONArray("suggestions") ?: return@launch
                val suggestions = mutableListOf<VideoSuggestion>()

                for (i in 0 until suggestionsArray.length()) {
                    val item = suggestionsArray.getJSONObject(i)
                    suggestions.add(
                        VideoSuggestion(
                            videoId    = item.getString("video_id"),
                            label      = item.optString("label", "Educational"),  // optString: field absent in API
                            finalScore = item.getDouble("final_score").toFloat(),
                            title      = item.optString("video_title", "Educational Video")
                        )
                    )
                }

                mainHandler.post {
                    displaySuggestions(suggestions)
                }
            } catch (e: Exception) {
                println("[CF_SERVICE] ✗ /safe_suggestions fetch: ${e.message}")
                mainHandler.post {
                    overlayView?.findViewById<ProgressBar>(R.id.pbSuggestions)?.visibility =
                        View.GONE
                }
            }
        }
    }

    private fun displaySuggestions(suggestions: List<VideoSuggestion>) {
        val container = overlayView?.findViewById<LinearLayout>(R.id.llSuggestions) ?: return
        overlayView?.findViewById<ProgressBar>(R.id.pbSuggestions)?.visibility = View.GONE

        suggestions.forEach { video ->
            val card = TextView(this).apply {
                text = "▶  ${video.title.take(30)}"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(16, 12, 16, 12)
                setBackgroundColor(android.graphics.Color.parseColor("#3388FF88"))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).also { it.marginEnd = 8 }

                setOnClickListener {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=${video.videoId}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    removeOverlay()
                }
            }
            container.addView(card)
        }
    }

    // Data class for suggestions response
    private data class VideoSuggestion(
        val videoId: String,
        val label: String,
        val finalScore: Float,
        val title: String
    )

    override fun onInterrupt() {
        println("[CF_SERVICE] Interrupted")
        mainHandler.post { removeOverlay() }
        currentJob.cancel()
        lastSentTitle = ""
        lastSentTimeMs = 0L
        pendingTitle = ""
        lastBlockedVideoId = ""
        lastEventTimeMs = 0L
        currentPriority = 0
        currentTarget = ""
        shortsPendingTitle = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post { removeOverlay() }
    }

}
