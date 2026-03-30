package com.childfocus.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SafetyViewModel
 *
 * Additions for the Proton VPN-style flow:
 *
 *  isWaitingForService  — true while the user is in Accessibility Settings.
 *                         LandingScreen shows a pulsing "Waiting…" state.
 *
 *  setWaitingForService — called by MainActivity when it opens settings.
 *
 *  onServiceConfirmed   — called by MainActivity.onResume() when it detects
 *                         the accessibility service is now enabled. Clears the
 *                         waiting state and activates safety mode automatically.
 *
 *  turnOffSafetyMode    — explicit off (replaces the ambiguous toggleSafetyMode
 *                         call from SafetyModeScreen's "Turn Off" button).
 *
 * Keep your existing classifyState, dismissBlock, and any other fields below
 * the new additions — this file shows only the new/changed surface.
 */
class SafetyViewModel : ViewModel() {

    // ── Safety mode ──────────────────────────────────────────────────────────

    private val _safetyModeOn = MutableStateFlow(false)
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn.asStateFlow()

    // ── Waiting-for-service state ─────────────────────────────────────────────

    private val _isWaitingForService = MutableStateFlow(false)
    val isWaitingForService: StateFlow<Boolean> = _isWaitingForService.asStateFlow()

    /**
     * Called by MainActivity right before it opens Accessibility Settings.
     * Puts the LandingScreen into the animated "Waiting…" state.
     */
    fun setWaitingForService(waiting: Boolean) {
        _isWaitingForService.value = waiting
    }

    /**
     * Called by MainActivity.onResume() when isAccessibilityServiceEnabled() returns true.
     * Clears the waiting spinner and activates protection — just like Proton VPN
     * auto-transitioning to "Connected" after you flip the VPN toggle.
     */
    fun onServiceConfirmed() {
        _isWaitingForService.value = false
        _safetyModeOn.value        = true
    }

    /**
     * Called when the user taps "Turn Off" in SafetyModeScreen.
     */
    fun turnOffSafetyMode() {
        _safetyModeOn.value        = false
        _isWaitingForService.value = false
    }

    // ── Classification result state ───────────────────────────────────────────
    // Keep your existing classifyState / dismissBlock implementation below.
    // Example stub — replace with your real implementation:

    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState.asStateFlow()

    fun dismissBlock() {
        _classifyState.value = ClassifyState.Idle
    }

    // ── ClassifyState sealed class (keep your existing one if different) ───────

    sealed class ClassifyState {
        object Idle : ClassifyState()
        data class Blocked(val videoId: String, val score: Float) : ClassifyState()
        data class Analyzing(val title: String) : ClassifyState()
    }
}
