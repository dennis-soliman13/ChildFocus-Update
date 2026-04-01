package com.childfocus.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SafetyViewModel — v6
 *
 * Owns all UI-visible state for ChildFocus:
 *   • safetyModeOn           — whether the accessibility service guard is active
 *   • isWaitingForService    — pulsing "waiting…" state while user is in Settings
 *   • classifyState          — current classification result for the status card
 *
 * ClassifyState sealed hierarchy matches every broadcast the service emits:
 *   Idle        — no video detected yet (or service just cleared)
 *   Analyzing   — classification in flight, shows video identifier
 *   Allowed     — video passed; shows label + score + cache flag
 *   Blocked     — overstimulating content detected; shows score
 *   Error       — backend unreachable or extraction failure
 */
class SafetyViewModel : ViewModel() {

    // ── Safety mode on/off ────────────────────────────────────────────────────

    private val _safetyModeOn = MutableStateFlow(false)
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn.asStateFlow()

    // ── Waiting-for-service state (Proton VPN-style) ──────────────────────────

    private val _isWaitingForService = MutableStateFlow(false)
    val isWaitingForService: StateFlow<Boolean> = _isWaitingForService.asStateFlow()

    fun setWaitingForService(waiting: Boolean) {
        _isWaitingForService.value = waiting
    }

    /** Called by MainActivity.onResume() when accessibility service is confirmed active. */
    fun onServiceConfirmed() {
        _isWaitingForService.value = false
        _safetyModeOn.value        = true
    }

    /** Called when the user taps "Turn Off Safety Mode". */
    fun turnOffSafetyMode() {
        _safetyModeOn.value        = false
        _isWaitingForService.value = false
        _classifyState.value       = ClassifyState.Idle
    }

    // ── Classification result state ───────────────────────────────────────────

    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState.asStateFlow()

    fun setAnalyzing(videoId: String) {
        _classifyState.value = ClassifyState.Analyzing(videoId)
    }

    fun setAllowed(label: String, score: Float, cached: Boolean) {
        _classifyState.value = ClassifyState.Allowed(label, score, cached)
    }

    fun setBlocked(videoId: String, score: Float) {
        _classifyState.value = ClassifyState.Blocked(videoId, score)
    }

    fun setError(videoId: String) {
        _classifyState.value = ClassifyState.Error(videoId)
    }

    fun dismissBlock() {
        _classifyState.value = ClassifyState.Idle
    }

    // ── ClassifyState ─────────────────────────────────────────────────────────

    sealed class ClassifyState {

        /** No video being monitored. */
        object Idle : ClassifyState()

        /**
         * Tier 1 / Tier 3 in flight.
         * @param videoId Either the 11-char video ID or the video title excerpt.
         */
        data class Analyzing(val videoId: String) : ClassifyState()

        /**
         * Classification completed — video is safe or neutral.
         * @param label   OIR label string ("Educational" | "Neutral")
         * @param score   score_final [0, 1]
         * @param cached  True if result came from DB / in-memory cache (Tier 0)
         */
        data class Allowed(
            val label:  String,
            val score:  Float,
            val cached: Boolean,
        ) : ClassifyState()

        /**
         * Video classified as Overstimulating — playback has been halted.
         * @param videoId Either video ID or title excerpt shown in status card.
         * @param score   Final fused score that triggered the block.
         */
        data class Blocked(
            val videoId: String,
            val score:   Float,
        ) : ClassifyState()

        /**
         * Extraction or backend error.
         * @param videoId Whatever identifier was available when the error occurred.
         */
        data class Error(val videoId: String) : ClassifyState()
    }
}
