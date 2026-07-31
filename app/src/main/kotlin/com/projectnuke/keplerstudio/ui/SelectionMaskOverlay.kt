package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.SelectionLayer

private val OverlayBadgeBackground = Color(0xAA000000)
private val DefaultMaskTint = Color(0xFFE91E63)

/**
 * Overlay that renders the active selection mask over the editor preview.
 *
 * The mask is a grayscale ARGB bitmap (R=G=B=intensity, A=0xFF). Drawing it directly would
 * render a uniformly opaque black image. We therefore apply a [ColorMatrixColorFilter] that
 * remaps the intensity channel into a bounded tint color and an output alpha proportional to
 * the mask intensity, so nonselected pixels stay fully transparent and selected pixels are
 * drawn with a bounded, accessible tint.
 *
 * [inverted] masks display the complementary selected region.
 * Layer [opacity] is reflected via the overlay alpha coefficient.
 *
 * The caller passes the same [scale] and [offset] the preview image is rendered with so the
 * overlay always aligns with the active content rectangle and follows zoom and pan gestures.
 * The mask bitmap is never copied, mutated or allocated; only a [Paint] with filter is used.
 * The overlay is drawn in the Compose layer tree only and is therefore never included in
 * export pixels.
 */
@Composable
fun SelectionMaskOverlay(
    layer: SelectionLayer?,
    visible: Boolean,
    scale: Float,
    offset: Offset,
    modifier: Modifier = Modifier,
    tint: Color = DefaultMaskTint,
) {
    if (!visible || layer == null || !layer.enabled) return
    val maskBitmap: Bitmap = layer.bitmap
    if (maskBitmap.isRecycled) return
    val inverted = layer.inverted
    val overlayAlpha = layer.opacity.coerceIn(0f, 1f) * 0.42f
    val tR = tint.red * 255f
    val tG = tint.green * 255f
    val tB = tint.blue * 255f
    val tA = (overlayAlpha * 255f).coerceIn(1f, 255f)

    // ColorMatrix rows: out = matrix * [R, G, B, A, 1]
    // Mask channels: R=G=B=intensity I (0..255), A=0xFF
    // Target: out = (tintR, tintG, tintB, tA * (inverted ? 1 - I/255 : I/255))
    // Non-inverted: out_R = tR/255 * R
    // Inverted:    out_R = tR - tR/255 * R = -tR/255 * R + tR
    val rScale = if (inverted) -tR / 255f else tR / 255f
    val gScale = if (inverted) -tG / 255f else tG / 255f
    val bScale = if (inverted) -tB / 255f else tB / 255f
    val aScale = if (inverted) -tA / 255f else tA / 255f
    val rAdd = if (inverted) tR else 0f
    val gAdd = if (inverted) tG else 0f
    val bAdd = if (inverted) tB else 0f
    val aAdd = if (inverted) tA else 0f

    val paint = remember(maskBitmap, inverted, overlayAlpha, tint) {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        rScale, 0f, 0f, 0f, rAdd,
                        0f, gScale, 0f, 0f, gAdd,
                        0f, 0f, bScale, 0f, bAdd,
                        // Alpha comes from the intensity channel (R), not the A input.
                        aScale, 0f, 0f, 0f, aAdd,
                        0f, 0f, 0f, 0f, 1f,
                    ),
                ),
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val dst = RectF(0f, 0f, size.width, size.height)
                native.drawBitmap(maskBitmap, null, dst, paint)
            }
        }
        Text(
            text = layer.name,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(OverlayBadgeBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
