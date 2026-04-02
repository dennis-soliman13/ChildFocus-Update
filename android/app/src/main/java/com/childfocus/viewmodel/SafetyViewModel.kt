package com.childfocus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Sealed state that the UI observes ─────────────────────────────────────────
sealed class ClassifyState {
    object Idle      : ClassifyState()
    data class Analyzing(val videoId: String)                          : ClassifyState()
    data class Blocked(val videoId: String, val score: Float)          : ClassifyState()
    data class Error(val videoId: String)                              : ClassifyState()
    data class Allowed(val label: String, val score: Float, val cached: Boolean) : ClassifyState()
}

class SafetyViewModel(application: Application) : AndroidViewModel(application) {

    // ── Safety mode toggle ─────────────────────────────────────────────────────
    private val _safetyModeOn = MutableStateFlow(false)
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn

    // ── Classification state ───────────────────────────────────────────────────
    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState

    // ── Waiting-for-accessibility-service flag ─────────────────────────────────
    private val _isWaitingForService = MutableStateFlow(false)
    val isWaitingForService: StateFlow<Boolean> = _isWaitingForService

    // ── Public state setters (called from MainActivity's broadcast receiver) ───

    fun setAnalyzing(videoId: String) {
        _classifyState.value = ClassifyState.Analyzing(videoId)
    }

    fun setBlocked(videoId: String, score: Float) {
        _classifyState.value = ClassifyState.Blocked(videoId, score)
    }

    fun setError(videoId: String) {
        _classifyState.value = ClassifyState.Error(videoId)
    }

    fun setAllowed(label: String, score: Float, cached: Boolean) {
        _classifyState.value = ClassifyState.Allowed(label, score, cached)
    }

    // ── Safety mode actions ────────────────────────────────────────────────────

    /** Called when the user taps "Turn Off" inside SafetyModeScreen. */
    fun turnOffSafetyMode() {
        _safetyModeOn.value  = false
        _classifyState.value = ClassifyState.Idle
    }

    /** Clears a block card without turning off safety mode. */
    fun dismissBlock() {
        _classifyState.value = ClassifyState.Idle
    }

    // ── Accessibility-service handshake ───────────────────────────────────────

    /**
     * Called by MainActivity when the user returns from Accessibility Settings
     * and the service is now active.  Turns safety mode on and clears the
     * waiting flag.
     */
    fun onServiceConfirmed() {
        _isWaitingForService.value = false
        _safetyModeOn.value        = true
        _classifyState.value       = ClassifyState.Idle
    }

    /**
     * Flipped to `true` while we wait for the user to enable the service in
     * Settings, so the UI can show a loading/waiting indicator.
     */
    fun setWaitingForService(waiting: Boolean) {
        _isWaitingForService.value = waiting
    }
}