package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.OwnedBitmap
import com.projectnuke.keplerstudio.editor.OwnedHandoff
import com.projectnuke.keplerstudio.editor.pinBitmapLease
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PreviewHistogramMode(val label: String) {
    Luminance("광도"),
    RGB("RGB"),
}

internal data class PreviewHistogram(
    val luminance: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val sampledPixels: Int,
)

internal fun calculatePreviewHistogram(pixels: IntArray): PreviewHistogram {
    val luminance = IntArray(256)
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)
    var sampled = 0
    for (color in pixels) {
        val alpha = color ushr 24
        if (alpha <= 8) continue
        val r = (color ushr 16) and 0xff
        val g = (color ushr 8) and 0xff
        val b = color and 0xff
        val y = ((54 * r + 183 * g + 19 * b + 128) ushr 8).coerceIn(0, 255)
        red[r]++
        green[g]++
        blue[b]++
        luminance[y]++
        sampled++
    }
    return PreviewHistogram(luminance, red, green, blue, sampled)
}

@Composable
internal fun PreviewHistogramOverlay(
    bitmap: Bitmap,
    mode: PreviewHistogramMode,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val bitmapGeneration = bitmap.generationId
    val histogram by
        produceState<PreviewHistogram?>(initialValue = null, bitmap, bitmapGeneration) {
            val pin = viewModel.pinBitmapLease(bitmap)
            try {
                if (pin != null && !bitmap.isRecycled) {
                    value = createBoundedHistogram(bitmap)
                }
            } finally {
                pin?.close()
            }
        }
    val data = histogram ?: return
    Box(
        modifier =
            modifier
                .width(188.dp)
                .height(92.dp)
                .background(Color(0xB8000000))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 6f
            val graphWidth = (size.width - inset * 2f).coerceAtLeast(1f)
            val graphHeight = (size.height - inset * 2f).coerceAtLeast(1f)
            drawLine(
                Color.White.copy(alpha = 0.2f),
                Offset(inset, size.height - inset),
                Offset(size.width - inset, size.height - inset),
            )
            when (mode) {
                PreviewHistogramMode.Luminance ->
                    drawHistogramLine(
                        data.luminance,
                        Color.White,
                        inset,
                        graphWidth,
                        graphHeight,
                    )
                PreviewHistogramMode.RGB -> {
                    val sharedMax =
                        max(
                            data.red.maxOrNull() ?: 1,
                            max(data.green.maxOrNull() ?: 1, data.blue.maxOrNull() ?: 1),
                        ).coerceAtLeast(1)
                    drawHistogramLine(data.red, Color.Red, inset, graphWidth, graphHeight, sharedMax)
                    drawHistogramLine(data.green, Color.Green, inset, graphWidth, graphHeight, sharedMax)
                    drawHistogramLine(data.blue, Color.Blue, inset, graphWidth, graphHeight, sharedMax)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramLine(
    bins: IntArray,
    color: Color,
    inset: Float,
    graphWidth: Float,
    graphHeight: Float,
    fixedMaximum: Int? = null,
) {
    val maximum = (fixedMaximum ?: bins.maxOrNull() ?: 1).coerceAtLeast(1)
    val path = Path()
    bins.forEachIndexed { index, count ->
        val x = inset + graphWidth * index / 255f
        val y = size.height - inset - graphHeight * count / maximum.toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.5f))
}

@Composable
internal fun PreviewGridOverlay(
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val rect = fittedPreviewRect(size, imageWidth, imageHeight)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        val lineColor = Color.White.copy(alpha = 0.55f)
        val shadowColor = Color.Black.copy(alpha = 0.45f)
        for (step in 1..2) {
            val x = rect.left + rect.width * step / 3f
            val y = rect.top + rect.height * step / 3f
            drawLine(shadowColor, Offset(x + 1f, rect.top), Offset(x + 1f, rect.bottom), 2.5f)
            drawLine(shadowColor, Offset(rect.left, y + 1f), Offset(rect.right, y + 1f), 2.5f)
            drawLine(lineColor, Offset(x, rect.top), Offset(x, rect.bottom), 1.2f)
            drawLine(lineColor, Offset(rect.left, y), Offset(rect.right, y), 1.2f)
        }
    }
}

internal fun fittedPreviewRect(size: Size, imageWidth: Int, imageHeight: Int): Rect =
    com.projectnuke.keplerstudio.editor.fittedImageRect(size, imageWidth, imageHeight)

private suspend fun createBoundedHistogram(bitmap: Bitmap): PreviewHistogram? {
    val sampleSlot = OwnedHandoff<OwnedBitmap>()
    var sampledBitmap: Bitmap? = null
    try {
        withContext(Dispatchers.Default) {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext null
            val maxSide = 256
            val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height).toFloat())
            val width = max(1, (bitmap.width * scale).roundToInt())
            val height = max(1, (bitmap.height * scale).roundToInt())
            runCatching {
                if (width == bitmap.width && height == bitmap.height) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    Bitmap.createScaledBitmap(bitmap, width, height, true)
                }
            }.getOrNull()?.let { sampleSlot.publish(OwnedBitmap(it)) }
        }
        sampledBitmap = sampleSlot.take()?.take() ?: return null
        return withContext(Dispatchers.Default) {
            if (sampledBitmap?.isRecycled != false) return@withContext null
            val pixels = IntArray(sampledBitmap!!.width * sampledBitmap!!.height)
            sampledBitmap!!.getPixels(
                pixels,
                0,
                sampledBitmap.width,
                0,
                0,
                sampledBitmap.width,
                sampledBitmap.height,
            )
            calculatePreviewHistogram(pixels)
        }
    } finally {
        sampleSlot.close()
        sampledBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}
