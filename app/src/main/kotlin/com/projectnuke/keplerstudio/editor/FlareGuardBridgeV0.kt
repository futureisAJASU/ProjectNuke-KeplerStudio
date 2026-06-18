package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

private const val FLARE_GUARD_BRIDGE_TAG = "KeplerFlareAI"

fun applyFlareGuardModelOrRuleV0(
    context: Context,
    source: Bitmap,
    mode: FlareGuardMode,
    strength: Float = when (mode) {
        FlareGuardMode.NightLight -> 0.35f
        FlareGuardMode.DaySun -> 0.32f
    }
): Bitmap {
    val runner = FlareGuardModelRunner.createOrNull(context)
    if (runner != null) {
        try {
            Log.i(
                FLARE_GUARD_BRIDGE_TAG,
                "FlareGuard model loaded: mode=$mode input=${runner.inputWidth}x${runner.inputHeight} source=${source.width}x${source.height}"
            )
            val result = runner.predictMaskOrNull(source)
            if (result != null) {
                Log.i(
                    FLARE_GUARD_BRIDGE_TAG,
                    "FlareGuard model inference success: mode=$mode mean=${result.meanAlpha} max=${result.maxAlpha}"
                )
                try {
                    return applyFlareGuardMaskBlendV0(source, result.mask, mode, strength)
                } finally {
                    result.mask.recycle()
                }
            }
            Log.w(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model inference returned null; falling back to rule path")
        } catch (t: Throwable) {
            Log.e(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model path failed; falling back to rule path", t)
        } finally {
            runner.close()
        }
    } else {
        Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model asset unavailable; using rule fallback")
    }

    return when (mode) {
        FlareGuardMode.NightLight -> applyFlareGuardV0(source, strength)
        FlareGuardMode.DaySun -> applyDaySunFlareGuardV0(source, strength)
    }
}

fun applyFlareGuardMaskBlendV0(
    source: Bitmap,
    modelMask: Bitmap,
    mode: FlareGuardMode,
    strength: Float
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val ruleMask = createFlareMaskV0(source, if (mode == FlareGuardMode.DaySun) 0.86f else 0.90f)
    val scaledMask = if (modelMask.width == source.width && modelMask.height == source.height) {
        modelMask
    } else {
        Bitmap.createScaledBitmap(modelMask, source.width, source.height, true)
    }

    try {
        val width = output.width
        val row = IntArray(width)
        val ruleRow = IntArray(width)
        val modelRow = IntArray(width)
        val safeStrength = strength.coerceIn(0f, 1f)

        for (y in 0 until output.height) {
            output.getPixels(row, 0, width, 0, y, width, 1)
            ruleMask.getPixels(ruleRow, 0, width, 0, y, width, 1)
            scaledMask.getPixels(modelRow, 0, width, 0, y, width, 1)

            for (x in 0 until width) {
                val pixel = row[x]
                val luma = bridgeLuma(pixel)
                val ruleAlpha = ((ruleRow[x] ushr 16) and 0xff) / 255f
                val modelAlpha = ((modelRow[x] ushr 16) and 0xff) / 255f
                val mask = max(ruleAlpha * 0.45f, modelAlpha * 0.85f).coerceIn(0f, 1f)
                val protect = if (mode == FlareGuardMode.DaySun) {
                    bridgeSmoothstep(0.90f, 1.0f, luma) * 0.82f
                } else {
                    bridgeSmoothstep(0.94f, 1.0f, luma) * 0.72f
                }
                val amount = mask * safeStrength * (1f - protect)
                if (amount > 0.001f) {
                    row[x] = if (mode == FlareGuardMode.DaySun) {
                        bridgeRecoverSun(pixel, amount)
                    } else {
                        bridgeReduceNight(pixel, amount)
                    }
                }
            }

            output.setPixels(row, 0, width, 0, y, width, 1)
        }
    } finally {
        ruleMask.recycle()
        if (scaledMask !== modelMask) scaledMask.recycle()
    }

    return output
}

private fun bridgeReduceNight(pixel: Int, amount: Float): Int {
    val alpha = pixel and -0x1000000
    val r = (pixel ushr 16) and 0xff
    val g = (pixel ushr 8) and 0xff
    val b = pixel and 0xff
    val luma = bridgeLuma(pixel)
    val desat = amount * 0.30f
    val darken = amount * 0.16f
    fun c(value: Int): Int {
        val n = value / 255f
        return ((n + (luma - n) * desat) * (1f - darken) * 255f).roundToInt().coerceIn(0, 255)
    }
    return alpha or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

private fun bridgeRecoverSun(pixel: Int, amount: Float): Int {
    val alpha = pixel and -0x1000000
    val r = (pixel ushr 16) and 0xff
    val g = (pixel ushr 8) and 0xff
    val b = pixel and 0xff
    val luma = bridgeLuma(pixel)
    val contrastGain = 1f + amount * 0.24f
    val saturationGain = 1f + amount * 0.14f
    val darken = amount * 0.07f
    fun c(value: Int): Int {
        val n = value / 255f
        val contrast = ((n - 0.5f) * contrastGain + 0.5f).coerceIn(0f, 1f)
        val saturated = luma + (contrast - luma) * saturationGain
        return (saturated * (1f - darken) * 255f).roundToInt().coerceIn(0, 255)
    }
    return alpha or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

private fun bridgeLuma(pixel: Int): Float {
    val r = ((pixel ushr 16) and 0xff) / 255f
    val g = ((pixel ushr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

private fun bridgeSmoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
