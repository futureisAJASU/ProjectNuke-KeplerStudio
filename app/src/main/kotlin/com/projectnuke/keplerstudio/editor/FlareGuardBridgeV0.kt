package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

private const val FLARE_GUARD_BRIDGE_TAG = "KeplerFlareAI"

class FlareGuardApplyResult internal constructor(
    private val ownedBitmap: TrackedBitmap,
    val status: FlareGuardRuntimeStatus,
    val fallbackReason: FlareGuardFallbackReason? = null,
    val algorithmDecision: FlareGuardV2Decision? = null,
    val maskSummary: FeatureMaskSummary? = null,
) {
    val bitmap: Bitmap get() = ownedBitmap.bitmap

    internal fun adoptToOrNull(owner: String): Bitmap? =
        ownedBitmap.adoptToOrNull(owner)

    internal fun recycleIfOwned(): Boolean = ownedBitmap.recycleAndRelease()
}

enum class FlareGuardRuntimeStatus(val uiText: String) {
    ExperimentalV2Rule("Experimental FlareGuard V2 used its rule mask."),
    ExperimentalV2Model("Experimental FlareGuard V2 fused validated model and rule masks."),
    ModelLoaded("플레어 마스크 모델을 불러왔습니다."),
    ModelInferenceSuccess("번짐 영역을 감지했습니다. 마스크 기반 기본 보정을 적용했습니다."),
    ModelUnavailableRuleFallback("모델 파일이 없어 기본 보정으로 대체했습니다."),
    ModelFailedRuleFallback("AI 모델 처리에 실패하여 기본 보정으로 대체했습니다."),
    Unavailable("번짐 완화에 실패했습니다.")
}

/**
 * Structured reason the Flare Guard path fell back to a rule. A model *inference*
 * failure must NOT collapse into [ModelUnavailableRuleFallback]; the runtime
 * status mirrors the structured [ModelLoadResult]/[ModelRunResult.Failure] reason.
 */
sealed interface FlareGuardFallbackReason {
    data object AssetMissing : FlareGuardFallbackReason
    data object AssetInvalid : FlareGuardFallbackReason
    data object UnsupportedContract : FlareGuardFallbackReason
    data object RuntimeUnavailable : FlareGuardFallbackReason
    data object LoadFailed : FlareGuardFallbackReason
    data object InferenceFailed : FlareGuardFallbackReason
    data object InvalidOutput : FlareGuardFallbackReason
    data object Stale : FlareGuardFallbackReason
    data object Cancelled : FlareGuardFallbackReason
}

internal fun ModelLoadResult<*>.fallbackReason(): FlareGuardFallbackReason =
    when (this) {
        is ModelLoadResult.AssetMissing -> FlareGuardFallbackReason.AssetMissing
        is ModelLoadResult.AssetInvalid -> FlareGuardFallbackReason.AssetInvalid
        is ModelLoadResult.UnsupportedContract -> FlareGuardFallbackReason.UnsupportedContract
        is ModelLoadResult.RuntimeUnavailable -> FlareGuardFallbackReason.RuntimeUnavailable
        is ModelLoadResult.LoadFailed -> FlareGuardFallbackReason.LoadFailed
        is ModelLoadResult.Ready -> FlareGuardFallbackReason.LoadFailed
    }

internal fun ModelFailureReason.fallbackReason(): FlareGuardFallbackReason =
    when (this) {
        ModelFailureReason.Cancelled -> FlareGuardFallbackReason.Cancelled
        ModelFailureReason.StaleGeneration -> FlareGuardFallbackReason.Stale
        ModelFailureReason.InvalidOutput -> FlareGuardFallbackReason.InvalidOutput
        ModelFailureReason.InferenceFailed -> FlareGuardFallbackReason.InferenceFailed
        ModelFailureReason.Closed -> FlareGuardFallbackReason.InferenceFailed
        ModelFailureReason.RunnerNotImplemented -> FlareGuardFallbackReason.UnsupportedContract
        ModelFailureReason.AssetMissing -> FlareGuardFallbackReason.AssetMissing
        ModelFailureReason.AssetInvalid -> FlareGuardFallbackReason.AssetInvalid
        ModelFailureReason.RuntimeUnavailable,
        ModelFailureReason.CapabilityUnknown -> FlareGuardFallbackReason.RuntimeUnavailable
        ModelFailureReason.ContractUnsupported -> FlareGuardFallbackReason.UnsupportedContract
        ModelFailureReason.UnsupportedVersion -> FlareGuardFallbackReason.UnsupportedContract
        ModelFailureReason.LoadingFailed -> FlareGuardFallbackReason.LoadFailed
        ModelFailureReason.InvalidInput -> FlareGuardFallbackReason.InferenceFailed
    }

internal suspend fun applyFlareGuardModelOrRuleResultV0(
    context: Context,
    source: Bitmap,
    mode: FlareGuardMode,
    strength: Float = when (mode) {
        FlareGuardMode.NightLight -> 0.28f
        FlareGuardMode.DaySun -> 0.24f
    },
    allowRuleFallback: Boolean = true,
    diagnostics: MemoryTrackerScope? = null,
    operation: ModelOperationContext,
): FlareGuardApplyResult {
    val registryLoadGeneration =
        ModelAvailabilityRegistry.reportLoading(ModelFeature.FlareGuard)
    val loadResult = FlareGuardModelRunner.create(context)
    ModelAvailabilityRegistry.reportLoad(
        ModelFeature.FlareGuard,
        loadResult,
        registryLoadGeneration,
    )
    val runner = (loadResult as? ModelLoadResult.Ready)?.runner
    val loadReason = loadResult.fallbackReason()
    if (runner != null) {
        val registrySessionGeneration =
            ModelAvailabilityRegistry.reportSessionReady(listOf(ModelFeature.FlareGuard))
        try {
            Log.i(
                FLARE_GUARD_BRIDGE_TAG,
                "FlareGuard model loaded: mode=$mode input=${runner.inputWidth}x${runner.inputHeight} source=${source.width}x${source.height}"
            )
            val modelRun = runner.predictMask(source, diagnostics, operation)
            if (modelRun is ModelRunResult.Success) {
                val result = modelRun.value
                Log.i(
                    FLARE_GUARD_BRIDGE_TAG,
                    "FlareGuard model inference success: mode=$mode confidence=${modelRun.confidence} mean=${result.meanAlpha} max=${result.maxAlpha}"
                )
                try {
                    val blended =
                        applyFlareGuardMaskBlendV0(
                            source,
                            result.mask,
                            mode,
                            strength,
                            diagnostics,
                        )
                    return FlareGuardApplyResult(
                        ownedBitmap = blended,
                        status = FlareGuardRuntimeStatus.ModelInferenceSuccess,
                        fallbackReason = null,
                        maskSummary = result.ownedMask.toFeatureMaskSummary(),
                    )
                } finally {
                    result.ownedMask.recycleAndRelease()
                }
            }
            val failure = modelRun as ModelRunResult.Failure
            val inferenceReason = failure.failure.reason.fallbackReason()
            if (failure.failure.reason == ModelFailureReason.Cancelled ||
                inferenceReason == FlareGuardFallbackReason.Cancelled
            ) {
                throw CancellationException(failure.failure.detail)
            }
            Log.w(
                FLARE_GUARD_BRIDGE_TAG,
                "FlareGuard model inference unavailable: reason=${failure.failure.reason}",
            )
            // A structured inference failure must NOT surface as ModelUnavailableRuleFallback.
            if (!allowRuleFallback) {
                return FlareGuardApplyResult(
                    TrackedBitmap.acquire(
                        source.copyOrThrow(Bitmap.Config.ARGB_8888, true),
                        diagnostics,
                        "flareGuard:unavailableCopy",
                    ),
                    FlareGuardRuntimeStatus.Unavailable,
                    fallbackReason = inferenceReason,
                )
            }
            val fallback = applyFlareGuardRuleFallback(source, mode, strength, diagnostics)
            Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard rule fallback path used: mode=$mode reason=inference_failure:${failure.failure.reason}")
            return FlareGuardApplyResult(
                fallback,
                FlareGuardRuntimeStatus.ModelFailedRuleFallback,
                fallbackReason = inferenceReason,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (t is BitmapAllocationRejectedException) throw t
            Log.e(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model path failed", t)
            if (!allowRuleFallback) {
                return FlareGuardApplyResult(
                    TrackedBitmap.acquire(
                        source.copyOrThrow(Bitmap.Config.ARGB_8888, true),
                        diagnostics,
                        "flareGuard:unavailableCopy",
                    ),
                    FlareGuardRuntimeStatus.Unavailable,
                    fallbackReason = FlareGuardFallbackReason.InferenceFailed,
                )
            }
            val fallback = applyFlareGuardRuleFallback(source, mode, strength, diagnostics)
            Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard rule fallback path used: mode=$mode reason=model_failed")
            return FlareGuardApplyResult(
                fallback,
                FlareGuardRuntimeStatus.ModelFailedRuleFallback,
                fallbackReason = FlareGuardFallbackReason.InferenceFailed,
            )
        } finally {
            runner.close()
            ModelAvailabilityRegistry.reportSessionClosed(
                listOf(ModelFeature.FlareGuard),
                registrySessionGeneration,
            )
        }
    } else {
        Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard model unavailable: reason=$loadReason")
    }

    if (!allowRuleFallback) {
        return FlareGuardApplyResult(
            TrackedBitmap.acquire(
                source.copyOrThrow(Bitmap.Config.ARGB_8888, true),
                diagnostics,
                "flareGuard:unavailableCopy",
            ),
            FlareGuardRuntimeStatus.Unavailable,
            fallbackReason = loadReason,
        )
    }
    val fallback = applyFlareGuardRuleFallback(source, mode, strength, diagnostics)
    Log.i(FLARE_GUARD_BRIDGE_TAG, "FlareGuard rule fallback path used: mode=$mode reason=model_unavailable:$loadReason")
    return FlareGuardApplyResult(
        fallback,
        FlareGuardRuntimeStatus.ModelUnavailableRuleFallback,
        fallbackReason = loadReason,
    )
}

private suspend fun applyFlareGuardRuleFallback(
    source: Bitmap,
    mode: FlareGuardMode,
    strength: Float,
    diagnostics: MemoryTrackerScope?,
): TrackedBitmap =
    when (mode) {
        FlareGuardMode.NightLight -> applyFlareGuardTracked(source, strength, mode, diagnostics)
        FlareGuardMode.DaySun -> applyFlareGuardTracked(source, strength, mode, diagnostics)
    }

internal suspend fun applyFlareGuardMaskBlendV0(
    source: Bitmap,
    modelMask: Bitmap,
    mode: FlareGuardMode,
    strength: Float,
    diagnostics: MemoryTrackerScope? = null
): TrackedBitmap {
    var output: TrackedBitmap? = null
    var ruleMask: TrackedBitmap? = null
    var scaledMask: TrackedBitmap? = null
    var rowsTransient = 0L
    var success = false
    try {
        output =
            TrackedBitmap.acquire(
                source.copyOrThrow(Bitmap.Config.ARGB_8888, true),
                diagnostics,
                "flareGuard:fullSizeOutputCopy",
            )
        ruleMask = createFlareMaskTracked(source, if (mode == FlareGuardMode.DaySun) 0.88f else 0.92f, diagnostics)
        scaledMask =
            if (modelMask.width == source.width && modelMask.height == source.height) {
                null
            } else {
                TrackedBitmap.acquire(
                    createScaledBitmapOrThrow(modelMask, source.width, source.height, true),
                    diagnostics,
                    "flareGuard:scaledModelMask",
                )
            }
        val effectiveModelMask =
            scaledMask?.bitmap ?: modelMask

        val width = output.bitmap.width
        val row = IntArray(width)
        val ruleRow = IntArray(width)
        val modelRow = IntArray(width)
        rowsTransient =
            diagnostics?.trackTransientBytes(
                "flareGuard:blendRows",
                width.toLong() * Int.SIZE_BYTES * 3L,
            ) ?: 0L
        val safeStrength = strength.coerceIn(0f, 1f)

        for (y in 0 until output.bitmap.height) {
            output.bitmap.getPixels(row, 0, width, 0, y, width, 1)
            ruleMask.bitmap.getPixels(ruleRow, 0, width, 0, y, width, 1)
            effectiveModelMask.getPixels(modelRow, 0, width, 0, y, width, 1)

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

            output.bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        success = true
        return output
    } finally {
        diagnostics?.releaseTransient(rowsTransient)
        ruleMask?.recycleAndRelease()
        scaledMask?.recycleAndRelease()
        if (!success) {
            output?.recycleAndRelease()
        }
    }
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
