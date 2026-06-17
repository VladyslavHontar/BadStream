package com.example.plohoystream.camera

/** How a lens chip can be honored while dual (PiP) is on, on THIS device's hardware. */
enum class DualClass { REAL, ZOOM, UNAVAILABLE }

/** A selectable back sensor: its Camera2 [id], focal [ratio] vs the 1x main, and zoom bounds. */
data class BackLens(val id: String, val ratio: Float, val minZoom: Float, val maxZoom: Float)

/**
 * Pure, phone-agnostic model of what dual-camera framing is achievable, derived from the device's
 * certified concurrent-camera combinations. Built by [CameraCapabilityReader]; consumed by the UI
 * (chip rendering) and [DualCameraSession] (which switch is legal). No Android types — unit-tested.
 */
class ConcurrentCameraCapabilities(
    val backLenses: List<BackLens>,
    val frontIds: List<String>,
    val concurrentSets: Set<Set<String>>,
) {
    /** True if any certified combo contains a front id together with some back lens id. */
    fun supportsDual(): Boolean =
        concurrentSets.any { set -> frontIds.any { it in set } && backLenses.any { it.id in set } }

    /** True if [backId] and [frontId] appear together in some certified concurrent combo. */
    fun isConcurrent(backId: String, frontId: String): Boolean =
        concurrentSets.any { it.containsAll(listOf(backId, frontId)) }

    /**
     * Classify [chip] for dual when [openBack] is the currently-open back sensor and [frontId] is the
     * PiP front. REAL = its own sensor runs with the front; ZOOM = reachable by zooming the open
     * sensor (zoom only narrows FOV); UNAVAILABLE = wider than the open sensor and not concurrent.
     */
    fun dualClass(chip: BackLens, openBack: BackLens, frontId: String): DualClass = when {
        isConcurrent(chip.id, frontId) && isConcurrent(openBack.id, frontId) -> DualClass.REAL
        chip.ratio in (openBack.ratio * openBack.minZoom)..(openBack.ratio * openBack.maxZoom) -> DualClass.ZOOM
        else -> DualClass.UNAVAILABLE
    }
}
