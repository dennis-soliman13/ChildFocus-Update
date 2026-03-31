package com.childfocus.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SafetyViewModel : ViewModel() {

    // ── Safety mode ───────────────────────────────────────────────────────────

    private val _safetyModeOn = MutableStateFlow(false)
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn.asStateFlow()

    // ── Waiting-for-service state ─────────────────────────────────────────────

    private val _isWaitingForService = MutableStateFlow(false)
    val isWaitingForService: StateFlow<Boolean> = _isWaitingForService.asStateFlow()

    fun setWaitingForService(waiting: Boolean) {
        _isWaitingForService.value = waiting
    }

    fun onServiceConfirmed() {
        _isWaitingForService.value = false
        _safetyModeOn.value        = true
    }

    fun turnOffSafetyMode() {
        _safetyModeOn.value        = false
        _isWaitingForService.value = false
    }

    // ── Classification state ──────────────────────────────────────────────────

    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState.asStateFlow()

    fun setAnalyzing(videoId: String) {
        _classifyState.value = ClassifyState.Analyzing(videoId)
    }

    fun setBlocked(videoId: String, score: Float) {
        _classifyState.value = ClassifyState.Blocked(videoId, score)
    }

    fun setAllowed(label: String, score: Float, cached: Boolean) {
        _classifyState.value = ClassifyState.Allowed(label, score, cached)
    }

    fun setError(videoId: String) {
        _classifyState.value = ClassifyState.Error(videoId)
    }

    fun dismissBlock() {
        _classifyState.value = ClassifyState.Idle
    }

    // ── ClassifyState ─────────────────────────────────────────────────────────

    sealed class ClassifyState {
        object Idle : ClassifyState()
        data class Analyzing(val videoId: String)                            : ClassifyState()
        data class Blocked  (val videoId: String, val score: Float)          : ClassifyState()
        data class Allowed  (val label: String, val score: Float,
                             val cached: Boolean)                            : ClassifyState()
        data class Error    (val videoId: String)                            : ClassifyState()
    }
}