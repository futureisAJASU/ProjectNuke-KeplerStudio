package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class PresetColorLook(
    val size: Int,
    val strength: Float,
    val values: FloatArray
) {
    init {
        require(size >= 2) { "LUT size must be at least 2" }
        require(values.size == size * size * size * 3) { "LUT value count does not match size" }
    }
}

fun createPresetColorLookFromParams(
    params: EditParams,
    size: Int = 9,
    strength: Float = 0.72f
): PresetColorLook {
    val safeSize = size.coerceIn(5, 17)
    val values = FloatArray(safeSize * safeSize * safeSize * 3)
    var index = 0
    for (bi in 0 until safeSize) {
        val b = bi / (safeSize - 1f)
        for (gi in 0 until safeSize) {
            val g = gi / (safeSize - 1f)
            for (ri in 0 until safeSize) {
                val r = ri / (safeSize - 1f)
                val mapped = mapLookColor(r, g, b, params)
                values[index++] = mapped[0]
                values[index++] = mapped[1]
                values[index++] = mapped[2]
            }
        }
    }
    return PresetColorLook(safeSize, strength.coerceIn(0f, 1f), values)
}

fun applyPresetColorLookInPlace(bitmap: Bitmap, look: PresetColorLook?) {
    if (look == null || look.strength <= 0.001f) return
    val width = bitmap.width
    val row = IntArray(width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            row[x] = applyLookToPixel(row[x], look)
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
}

private fun mapLookColor(rIn: Float, gIn: Float, bIn: Float, params: EditParams): FloatArray {
    var r = rIn
    var g = gIn
    var b = bIn
    val luma0 = luma(r, g, b).coerceIn(0f, 1f)
    val contrastMul = 1f + params.contrast * 0.42f
    val temp = params.temperature * 0.10f
    val tint = params.tint * 0.07f
    val sat = 1f + params.saturation * 0.56f + params.vibrance * 0.34f * (1f - chroma(r, g, b))
    val gamma = (1f - params.exposure * 0.12f).coerceIn(0.72f, 1.38f)

    r = r.pow(gamma) * (1f + temp + tint * 0.40f)
    g = g.pow(gamma) * (1f - tint * 0.65f)
    b = b.pow(gamma) * (1f - temp + tint * 0.40f)

    var lum = luma(r, g, b).coerceIn(0f, 1f)
    val shadowMask = 1f - smoothstep(0.18f, 0.58f, lum)
    val highlightMask = smoothstep(0.46f, 0.92f, lum)
    lum += params.shadows * 0.18f * shadowMask
    lum -= params.highlights * 0.16f * highlightMask
    lum += params.whites * 0.12f * smoothstep(0.68f, 1f, lum)
    lum += params.blacks * 0.12f * (1f - smoothstep(0.0f, 0.35f, lum))
    lum = ((lum - 0.5f) * contrastMul + 0.5f).coerceIn(0f, 1f)

    val oldLum = max(0.015f, luma(r, g, b))
    val lumaScale = lum / oldLum
    r = (r * lumaScale).coerceIn(0f, 1f)
    g = (g * lumaScale).coerceIn(0f, 1f)
    b = (b * lumaScale).coerceIn(0f, 1f)

    val newLum = luma(r, g, b)
    r = (newLum + (r - newLum) * sat).coerceIn(0f, 1f)
    g = (newLum + (g - newLum) * sat).coerceIn(0f, 1f)
    b = (newLum + (b - newLum) * sat).coerceIn(0f, 1f)

    val clarity = params.clarity * 0.08f + params.dehaze * 0.10f
    val localMask = 1f - kotlin.math.abs(luma0 - 0.5f) * 2f
    val curve = (luma0 - 0.5f) * clarity * localMask.coerceIn(0f, 1f)
    r = (r + curve).coerceIn(0f, 1f)
    g = (g + curve).coerceIn(0f, 1f)
    b = (b + curve).coerceIn(0f, 1f)

    return floatArrayOf(r, g, b)
}

private fun applyLookToPixel(pixel: Int, look: PresetColorLook): Int {
    val a = pixel and -0x1000000
    val r = ((pixel shr 16) and 0xff) / 255f
    val g = ((pixel shr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    val mapped = sampleLook(r, g, b, look)
    val strength = look.strength
    val outR = lerp(r, mapped[0], strength)
    val outG = lerp(g, mapped[1], strength)
    val outB = lerp(b, mapped[2], strength)
    return a or (toByte(outR) shl 16) or (toByte(outG) shl 8) or toByte(outB)
}

private fun sampleLook(r: Float, g: Float, b: Float, look: PresetColorLook): FloatArray {
    val s = look.size
    val rf = r.coerceIn(0f, 1f) * (s - 1)
    val gf = g.coerceIn(0f, 1f) * (s - 1)
    val bf = b.coerceIn(0f, 1f) * (s - 1)
    val r0 = floor(rf).toInt().coerceIn(0, s - 1)
    val g0 = floor(gf).toInt().coerceIn(0, s - 1)
    val b0 = floor(bf).toInt().coerceIn(0, s - 1)
    val r1 = (r0 + 1).coerceAtMost(s - 1)
    val g1 = (g0 + 1).coerceAtMost(s - 1)
    val b1 = (b0 + 1).coerceAtMost(s - 1)
    val tr = rf - r0
    val tg = gf - g0
    val tb = bf - b0
    val out = FloatArray(3)
    for (c in 0 until 3) {
        val c000 = lookAt(look, r0, g0, b0, c)
        val c100 = lookAt(look, r1, g0, b0, c)
        val c010 = lookAt(look, r0, g1, b0, c)
        val c110 = lookAt(look, r1, g1, b0, c)
        val c001 = lookAt(look, r0, g0, b1, c)
        val c101 = lookAt(look, r1, g0, b1, c)
        val c011 = lookAt(look, r0, g1, b1, c)
        val c111 = lookAt(look, r1, g1, b1, c)
        val x00 = lerp(c000, c100, tr)
        val x10 = lerp(c010, c110, tr)
        val x01 = lerp(c001, c101, tr)
        val x11 = lerp(c011, c111, tr)
        val y0 = lerp(x00, x10, tg)
        val y1 = lerp(x01, x11, tg)
        out[c] = lerp(y0, y1, tb).coerceIn(0f, 1f)
    }
    return out
}

private fun lookAt(look: PresetColorLook, r: Int, g: Int, b: Int, c: Int): Float {
    val idx = (((b * look.size + g) * look.size + r) * 3 + c)
    return look.values[idx]
}

private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

private fun chroma(r: Float, g: Float, b: Float): Float = max(r, max(g, b)) - kotlin.math.min(r, kotlin.math.min(g, b))

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun toByte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
