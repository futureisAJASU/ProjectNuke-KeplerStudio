package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

private const val FLARE_GUARD_BRIDGE_TAG = "KeplerFlareAI"

data class FlareGuardApplyResult(
    val bitmap: Bitmap,
    val status: FlareGuardRuntimeStatus
)

enum class FlareGuardRuntimeStatus(val uiText: String) {
    ModelLoaded("플레어 마스크 모델을 불러왔습니다."),
    ModelInferenceSuccess("번짐 영역을 감지했습니다. 마스크 기반 기본 보정을 적용했습니다."),
    ModelUnavailableRuleFallback("모델 파일이 없어 기본 보정으로 대체했습니다."),
    ModelFailedRuleFallback("AI 모델 처리에 실패하여 기본 보정으로 대체했습니다."),
    Unavailable("번짐 완화에 실패했습니다.")
}

fun applyFlareGuardModelOrRuleV0(
    context: Context,
    source: Bitmap,
    mode: FlareGuardMode,
    strength: Float = when (mode) {
        FlareGuardMode.NightLight -> 0.28f
        FlareGuardMode.DaySun -> 0.24f
    }
): Bitmap = applyFlareGuardModelOrRuleResultV0(context, source, mode, strength).bitmap

fun applyFlareGuardModelOrRuleResultV0(
    context: Context,
    source: Bitmap,
    mode: FlareGuardMode,
    strength: Float = when (mode) {
        FlareGuardMode.NightLight -> 0.28f
        FlareGuardMode.DaySun -> 0.24f
    },
    allowRuleFallback: Boolean = true
): FlareGuardApplyResult {
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
                    return FlareGuardApplyResult(
                        bitmap = applyFlareGuardMaskBlendV0(source, result.mask, mode, strength),
                        status = FlareGuardRuntimeStatus.ModelInferenceSuccess
                    )
                } finally {
                    result.mask.recycle()
                }
            }
            Log.w(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model inference returned null")
        } catch (t: Throwable) {
            Log.e(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model path failed", t)
            if (!allowRuleFallback) {
                return FlareGuardApplyResult(source.copyOrThrow(Bitmap.Config.ARGB_8888, true), FlareGuardRuntimeStatus.Unavailable)
            }
            val fallback = applyFlareGuardRuleFallback(source, mode, strength)
            Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard rule fallback path used: mode=$mode reason=model_failed")
            return FlareGuardApplyResult(fallback, FlareGuardRuntimeStatus.ModelFailedRuleFallback)
        } finally {
            runner.close()
        }
    } else {
        Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model asset unavailable")
    }

    if (!allowRuleFallback) {
        return FlareGuardApplyResult(source.copyOrThrow(Bitmap.Config.ARGB_8888, true), FlareGuardRuntimeStatus.Unavailable)
    }
    val fallback = applyFlareGuardRuleFallback(source, mode, strength)
    Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard rule fallback path used: mode=$mode reason=model_unavailable")
    return FlareGuardApplyResult(fallback, FlareGuardRuntimeStatus.ModelUnavailableRuleFallback)
}

private fun applyFlareGuardRuleFallback(source: Bitmap, mode: FlareGuardMode, strength: Float): Bitmap =
    when (mode) {
        FlareGuardMode.NightLight -> applyFlareGuardV0(source, strength)
        FlareGuardMode.DaySun -> applyDaySunFlareGuardV0(source, strength)
    }

fun applyFlareGuardMaskBlendV0(
    source: Bitmap,
    modelMask: Bitmap,
    mode: FlareGuardMode,
    strength: Float
): Bitmap {
    val output = source.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    val ruleMask = createFlareMaskV0(source, if (mode == FlareGuardMode.DaySun) 0.88f else 0.92f)
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
                val mask = max(ruleAlpha * 0.32f, modelAlpha * 0.72f).coerceIn(0f, 1f)
                val protect = if (mode == FlareGuardMode.DaySun) {
                    bridgeSmoothstep(0.88f, 1.0f, luma) * 0.90f
                } else {
                    bridgeSmoothstep(0.92f, 1.0f, luma) * 0.84f
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
    val desat = amount * 0.16f
    val darken = amount * 0.08f
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
    val contrastGain = 1f + amount * 0.16f
    val saturationGain = 1f + amount * 0.08f
    val darken = amount * 0.03f
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
