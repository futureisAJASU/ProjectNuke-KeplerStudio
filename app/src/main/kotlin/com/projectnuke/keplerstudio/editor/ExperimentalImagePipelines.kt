package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

data class ExperimentalPipelinePlan(
    val pixelCount: Int,
    val knownTransientBytes: Long,
    val hasUnknownContributors: Boolean,
    val stageBytes: Map<String, Long> = emptyMap(),
)

enum class FlareGuardV2Decision {
    ModelRuleFused,
    ModelAccepted,
    RuleSelected,
    ModelRejectedByQuality,
    MaskInsignificant,
    NoOpZeroStrength,
    NoOpSafetyFallback,
}

data class FlareGuardV2Result(
    val argb: IntArray,
    val mask: FloatArray,
    val maskMetrics: MaskQualityMetrics?,
    val decision: FlareGuardV2Decision,
    val decisionDetail: String? = null,
)

object FlareGuardV2 {
    fun plan(width: Int, height: Int): ExperimentalPipelinePlan {
        val count = checkedPixelCount(width, height)
        val plane = Math.multiplyExact(count.toLong(), Float.SIZE_BYTES.toLong())
        val packed = Math.multiplyExact(count.toLong(), Int.SIZE_BYTES.toLong())
        val refinement =
            MaskRefinement.plan(
                width,
                height,
                MaskRefinementOptions(
                    minimumComponentPixels = max(2, count / 50_000),
                    fillSinglePixelHoles = true,
                    dilationRadius = if (min(width, height) >= 256) 2 else 1,
                    featherRadius = if (min(width, height) >= 256) 4 else 1,
                    connectivity = MaskConnectivity.Eight,
                    activationThreshold = 0.28f,
                ),
            ).knownPeakTransientBytes
        // Worst model-assisted overlap: packed source/output conversion, luma, rule, model,
        // fused input, and refinement workspace. The TFLite arena remains unknown.
        val processPeak = Math.addExact(packed * 2L + plane * 4L, refinement)
        return ExperimentalPipelinePlan(
            count,
            processPeak,
            hasUnknownContributors = true,
            stageBytes =
                mapOf(
                    "bitmap-conversion" to packed * 2L,
                    "mask-analysis" to plane * 4L,
                    "mask-refinement" to refinement,
                ),
        )
    }

    fun process(
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mode: FlareGuardMode,
        strength: Float,
        modelMask: FloatArray? = null,
        isCancelled: () -> Boolean = { false },
    ): FlareGuardV2Result {
        val plan = plan(width, height)
        require(sourceArgb.size == plan.pixelCount)
        require(strength.isFinite() && strength in 0f..1f)
        modelMask?.let {
            require(it.size == plan.pixelCount)
            require(it.all { value -> value.isFinite() && value in 0f..1f })
        }
        checkCancelled(isCancelled)
        if (strength == 0f) {
            return FlareGuardV2Result(
                sourceArgb.copyOf(),
                FloatArray(plan.pixelCount),
                null,
                decision = FlareGuardV2Decision.NoOpZeroStrength,
            )
        }

        val luma = FloatArray(plan.pixelCount)
        val rule = FloatArray(plan.pixelCount)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val color = sourceArgb[index]
                val r = red(color)
                val g = green(color)
                val b = blue(color)
                luma[index] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
            if ((y and 31) == 0) checkCancelled(isCancelled)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val color = sourceArgb[index]
                val r = red(color)
                val g = green(color)
                val b = blue(color)
                val value = luma[index]
                val peak = max(r, max(g, b)).toFloat()
                val low = min(r, min(g, b)).toFloat()
                val chroma = peak - low
                var neighborhoodLuma = 0f
                var neighborhoodCount = 0
                for (yy in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (xx in max(0, x - 1)..min(width - 1, x + 1)) {
                        if (xx == x && yy == y) continue
                        neighborhoodLuma += luma[yy * width + xx]
                        neighborhoodCount++
                    }
                }
                val surrounding = neighborhoodLuma / neighborhoodCount.coerceAtLeast(1)
                val highlight =
                    smoothstep(
                        if (mode == FlareGuardMode.NightLight) 150f else 205f,
                        if (mode == FlareGuardMode.NightLight) 238f else 252f,
                        value,
                    )
                val contamination =
                    if (mode == FlareGuardMode.NightLight) {
                        smoothstep(12f, 92f, max(r - b, g - b).toFloat())
                    } else {
                        smoothstep(8f, 72f, (r - min(g, b)).toFloat())
                    }
                val bloomSupport =
                    smoothstep(
                        if (mode == FlareGuardMode.NightLight) 75f else 145f,
                        if (mode == FlareGuardMode.NightLight) 190f else 225f,
                        surrounding,
                    )
                val coloredGhost =
                    smoothstep(22f, 90f, chroma) *
                        smoothstep(70f, 210f, value) *
                        (1f - smoothstep(238f, 255f, peak))
                rule[index] =
                    max(
                        highlight * bloomSupport * (0.52f + 0.38f * contamination),
                        coloredGhost * (0.45f + 0.35f * contamination),
                    )
                        .coerceIn(0f, 1f)
            }
            if ((y and 31) == 0) checkCancelled(isCancelled)
        }

        val ruleQuality =
            MaskQualityValidator.evaluate(
                rule,
                width,
                height,
                MaskQualityThresholds(
                    activationThreshold = 0.32f,
                    minimumAffectedAreaRatio = 0.0001f,
                    maximumAffectedAreaRatio = 0.45f,
                    maximumIsolatedNoiseRatio = 0.65f,
                    connectivity = MaskConnectivity.Eight,
                ),
                isCancelled,
            )
        val modelQuality =
            modelMask?.let {
                MaskQualityValidator.evaluate(
                    it,
                    width,
                    height,
                    MaskQualityThresholds(
                        activationThreshold = 0.45f,
                        minimumAffectedAreaRatio = 0.0001f,
                        maximumAffectedAreaRatio = 0.5f,
                        minimumConfidence = 0.55f,
                        maximumIsolatedNoiseRatio = 0.5f,
                        connectivity = MaskConnectivity.Eight,
                    ),
                    isCancelled,
                )
            }
        val acceptedModel = modelQuality as? MaskQualityResult.Valid
        val fused =
            if (acceptedModel != null) {
                FloatArray(plan.pixelCount) { index ->
                    val model = modelMask[index]
                    val guided = rule[index]
                    // Preserve confident model-only components while requiring some rule support
                    // for uncertain values.
                    max(guided * 0.72f, model * (0.55f + 0.45f * guided))
                        .coerceIn(0f, 1f)
                }
            } else {
                rule
            }
        val refined =
            MaskRefinement.refine(
                fused,
                width,
                height,
                MaskRefinementOptions(
                    minimumComponentPixels = max(2, plan.pixelCount / 50_000),
                    fillSinglePixelHoles = true,
                    dilationRadius = if (min(width, height) >= 256) 2 else 1,
                    featherRadius = if (min(width, height) >= 256) 4 else 1,
                    connectivity = MaskConnectivity.Eight,
                    activationThreshold = 0.28f,
                ),
                isCancelled,
            )
        val finalQuality =
            MaskQualityValidator.evaluate(
                refined,
                width,
                height,
                MaskQualityThresholds(
                    activationThreshold = 0.22f,
                    minimumAffectedAreaRatio = 0.0001f,
                    maximumAffectedAreaRatio = 0.75f,
                    maximumIsolatedNoiseRatio = 0.7f,
                    connectivity = MaskConnectivity.Eight,
                ),
                isCancelled,
            )
        val metrics =
            when (finalQuality) {
                is MaskQualityResult.Valid -> finalQuality.metrics
                is MaskQualityResult.LowConfidence -> finalQuality.metrics
                is MaskQualityResult.Invalid -> null
            }
        if (metrics == null || metrics.affectedAreaRatio > 0.75f) {
            return FlareGuardV2Result(
                sourceArgb.copyOf(),
                refined,
                metrics,
                decision =
                    when {
                        modelMask != null && acceptedModel == null ->
                            FlareGuardV2Decision.ModelRejectedByQuality
                        metrics == null || metrics.affectedAreaRatio <= 0.0001f ->
                            FlareGuardV2Decision.MaskInsignificant
                        else -> FlareGuardV2Decision.NoOpSafetyFallback
                    },
                decisionDetail =
                    if (modelMask != null && acceptedModel == null) {
                        "model rejected; final rule mask failed affected-area safety"
                    } else {
                        "final mask failed affected-area safety"
                    },
            )
        }

        val output = sourceArgb.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val amount = refined[index] * strength
                if (amount <= 0.001f) continue
                val color = sourceArgb[index]
                val r = red(color)
                val g = green(color)
                val b = blue(color)
                val localGradient = localLumaGradient(luma, width, height, x, y)
                val edgeProtection = 1f - smoothstep(14f, 70f, localGradient) * 0.55f
                val darkProtection = smoothstep(18f, 90f, luma[index])
                val applied = amount * edgeProtection * darkProtection
                val targetLuma =
                    if (mode == FlareGuardMode.NightLight) {
                        luma[index] * (1f - 0.22f * applied)
                    } else {
                        luma[index] * (1f - 0.16f * applied)
                    }
                val lumaScale = targetLuma / max(1f, luma[index])
                var rr = r * lumaScale
                var gg = g * lumaScale
                var bb = b * lumaScale
                var referenceR = 0f
                var referenceG = 0f
                var referenceB = 0f
                var referenceWeight = 0f
                for (yy in max(0, y - 2)..min(height - 1, y + 2)) {
                    for (xx in max(0, x - 2)..min(width - 1, x + 2)) {
                        val sampleIndex = yy * width + xx
                        val weight = (1f - refined[sampleIndex]).coerceIn(0f, 1f)
                        if (weight <= 0.05f) continue
                        val sample = sourceArgb[sampleIndex]
                        referenceR += red(sample) * weight
                        referenceG += green(sample) * weight
                        referenceB += blue(sample) * weight
                        referenceWeight += weight
                    }
                }
                if (referenceWeight > 0.5f) {
                    val reconstruction = applied * 0.22f * (1f - smoothstep(18f, 64f, localGradient))
                    rr += (referenceR / referenceWeight - rr) * reconstruction
                    gg += (referenceG / referenceWeight - gg) * reconstruction
                    bb += (referenceB / referenceWeight - bb) * reconstruction
                }
                if (mode == FlareGuardMode.NightLight) {
                    val warm = max(0f, ((r + g) * 0.5f - b))
                    rr -= warm * 0.14f * applied
                    gg -= warm * 0.08f * applied
                    bb += warm * 0.035f * applied
                } else {
                    val redCast = max(0f, r - (g + b) * 0.5f)
                    rr -= redCast * 0.18f * applied
                    gg += redCast * 0.025f * applied
                    bb += redCast * 0.02f * applied
                }
                output[index] =
                    argb(
                        alpha(color),
                        boundedChannel(r, rr, 48f * applied),
                        boundedChannel(g, gg, 48f * applied),
                        boundedChannel(b, bb, 48f * applied),
                    )
            }
            if ((y and 31) == 0) checkCancelled(isCancelled)
        }
        return FlareGuardV2Result(
            output,
            refined,
            metrics,
            decision =
                when {
                    acceptedModel != null && ruleQuality !is MaskQualityResult.Invalid ->
                        FlareGuardV2Decision.ModelRuleFused
                    acceptedModel != null -> FlareGuardV2Decision.ModelAccepted
                    modelMask != null -> FlareGuardV2Decision.ModelRejectedByQuality
                    else -> FlareGuardV2Decision.RuleSelected
                },
            decisionDetail =
                when {
                    acceptedModel != null && ruleQuality is MaskQualityResult.Invalid ->
                        "model accepted with weak rule guidance"
                    modelMask != null && acceptedModel == null -> "model quality thresholds rejected"
                    else -> null
                },
        )
    }
}

data class RemasterV2Result(
    val argb: IntArray,
    val refinedMask: FloatArray,
    val maskMetrics: MaskQualityMetrics,
    val decision: RemasterV2Decision = RemasterV2Decision.MaskAccepted,
)

enum class RemasterV2Decision { MaskAccepted, ManualMaskAccepted, ModelMaskAccepted }

object RemasterV2 {
    fun plan(width: Int, height: Int): ExperimentalPipelinePlan {
        val count = checkedPixelCount(width, height)
        val plane = Math.multiplyExact(count.toLong(), Float.SIZE_BYTES.toLong())
        val packed = Math.multiplyExact(count.toLong(), Int.SIZE_BYTES.toLong())
        val refinement =
            MaskRefinement.plan(
                width,
                height,
                MaskRefinementOptions(
                    minimumComponentPixels = max(2, count / 100_000),
                    fillSinglePixelHoles = true,
                    featherRadius = 1,
                    connectivity = MaskConnectivity.Eight,
                    activationThreshold = 0.42f,
                ),
            ).knownPeakTransientBytes
        val refinementPeak = plane * 2L + refinement
        val renderPeak = packed * 2L + plane * 5L
        return ExperimentalPipelinePlan(
            count,
            maxOf(refinementPeak, renderPeak),
            hasUnknownContributors = false,
            stageBytes =
                mapOf(
                    "mask-refinement" to refinementPeak,
                    "luma-local-mean-blend" to renderPeak,
                ),
        )
    }

    fun process(
        sourceArgb: IntArray,
        foregroundMask: FloatArray,
        width: Int,
        height: Int,
        foregroundStrength: Float = 0.55f,
        backgroundStrength: Float = 0.2f,
        isCancelled: () -> Boolean = { false },
    ): RemasterV2Result {
        val plan = plan(width, height)
        require(sourceArgb.size == plan.pixelCount && foregroundMask.size == plan.pixelCount)
        require(foregroundStrength.isFinite() && foregroundStrength in 0f..1f)
        require(backgroundStrength.isFinite() && backgroundStrength in 0f..1f)
        val refined =
            MaskRefinement.refine(
                foregroundMask,
                width,
                height,
                MaskRefinementOptions(
                    minimumComponentPixels = max(2, plan.pixelCount / 100_000),
                    fillSinglePixelHoles = true,
                    dilationRadius = 0,
                    erosionRadius = 0,
                    featherRadius = 1,
                    connectivity = MaskConnectivity.Eight,
                    activationThreshold = 0.42f,
                ),
                isCancelled,
            )
        val quality =
            MaskQualityValidator.evaluate(
                refined,
                width,
                height,
                MaskQualityThresholds(
                    activationThreshold = 0.35f,
                    minimumAffectedAreaRatio = 0.0001f,
                    maximumAffectedAreaRatio = 0.98f,
                    maximumIsolatedNoiseRatio = 0.65f,
                    connectivity = MaskConnectivity.Eight,
                ),
                isCancelled,
            )
        val metrics =
            when (quality) {
                is MaskQualityResult.Valid -> quality.metrics
                is MaskQualityResult.LowConfidence -> quality.metrics
                is MaskQualityResult.Invalid -> error("Remaster V2 mask rejected: ${quality.reason}")
            }
        val luma = FloatArray(plan.pixelCount) { index -> luma(sourceArgb[index]) }
        val localMean = boxMean3x3(luma, width, height, isCancelled)
        val output = IntArray(plan.pixelCount)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val color = sourceArgb[index]
                val mask = refined[index]
                val detail = luma[index] - localMean[index]
                val localNoise = localAbsoluteDeviation(luma, localMean[index], width, height, x, y)
                val highlightProtection = 1f - smoothstep(210f, 252f, luma[index])
                val shadowProtection = smoothstep(8f, 55f, luma[index])
                val noiseProtection = 1f - smoothstep(5f, 24f, localNoise) * 0.72f
                val foregroundGain =
                    foregroundStrength * mask * highlightProtection * shadowProtection * noiseProtection
                val centered = luma[index] - 127.5f
                val backgroundGain =
                    backgroundStrength * (1f - mask) * highlightProtection * shadowProtection
                val lumaDelta =
                    detail * 0.58f * foregroundGain +
                        centered * 0.045f * backgroundGain
                val scale = (luma[index] + lumaDelta) / max(1f, luma[index])
                output[index] =
                    argb(
                        alpha(color),
                        boundedChannel(red(color), red(color) * scale, 24f),
                        boundedChannel(green(color), green(color) * scale, 24f),
                        boundedChannel(blue(color), blue(color) * scale, 24f),
                    )
            }
            if ((y and 31) == 0) checkCancelled(isCancelled)
        }
        return RemasterV2Result(output, refined, metrics)
    }
}

enum class ManualMaskEditMode {
    Add,
    Subtract,
}

data class SubjectSelectionV2Result(
    val mask: FloatArray,
    val metrics: MaskQualityMetrics,
    val operationToken: Long,
    val documentGeneration: String,
    val decision: SubjectSelectionV2Decision = SubjectSelectionV2Decision.RefinedModelMask,
)

enum class SubjectSelectionV2Decision {
    RefinedModelMask,
    RefinedManualMask,
    RefinedCombinedMask,
}

object SubjectSelectionV2 {
    fun plan(
        width: Int,
        height: Int,
        includesManualMask: Boolean,
    ): ExperimentalPipelinePlan {
        val count = checkedPixelCount(width, height)
        val plane = Math.multiplyExact(count.toLong(), Float.SIZE_BYTES.toLong())
        val options =
            MaskRefinementOptions(
                minimumComponentPixels = max(2, count / 80_000),
                fillSinglePixelHoles = true,
                dilationRadius = 1,
                erosionRadius = 1,
                featherRadius = 1,
                connectivity = MaskConnectivity.Eight,
                activationThreshold = 0.45f,
            )
        val refinement = MaskRefinement.plan(width, height, options).knownPeakTransientBytes
        val inputs = plane * if (includesManualMask) 3L else 1L
        return ExperimentalPipelinePlan(
            count,
            Math.addExact(inputs, refinement),
            false,
            mapOf("mask-inputs" to inputs, "mask-refinement" to refinement),
        )
    }

    fun refine(
        rawMask: FloatArray,
        width: Int,
        height: Int,
        operation: ModelOperationContext,
        manualMask: FloatArray? = null,
        manualMode: ManualMaskEditMode = ManualMaskEditMode.Add,
    ): SubjectSelectionV2Result {
        operation.validateOrThrow()
        val count = checkedPixelCount(width, height)
        require(rawMask.size == count)
        require(rawMask.all { it.isFinite() && it in 0f..1f })
        manualMask?.let {
            require(it.size == count && it.all { value -> value.isFinite() && value in 0f..1f })
        }
        val combined =
            if (manualMask == null) {
                rawMask
            } else {
                FloatArray(count) { index ->
                    when (manualMode) {
                        ManualMaskEditMode.Add -> max(rawMask[index], manualMask[index])
                        ManualMaskEditMode.Subtract ->
                            (rawMask[index] * (1f - manualMask[index])).coerceIn(0f, 1f)
                    }
                }
            }
        val refined =
            MaskRefinement.refine(
                combined,
                width,
                height,
                MaskRefinementOptions(
                    minimumComponentPixels = max(2, count / 80_000),
                    fillSinglePixelHoles = true,
                    dilationRadius = 1,
                    erosionRadius = 1,
                    featherRadius = 1,
                    connectivity = MaskConnectivity.Eight,
                    activationThreshold = 0.45f,
                ),
                operation.isCancelled,
            )
        operation.validateOrThrow()
        val quality =
            MaskQualityValidator.evaluate(
                refined,
                width,
                height,
                MaskQualityThresholds(
                    activationThreshold = 0.35f,
                    minimumAffectedAreaRatio = 0.0001f,
                    maximumAffectedAreaRatio = 0.98f,
                    minimumConfidence = 0.45f,
                    maximumIsolatedNoiseRatio = 0.55f,
                    connectivity = MaskConnectivity.Eight,
                ),
                operation.isCancelled,
            )
        val metrics =
            when (quality) {
                is MaskQualityResult.Valid -> quality.metrics
                is MaskQualityResult.LowConfidence -> quality.metrics
                is MaskQualityResult.Invalid -> error("Subject Selection V2 mask rejected: ${quality.reason}")
            }
        operation.validateOrThrow()
        return SubjectSelectionV2Result(
            refined,
            metrics,
            operation.operationToken,
            operation.documentGeneration,
            when {
                manualMask == null -> SubjectSelectionV2Decision.RefinedModelMask
                rawMask.all { it == 0f } -> SubjectSelectionV2Decision.RefinedManualMask
                else -> SubjectSelectionV2Decision.RefinedCombinedMask
            },
        )
    }
}

/**
 * Consumes [source] and returns a newly tracked, refined mask at the requested document size.
 *
 * The destination edge is acquired immediately after allocation, before conversion arrays are
 * released. The source Bitmap is never returned after adoption and is recycled on every path.
 */
internal fun refineTrackedSubjectSelectionV2(
    source: TrackedMask,
    targetWidth: Int,
    targetHeight: Int,
    diagnostics: MemoryTrackerScope?,
    operation: ModelOperationContext,
): TrackedMask {
    var ownedSource: Bitmap? = null
    var scaledSource: Bitmap? = null
    var scaledEdge = 0L
    var conversionTransient = 0L
    var output: TrackedMask? = null
    try {
        operation.validateOrThrow()
        val pipelinePlan =
            SubjectSelectionV2.plan(targetWidth, targetHeight, includesManualMask = false)
        if (!BitmapMemoryBudget.canAllocate(pipelinePlan.knownTransientBytes)) {
            throw BitmapAllocationRejectedException(pipelinePlan.knownTransientBytes)
        }
        ownedSource = source.requireAdopt()
        val readable =
            if (ownedSource.width == targetWidth && ownedSource.height == targetHeight) {
                ownedSource
            } else {
                createScaledBitmapOrThrow(
                    ownedSource,
                    targetWidth,
                    targetHeight,
                    true,
                ).also {
                    scaledSource = it
                    scaledEdge = diagnostics?.track(it, "subjectSelectionV2:scaledModelMask") ?: 0L
                }
            }
        val count = checkedPixelCount(targetWidth, targetHeight)
        val packed = IntArray(count)
        val values = FloatArray(count)
        val refinedPacked = IntArray(count)
        conversionTransient =
            diagnostics?.trackTransientBytes(
                "subjectSelectionV2:conversion",
                count.toLong() * (Int.SIZE_BYTES * 2L + Float.SIZE_BYTES),
            ) ?: 0L
        readable.getPixels(packed, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        for (index in values.indices) {
            val color = packed[index]
            values[index] =
                max(
                    (color ushr 16) and 0xff,
                    max((color ushr 8) and 0xff, color and 0xff),
                ) / 255f
        }
        val refined =
            SubjectSelectionV2.refine(
                values,
                targetWidth,
                targetHeight,
                operation,
            )
        operation.validateOrThrow()
        val destination =
            createBitmapOrThrow(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val confidence =
            ModelConfidence(
                wholeImageMean = refined.metrics.meanConfidence,
                peak = refined.metrics.peakConfidence,
                activeRegionMean = refined.metrics.activeRegionMeanConfidence,
                activeRegionPercentile = refined.metrics.activeRegionP90Confidence,
                affectedAreaRatio = refined.metrics.affectedAreaRatio,
                backgroundLeakage = refined.metrics.backgroundLeakage,
                modelProvided = source.confidenceMetrics.modelProvided,
                finalPolicy = refined.metrics.activeRegionMeanConfidence,
            )
        output =
            TrackedMask.acquire(
                bitmap = destination,
                scope = diagnostics,
                owner = "subjectSelectionV2:finalMask",
                modelId = source.modelId,
                modelVersion = "${source.modelVersion}+selection-v2",
                operationToken = operation.operationToken,
                documentGeneration = operation.documentGeneration,
                confidenceMetrics = confidence,
                maskQuality = MaskQualityResult.Valid(refined.metrics),
            )
        for (index in refinedPacked.indices) {
            val channel = (refined.mask[index] * 255f).roundToInt().coerceIn(0, 255)
            refinedPacked[index] = argb(255, channel, channel, channel)
        }
        destination.setPixels(
            refinedPacked,
            0,
            targetWidth,
            0,
            0,
            targetWidth,
            targetHeight,
        )
        operation.validateOrThrow()
        return checkNotNull(output).also { output = null }
    } catch (failure: Throwable) {
        output?.recycleAndRelease()
        throw failure
    } finally {
        diagnostics?.releaseTransient(conversionTransient)
        scaledSource?.takeUnless(Bitmap::isRecycled)?.recycle()
        diagnostics?.release(scaledEdge)
        ownedSource?.takeUnless(Bitmap::isRecycled)?.recycle()
        if (ownedSource == null) source.recycleAndRelease()
    }
}

private fun checkedPixelCount(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    val count = Math.multiplyExact(width.toLong(), height.toLong())
    require(count <= Int.MAX_VALUE)
    return count.toInt()
}

private fun boxMean3x3(
    source: FloatArray,
    width: Int,
    height: Int,
    isCancelled: () -> Boolean,
): FloatArray {
    val horizontal = FloatArray(source.size)
    val output = FloatArray(source.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val left = source[y * width + max(0, x - 1)]
            val center = source[y * width + x]
            val right = source[y * width + min(width - 1, x + 1)]
            horizontal[y * width + x] = (left + center + right) / 3f
        }
        if ((y and 31) == 0) checkCancelled(isCancelled)
    }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val top = horizontal[max(0, y - 1) * width + x]
            val center = horizontal[y * width + x]
            val bottom = horizontal[min(height - 1, y + 1) * width + x]
            output[y * width + x] = (top + center + bottom) / 3f
        }
        if ((y and 31) == 0) checkCancelled(isCancelled)
    }
    return output
}

private fun localAbsoluteDeviation(
    source: FloatArray,
    mean: Float,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): Float {
    var total = 0f
    var count = 0
    for (yy in max(0, y - 1)..min(height - 1, y + 1)) {
        for (xx in max(0, x - 1)..min(width - 1, x + 1)) {
            total += abs(source[yy * width + xx] - mean)
            count++
        }
    }
    return total / count.coerceAtLeast(1)
}

private fun localLumaGradient(
    luma: FloatArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): Float {
    val left = luma[y * width + max(0, x - 1)]
    val right = luma[y * width + min(width - 1, x + 1)]
    val top = luma[max(0, y - 1) * width + x]
    val bottom = luma[min(height - 1, y + 1) * width + x]
    return max(abs(right - left), abs(bottom - top))
}

private fun boundedChannel(original: Int, candidate: Float, maximumDelta: Float): Int {
    val low = original - maximumDelta
    val high = original + maximumDelta
    return candidate.coerceIn(low, high).roundToInt().coerceIn(0, 255)
}

private fun smoothstep(low: Float, high: Float, value: Float): Float {
    if (high <= low) return if (value >= high) 1f else 0f
    val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("experimental image pipeline cancelled")
}

private fun alpha(argb: Int) = (argb ushr 24) and 0xff
private fun red(argb: Int) = (argb ushr 16) and 0xff
private fun green(argb: Int) = (argb ushr 8) and 0xff
private fun blue(argb: Int) = argb and 0xff
private fun argb(a: Int, r: Int, g: Int, b: Int) =
    (a shl 24) or (r shl 16) or (g shl 8) or b
private fun luma(argb: Int) =
    0.2126f * red(argb) + 0.7152f * green(argb) + 0.0722f * blue(argb)

internal fun analyzeManualMask(bitmap: Bitmap): ModelConfidence {
    val row = IntArray(bitmap.width)
    var sum = 0.0
    var activeSum = 0.0
    var active = 0L
    var peak = 0f
    val total = bitmap.width.toLong() * bitmap.height
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (pixel in row) {
            val value = max(red(pixel), max(green(pixel), blue(pixel))) / 255f
            sum += value
            peak = max(peak, value)
            if (value >= 0.35f) {
                active++
                activeSum += value
            }
        }
    }
    val mean = (sum / total.coerceAtLeast(1)).toFloat()
    val activeMean = (activeSum / active.coerceAtLeast(1)).toFloat()
    val area = active.toFloat() / total.coerceAtLeast(1).toFloat()
    return ModelConfidence(
        wholeImageMean = mean,
        peak = peak,
        activeRegionMean = activeMean,
        activeRegionPercentile = peak,
        affectedAreaRatio = area,
        backgroundLeakage = if (active == 0L) mean else ((sum - activeSum) / (total - active).coerceAtLeast(1)).toFloat(),
        modelProvided = null,
        finalPolicy = activeMean * (1f - area.coerceAtLeast(0.85f).minus(0.85f) / 0.15f),
    )
}

internal fun applyExperimentalFlareGuardV2(
    context: Context,
    source: Bitmap,
    flareMode: FlareGuardMode,
    algorithmMode: FlareGuardRoute,
    strength: Float,
    diagnostics: MemoryTrackerScope?,
    operation: ModelOperationContext,
): FlareGuardApplyResult {
    require(algorithmMode != FlareGuardRoute.V1)
    operation.validateOrThrow()
    val pipelinePlan = FlareGuardV2.plan(source.width, source.height)
    if (!BitmapMemoryBudget.canAllocate(pipelinePlan.knownTransientBytes)) {
        throw BitmapAllocationRejectedException(pipelinePlan.knownTransientBytes)
    }
    val sourcePixels = IntArray(checkedPixelCount(source.width, source.height))
    val sourceTransient =
        diagnostics?.trackTransientBytes(
            "flareGuardV2:sourcePixels",
            sourcePixels.size.toLong() * Int.SIZE_BYTES,
        ) ?: 0L
    var modelMask: FloatArray? = null
    var modelMaskTransient = 0L
    var fallbackReason: FlareGuardFallbackReason? = null
    var runner: FlareGuardModelRunner? = null
    try {
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        if (algorithmMode == FlareGuardRoute.V2ModelAssisted) {
            when (val loaded = FlareGuardModelRunner.create(context)) {
                is ModelLoadResult.Ready -> {
                    runner = loaded.runner
                    when (val inference = loaded.runner.predictMask(source, diagnostics, operation)) {
                        is ModelRunResult.Success -> {
                            var ownedMask: Bitmap? = null
                            var scaledMask: Bitmap? = null
                            var scaledEdge = 0L
                            var maskPixelsTransient = 0L
                            try {
                                ownedMask = inference.value.ownedMask.requireAdopt()
                                val readable =
                                    if (
                                        ownedMask.width == source.width &&
                                            ownedMask.height == source.height
                                    ) {
                                        ownedMask
                                    } else {
                                        createScaledBitmapOrThrow(
                                            ownedMask,
                                            source.width,
                                            source.height,
                                            true,
                                        ).also {
                                            scaledMask = it
                                            scaledEdge =
                                                diagnostics?.track(
                                                    it,
                                                    "flareGuardV2:scaledModelMask",
                                                ) ?: 0L
                                        }
                                    }
                                val maskPixels = IntArray(sourcePixels.size)
                                maskPixelsTransient =
                                    diagnostics?.trackTransientBytes(
                                        "flareGuardV2:modelMaskPixels",
                                        maskPixels.size.toLong() * Int.SIZE_BYTES,
                                    ) ?: 0L
                                readable.getPixels(
                                    maskPixels,
                                    0,
                                    source.width,
                                    0,
                                    0,
                                    source.width,
                                    source.height,
                                )
                                modelMask =
                                    FloatArray(maskPixels.size) { index ->
                                        ((maskPixels[index] ushr 16) and 0xff) / 255f
                                    }
                                modelMaskTransient =
                                    diagnostics?.trackTransientBytes(
                                        "flareGuardV2:modelMaskValues",
                                        maskPixels.size.toLong() * Float.SIZE_BYTES,
                                    ) ?: 0L
                            } finally {
                                diagnostics?.releaseTransient(maskPixelsTransient)
                                scaledMask?.takeUnless(Bitmap::isRecycled)?.recycle()
                                diagnostics?.release(scaledEdge)
                                ownedMask?.takeUnless(Bitmap::isRecycled)?.recycle()
                            }
                        }
                        is ModelRunResult.Failure -> {
                            fallbackReason = inference.failure.reason.fallbackReason()
                        }
                    }
                }
                else -> fallbackReason = loaded.fallbackReason()
            }
        }
        operation.validateOrThrow()
        val processed =
            FlareGuardV2.process(
                sourcePixels,
                source.width,
                source.height,
                flareMode,
                strength,
                modelMask,
                operation.isCancelled,
            )
        operation.validateOrThrow()
        val output = createBitmapOrThrow(source.width, source.height, Bitmap.Config.ARGB_8888)
        val tracked = TrackedBitmap.acquire(output, diagnostics, "flareGuardV2:finalBitmap")
        try {
            output.setPixels(
                processed.argb,
                0,
                source.width,
                0,
                0,
                source.width,
                source.height,
            )
            operation.validateOrThrow()
            return FlareGuardApplyResult(
                tracked,
                if (processed.decision == FlareGuardV2Decision.ModelRuleFused ||
                    processed.decision == FlareGuardV2Decision.ModelAccepted
                ) {
                    FlareGuardRuntimeStatus.ExperimentalV2Model
                } else {
                    FlareGuardRuntimeStatus.ExperimentalV2Rule
                },
                fallbackReason,
                processed.decision,
            )
        } catch (failure: Throwable) {
            tracked.recycleAndRelease()
            throw failure
        }
    } finally {
        runner?.close()
        diagnostics?.releaseTransient(modelMaskTransient)
        diagnostics?.releaseTransient(sourceTransient)
    }
}

internal fun renderExperimentalRemasterV2(
    source: Bitmap,
    mask: Bitmap,
    diagnostics: MemoryTrackerScope?,
    operation: ModelOperationContext,
): Bitmap {
    operation.validateOrThrow()
    val plan = RemasterV2.plan(source.width, source.height)
    val required = plan.knownTransientBytes
    if (!BitmapMemoryBudget.canAllocate(required)) {
        throw BitmapAllocationRejectedException(required)
    }
    var scaledMask: Bitmap? = null
    var scaledEdge = 0L
    var sourceTransient = 0L
    var maskTransient = 0L
    try {
        val readableMask =
            if (mask.width == source.width && mask.height == source.height) {
                mask
            } else {
                createScaledBitmapOrThrow(mask, source.width, source.height, true).also {
                    scaledMask = it
                    scaledEdge = diagnostics?.track(it, "remasterV2:scaledMask") ?: 0L
                }
            }
        val sourcePixels = IntArray(plan.pixelCount)
        sourceTransient =
            diagnostics?.trackTransientBytes(
                "remasterV2:sourcePixels",
                sourcePixels.size.toLong() * Int.SIZE_BYTES,
            ) ?: 0L
        val maskPixels = IntArray(plan.pixelCount)
        val maskValues = FloatArray(plan.pixelCount)
        maskTransient =
            diagnostics?.trackTransientBytes(
                "remasterV2:maskConversion",
                maskPixels.size.toLong() * (Int.SIZE_BYTES + Float.SIZE_BYTES),
            ) ?: 0L
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        readableMask.getPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in maskValues.indices) {
            val color = maskPixels[index]
            maskValues[index] =
                max(
                    (color ushr 16) and 0xff,
                    max((color ushr 8) and 0xff, color and 0xff),
                ) / 255f
        }
        val result =
            RemasterV2.process(
                sourcePixels,
                maskValues,
                source.width,
                source.height,
                isCancelled = operation.isCancelled,
            )
        operation.validateOrThrow()
        val output = createBitmapOrThrow(source.width, source.height, Bitmap.Config.ARGB_8888)
        val outputEdge = diagnostics?.track(output, "remasterV2:finalBitmap") ?: 0L
        try {
            output.setPixels(result.argb, 0, source.width, 0, 0, source.width, source.height)
            operation.validateOrThrow()
            return output
        } catch (failure: Throwable) {
            output.takeUnless(Bitmap::isRecycled)?.recycle()
            diagnostics?.release(outputEdge)
            throw failure
        }
    } finally {
        diagnostics?.releaseTransient(maskTransient)
        diagnostics?.releaseTransient(sourceTransient)
        scaledMask?.takeUnless(Bitmap::isRecycled)?.recycle()
        diagnostics?.release(scaledEdge)
    }
}
