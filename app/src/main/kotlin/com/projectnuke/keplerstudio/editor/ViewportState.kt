package com.projectnuke.keplerstudio.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

data class ViewportState(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0
) {
    /**
     * Returns this viewport clamped to new image geometry.
     * Preserves scale/pan if still valid, clamps pan to new bounds otherwise.
     * Viewport dimensions are preserved.
     */
    fun clampedForImage(newWidth: Int, newHeight: Int): ViewportState {
        val safeScale = scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
        if (viewportWidth <= 0 || viewportHeight <= 0 || newWidth <= 0 || newHeight <= 0) {
            return copy(scale = safeScale, offset = if (safeScale <= 1f) Offset.Zero else offset)
        }
        val clamped = PreviewGeometry(
            container = IntSize(viewportWidth, viewportHeight),
            imageWidth = newWidth,
            imageHeight = newHeight,
            zoom = safeScale,
            pan = offset,
        ).clampedPan()
        return copy(scale = safeScale, offset = clamped)
    }

    fun isSameTransform(other: ViewportState): Boolean =
        scale == other.scale && offset == other.offset && viewportWidth == other.viewportWidth && viewportHeight == other.viewportHeight
}
