package com.example.plohoystream.stream.obs

import com.example.plohoystream.stream.StreamState

/**
 * Pure, Android-free decision function for the BRB auto-switch logic.
 * Returns the OBS scene to switch to, or null for "do nothing".
 */
object BrbSwitch {
    fun decide(
        state: StreamState,
        autoEnabled: Boolean,
        connected: Boolean,
        currentScene: String?,
        mainScene: String,
        brbScene: String,
    ): String? {
        if (!autoEnabled || !connected || currentScene == null) return null
        return when (state) {
            is StreamState.Reconnecting -> {
                if (brbScene.isNotBlank() && currentScene == mainScene) brbScene else null
            }
            is StreamState.Live -> {
                if (mainScene.isNotBlank() && currentScene == brbScene) mainScene else null
            }
            else -> null
        }
    }
}
