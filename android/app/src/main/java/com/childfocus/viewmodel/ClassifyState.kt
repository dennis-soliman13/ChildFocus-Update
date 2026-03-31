package com.childfocus.viewmodel

/**
 * Represents the current classification result displayed in SafetyModeScreen.
 * Top-level so both SafetyViewModel and SafetyModeScreen can import it directly.
 */
sealed class ClassifyState {

    /** Service is active but no video is being processed. */
    object Idle : ClassifyState()

    /** A video/title has been detected and is being sent to the backend. */
    data class Analyzing(val videoId: String) : ClassifyState()

    /** Backend returned Educational or Neutral — video is allowed. */
    data class Allowed(
        val label:  String,
        val score:  Float,
        val cached: Boolean,
    ) : ClassifyState()

    /** Backend returned Overstimulating — video is blocked. */
    data class Blocked(
        val videoId: String,
        val score:   Float,
    ) : ClassifyState()

    /** Classification failed or timed out. */
    data class Error(val videoId: String) : ClassifyState()
}
