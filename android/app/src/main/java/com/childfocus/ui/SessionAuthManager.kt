package com.childfocus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In-memory authentication state for the current app session.
 *
 * Behaviour:
 * - PIN required on first open.
 * - PIN required again after minimizing (ON_STOP clears the session).
 * - App cannot be swiped away / killed from the Recents screen unless the
 *   correct PIN is first provided — enforced via Activity back/task guards.
 */
object SessionAuthManager {

    /** True once the parent has entered the correct PIN in this session. */
    var isAuthenticated by mutableStateOf(false)
        private set

    /**
     * True while the "confirm close" PIN dialog is visible.
     * The Activity reads this to decide whether to honour a kill gesture.
     */
    var isShowingCloseConfirm by mutableStateOf(false)
        internal set

    // ── Called by PinGateScreen on correct PIN entry ──────────────────────────
    fun authenticate() {
        isAuthenticated = true
    }

    // ── Called from Activity.onStop() ────────────────────────────────────────
    fun onAppStop() {
        isAuthenticated       = false
        isShowingCloseConfirm = false
    }

    // ── Called from Activity back-press / predictive-back handler ────────────
    // Returns true = event consumed (blocked); false = let system handle it.
    fun onBackPressed(): Boolean {
        return if (!isAuthenticated) {
            true  // Still on PIN screen — block going further back
        } else {
            isShowingCloseConfirm = true   // Show the PIN-to-close dialog
            true                           // Consume the event
        }
    }

    /** Parent confirmed they want to close — allow it and reset state. */
    fun confirmClose() {
        isAuthenticated       = false
        isShowingCloseConfirm = false
    }

    /** Parent cancelled the close dialog. */
    fun cancelClose() {
        isShowingCloseConfirm = false
    }

    /** Explicit manual lock from inside the UI (Lock button). */
    fun lock() {
        isAuthenticated = false
    }
}