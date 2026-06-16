package com.example.plohoystream.camera

/** PiP inset size as a fraction of the composited frame's smaller-axis span (see [PipLayout]). */
enum class PipSize(val widthFraction: Float) { S(0.22f), M(0.30f), L(0.40f) }

/** Normalized rectangle in the composited frame: (0,0) top-left .. (1,1) bottom-right. */
data class PipRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Layout of the draggable picture-in-picture inset for dual-camera mode. All coordinates are
 * normalized to the composited frame: (0,0) top-left .. (1,1) bottom-right. [x],[y] is the PiP's
 * top-left corner. The inset is kept square in normalized space (so it renders with the same aspect
 * as the output frame). [primaryFront] true means the FRONT camera is the full-frame main feed and
 * the PiP shows BACK; false means BACK is main and the PiP shows FRONT.
 */
data class PipLayout(
    val x: Float = 0.62f,
    val y: Float = 0.05f,
    val size: PipSize = PipSize.M,
    val primaryFront: Boolean = false,
) {
    companion object {
        /** The PiP rectangle in normalized frame coordinates. */
        fun pipRect(l: PipLayout): PipRect {
            val wf = l.size.widthFraction
            return PipRect(left = l.x, top = l.y, right = l.x + wf, bottom = l.y + wf)
        }

        /** Push [l] so its rectangle lies fully inside the unit square. */
        fun clampInBounds(l: PipLayout): PipLayout {
            val wf = l.size.widthFraction
            val maxXY = (1f - wf).coerceAtLeast(0f)
            return l.copy(x = l.x.coerceIn(0f, maxXY), y = l.y.coerceIn(0f, maxXY))
        }

        /** Snap [l] to whichever corner its center is closest to, inset by [margin]. */
        fun snapToNearestCorner(l: PipLayout, margin: Float = 0.04f): PipLayout {
            val wf = l.size.widthFraction
            val cx = l.x + wf / 2f
            val cy = l.y + wf / 2f
            val near = (1f - wf - margin).coerceAtLeast(0f)
            val targetX = if (cx < 0.5f) margin else near
            val targetY = if (cy < 0.5f) margin else near
            return l.copy(x = targetX, y = targetY)
        }

        /** Cycle S -> M -> L -> S, re-clamping so a grown inset stays on-screen. */
        fun cycleSize(l: PipLayout): PipLayout {
            val next = when (l.size) {
                PipSize.S -> PipSize.M
                PipSize.M -> PipSize.L
                PipSize.L -> PipSize.S
            }
            return clampInBounds(l.copy(size = next))
        }

        /** Flip which camera is the full-frame main feed vs the inset. */
        fun swapPrimary(l: PipLayout): PipLayout = l.copy(primaryFront = !l.primaryFront)
    }
}
