package com.example.nsfwshield.core

import com.example.nsfwshield.Prefs

/**
 * Decides whether to block based on the NSFW score produced by the classifier.
 *
 * Supports three sensitivity presets. The thresholds were chosen conservatively:
 * the default (Conservative) blocks at a low NSFW score to err on the side of
 * protection; users who see too many false positives can switch to Balanced or
 * Less Sensitive.
 *
 * Hysteresis prevents rapid BLOCK/UNBLOCK flicker: the score must drop below the
 * unblock threshold (lower than the block threshold) before protection lifts.
 * While blocked, the DecisionEngine continues to request frames — if the content
 * is still present the overlay stays, if it clears the overlay lifts promptly.
 */
class DecisionEngine(private val sensitivity: Int) {

    interface Callback {
        fun onBlock(score: Float)
        fun onUnblock()
    }

    private enum class State { CLEAR, BLOCKED }

    private var state: State = State.CLEAR

    private val blockThreshold: Float = when (sensitivity) {
        Prefs.SENSITIVITY_CONSERVATIVE -> 0.30f
        Prefs.SENSITIVITY_BALANCED -> 0.50f
        Prefs.SENSITIVITY_LESS -> 0.70f
        else -> 0.30f
    }

    // Unblock threshold is lower than block threshold => hysteresis gap.
    private val unblockThreshold: Float = blockThreshold * 0.6f

    /**
     * Feed a new score into the engine. Calls the callback on state transitions only.
     */
    fun submit(score: Float, callback: Callback) {
        when (state) {
            State.CLEAR -> {
                if (score >= blockThreshold) {
                    state = State.BLOCKED
                    callback.onBlock(score)
                }
            }
            State.BLOCKED -> {
                if (score <= unblockThreshold) {
                    state = State.CLEAR
                    callback.onUnblock()
                }
                // else: stay blocked — content still present, overlay remains.
            }
        }
    }

    fun isBlocked(): Boolean = state == State.BLOCKED

    fun reset() {
        state = State.CLEAR
    }
}
