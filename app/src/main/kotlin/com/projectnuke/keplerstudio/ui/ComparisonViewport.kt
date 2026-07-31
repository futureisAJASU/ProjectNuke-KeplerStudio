package com.projectnuke.keplerstudio.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

internal fun clampComparisonOffset(
    offset: Offset,
    viewportSize: IntSize,
    scale: Float,
): Offset {
    if (scale <= 1f || viewportSize.width <= 0 || viewportSize.height <= 0) return Offset.Zero
    val maxX = max(0f, viewportSize.width * (scale - 1f) * 0.5f)
    val maxY = max(0f, viewportSize.height * (scale - 1f) * 0.5f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

internal fun moveComparisonSplit(
    current: Float,
    dragAmountPx: Float,
    viewportWidthPx: Int,
): Float {
    val safe = if (current.isFinite()) current else 0.5f
    val safeDrag = if (dragAmountPx.isFinite()) dragAmountPx else 0f
    if (viewportWidthPx <= 0) return safe.coerceIn(0.05f, 0.95f)
    val delta = safeDrag / viewportWidthPx.toFloat()
    return (safe + if (delta.isFinite()) delta else 0f).coerceIn(0.05f, 0.95f)
}
