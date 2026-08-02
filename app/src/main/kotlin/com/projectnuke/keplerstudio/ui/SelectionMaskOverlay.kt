package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.pinBitmapLease
import com.projectnuke.keplerstudio.editor.fittedImageRect
import androidx.compose.ui.geometry.Size

private val OverlayBadgeBackground = Color(0xAA000000)
private val DefaultMaskTint = Color(0xFFE91E63)

internal fun selectionOverlayAlpha(
    maskIntensity: Int,
    layerOpacity: Float,
    overlayOpacity: Float,
    inverted: Boolean,
): Int {
    val intensity = maskIntensity.coerceIn(0, 255) / 255f
    val mask = if (inverted) 1f - intensity else intensity
    return (mask * layerOpacity.coerceIn(0f, 1f) * overlayOpacity.coerceIn(0f, 1f) * 255f)
        .toInt()
        .coerceIn(0, 255)
}

/**
 * Overlay that renders the active selection mask aligned to the fitted image rect.
 *
 * The mask is a grayscale ARGB bitmap (R=G=B=I, A=0xFF). The color matrix is:
 * - RGB: always stable tint color (independent of mask intensity)
 * - Alpha: derived from mask intensity * layer opacity * overlay alpha
 *
 * Non-inverted: alpha = I/255 * opacity * overlayFactor
 * Inverted:     alpha = (1 - I/255) * opacity * overlayFactor
 *
 * The overlay is drawn into the [graphicsLayer] that mirrors the preview image
 * transform, so it follows zoom and pan. The destination rect is clamped to the
 * fitted image rectangle so the overlay is correctly letterboxed for portrait,
 * landscape and ultra-wide images.
 */
@Composable
fun SelectionMaskOverlay(
    layer: SelectionLayer?,
    visible: Boolean,
    viewModel: EditorViewModel? = null,
    scale: Float,
    offset: Offset,
    paddingPx: Float = 0f,
    modifier: Modifier = Modifier,
    tint: Color = DefaultMaskTint,
) {
    if (!visible || layer == null || !layer.enabled) return
    val maskBitmap: Bitmap = layer.bitmap
    val bitmapPin = remember(maskBitmap, viewModel) { viewModel?.pinBitmapLease(maskBitmap) }
    DisposableEffect(bitmapPin) { onDispose { bitmapPin?.close() } }
    if ((viewModel != null && bitmapPin == null) || maskBitmap.isRecycled) return
    val inverted = layer.inverted
    val overlayAlpha = layer.opacity.coerceIn(0f, 1f) * 0.42f

    // ColorMatrix: stable RGB tint, alpha modulated by mask intensity.
    // Mask channels: R=G=B=I (0..255), A=0xFF (ignored)
    // Output: R = tR (constant), G = tG (constant), B = tB (constant)
    //         A = overlayAlpha * (I/255 or 1-I/255)
    // Non-inverted: RGB constant, alpha = overlayAlpha * I/255
    // Inverted:     RGB constant, alpha = overlayAlpha * (1 - I/255)
    val tA = overlayAlpha
    // Android ColorMatrix operates on 0..255 channels, while Compose Color stores 0..1.
    // Keep the conversion explicit so the tint is not rendered nearly black and the alpha
    // remains maskIntensity * layerOpacity * overlayOpacity.
    val aMul = if (inverted) -tA else tA
    val aAdd = if (inverted) tA * 255f else 0f

    val paint = remember(maskBitmap, tint, inverted, overlayAlpha) {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, tint.red * 255f,    // R = tR (constant)
                        0f, 0f, 0f, 0f, tint.green * 255f,  // G = tG (constant)
                        0f, 0f, 0f, 0f, tint.blue * 255f,   // B = tB (constant)
                        aMul, 0f, 0f, 0f, aAdd,       // A = aMul*R + aAdd
                    ),
                ),
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            val fitted = fittedImageRect(
                size, maskBitmap.width, maskBitmap.height, paddingPx,
            )
            if (!fitted.isEmpty) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val dst = RectF(fitted.left, fitted.top, fitted.right, fitted.bottom)
                    native.drawBitmap(maskBitmap, null, dst, paint)
                }
            }
        }
        Text(
            text = "${layer.name}${if (inverted) "" else ""}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(OverlayBadgeBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
