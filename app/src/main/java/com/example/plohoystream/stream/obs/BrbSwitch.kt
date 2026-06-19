package com.example.plohoystream.stream.obs

/**
 * Pure, Android-free decision for the BRB ("be right back") auto-switch, modelled on Moblin's
 * `updateObsSceneSwitcher`: when the stream is likely [broken], switch the MAIN scene → BRB so
 * viewers see a placeholder instead of a frozen feed; when it's likely [working] again, switch
 * BRB → MAIN. Only ever flips between those two scenes (never yanks you off some other scene),
 * so it can't fight manual scene navigation. Returns the scene to switch to, or null for no-op.
 *
 * Evaluated continuously off stream health (not just on a one-shot state change), so it engages
 * whenever the link drops from the main scene and recovers when it comes back.
 */
object BrbSwitch {
    fun decide(
        broken: Boolean,
        working: Boolean,
        autoEnabled: Boolean,
        connected: Boolean,
        currentScene: String?,
        mainScene: String,
        brbScene: String,
    ): String? {
        if (!autoEnabled || !connected || currentScene == null) return null
        if (mainScene.isBlank() || brbScene.isBlank()) return null
        return when {
            broken && currentScene == mainScene -> brbScene
            working && currentScene == brbScene -> mainScene
            else -> null
        }
    }
}
