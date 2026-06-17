package com.example.plohoystream.camera.scene

/** Which camera slot a layer draws. PRIMARY = the high-res/effect slot (always the big view);
 *  SECONDARY = the low-res/direct slot (the PiP). Mapping of slot -> physical camera is owned by
 *  the controller's bind; swapping cameras is a rebind, not a scene mutation. */
enum class SourceId { PRIMARY, SECONDARY }

/** Normalized rectangle in the composited frame: (0,0) top-left .. (1,1) bottom-right. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    companion object { val FULL = NormRect(0f, 0f, 1f, 1f) }
}

/** PiP preset sizes as a fraction of the frame width (square box in normalized space). */
enum class PipSize(val widthFraction: Float) { S(0.22f), M(0.30f), L(0.40f) }

/** A camera [source] drawn into [rect]; layers composite in ascending [z]. */
data class SceneLayer(val source: SourceId, val rect: NormRect, val z: Int)

/** The composited scene: an ordered set of layers, the single source of truth for preview + stream. */
data class Scene(val layers: List<SceneLayer>) {
    val isDual: Boolean get() = layers.size > 1
    fun ordered(): List<SceneLayer> = layers.sortedBy { it.z }
    fun layer(source: SourceId): SceneLayer? = layers.firstOrNull { it.source == source }
    fun updateLayer(source: SourceId, transform: (NormRect) -> NormRect): Scene =
        copy(layers = layers.map { if (it.source == source) it.copy(rect = transform(it.rect)) else it })

    companion object {
        const val PIP_MARGIN = 0.04f
        val SINGLE = Scene(listOf(SceneLayer(SourceId.PRIMARY, NormRect.FULL, z = 0)))
        fun dual(pip: NormRect = defaultPip()): Scene = Scene(
            listOf(
                SceneLayer(SourceId.PRIMARY, NormRect.FULL, z = 0),
                SceneLayer(SourceId.SECONDARY, pip, z = 1),
            ),
        )
        /** A square PiP of [size] inset into the top-right corner by [margin]. */
        fun defaultPip(size: PipSize = PipSize.M, margin: Float = PIP_MARGIN): NormRect {
            val wf = size.widthFraction
            val left = 1f - wf - margin
            return NormRect(left, margin, left + wf, margin + wf)
        }
    }
}

/** Pure edits applied to the PiP layer's rect (kept square in normalized space). */
object SceneEdits {
    const val MIN_PIP_WF = 0.15f
    const val MAX_PIP_WF = 0.5f

    /** Re-center [r] on ([cx],[cy]), keeping its size, clamped fully inside the unit square. A rect
     *  wider/taller than the unit square (half-extent >= 0.5) can't fit, so it is centered on that
     *  axis (avoids an inverted [coerceIn] range). */
    fun moveTo(r: NormRect, cx: Float, cy: Float): NormRect {
        val halfW = r.width * 0.5f
        val halfH = r.height * 0.5f
        val clampedCx = if (halfW >= 0.5f) 0.5f else cx.coerceIn(halfW, 1f - halfW)
        val clampedCy = if (halfH >= 0.5f) 0.5f else cy.coerceIn(halfH, 1f - halfH)
        return NormRect(clampedCx - halfW, clampedCy - halfH, clampedCx + halfW, clampedCy + halfH)
    }

    /** Resize [r] to a square of [widthFraction] (clamped to [MIN_PIP_WF]..[MAX_PIP_WF]) about its
     *  center, then clamp back inside the unit square. */
    fun resizeKeepingCenter(r: NormRect, widthFraction: Float): NormRect {
        val wf = widthFraction.coerceIn(MIN_PIP_WF, MAX_PIP_WF)
        val half = wf * 0.5f
        val centered = NormRect(r.centerX - half, r.centerY - half, r.centerX + half, r.centerY + half)
        return moveTo(centered, centered.centerX, centered.centerY)
    }

    /** Snap [r] to whichever corner its center is closest to, inset by [margin]. The horizontal and
     *  vertical insets are derived per-axis so a non-square rect still snaps fully inside. */
    fun snapToCorner(r: NormRect, margin: Float): NormRect {
        val nearLeft = (1f - r.width - margin).coerceAtLeast(0f)
        val nearTop = (1f - r.height - margin).coerceAtLeast(0f)
        val targetLeft = if (r.centerX < 0.5f) margin else nearLeft
        val targetTop = if (r.centerY < 0.5f) margin else nearTop
        return NormRect(targetLeft, targetTop, targetLeft + r.width, targetTop + r.height)
    }
}
