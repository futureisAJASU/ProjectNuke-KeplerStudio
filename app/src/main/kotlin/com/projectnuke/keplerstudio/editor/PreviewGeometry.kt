package com.projectnuke.keplerstudio.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * Canonical fitted-image geometry shared by preview, overlay, grid, brush, and comparison.
 *
 * Every consumer uses the same [imageRect] and coordinate mappings so transforms do not
 * diverge across independently copied helpers.
 */
data class PreviewGeometry(
    val container: IntSize,
    val imageWidth: Int,
    val imageHeight: Int,
    val padding: Float = 0f,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
) {
    /** The fitted image rect inside the container after padding, before zoom/pan. */
    val imageRect: Rect by lazy {
        fittedImageRect(
            Size(container.width.toFloat(), container.height.toFloat()),
            imageWidth, imageHeight, padding,
        )
    }

    /**
     * Maps a view position (in container-local space before graphicsLayer) to image pixels.
     * Returns null when the point is outside the fitted image.
     */
    fun viewToImage(view: Offset): Pair<Float, Float>? {
        val rect = imageRect
        if (rect.isEmpty) return null
        val effZoom = if (zoom > 0f) zoom else 1f
        val px = (view.x - pan.x) / effZoom
        val py = (view.y - pan.y) / effZoom
        if (px < rect.left || px > rect.right || py < rect.top || py > rect.bottom) return null
        val fracX = ((px - rect.left) / rect.width).coerceIn(0f, 1f)
        val fracY = ((py - rect.top) / rect.height).coerceIn(0f, 1f)
        return fracX * imageWidth to fracY * imageHeight
    }

    fun isValid(): Boolean =
        container.width > 0 && container.height > 0 && imageWidth > 0 && imageHeight > 0

    fun clampedPan(): Offset {
        if (zoom <= 1f) return Offset.Zero
        val rect = imageRect
        val innerW = (container.width - 2 * padding).coerceAtLeast(1f)
        val innerH = (container.height - 2 * padding).coerceAtLeast(1f)
        val maxX = max(0f, (rect.width * zoom - innerW) / 2f)
        val maxY = max(0f, (rect.height * zoom - innerH) / 2f)
        return Offset(
            pan.x.coerceIn(-maxX, maxX),
            pan.y.coerceIn(-maxY, maxY),
        )
    }
}

/**
 * Returns the fitted image rect for any container size and image. This is the single source
 * of truth used by both V1 and V2 previews, the overlay, the grid, and brush coordinate.
 */
fun fittedImageRect(size: Size, imageWidth: Int, imageHeight: Int, padding: Float = 0f): Rect {
    if (size.width <= 0f || size.height <= 0f || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val aspect = imageWidth.toFloat() / imageHeight.toFloat().coerceAtLeast(0.0001f)
    val containerAspect = size.width / size.height.coerceAtLeast(0.0001f)
    val fitW: Float
    val fitH: Float
    if (aspect > containerAspect) {
        fitW = size.width
        fitH = size.width / aspect
    } else {
        fitH = size.height
        fitW = size.height * aspect
    }
    val left = (size.width - fitW) / 2f
    val top = (size.height - fitH) / 2f
    return Rect(left, top, left + fitW, top + fitH)
}