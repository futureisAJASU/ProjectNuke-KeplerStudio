package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NativeRenderRoute { V1, V2, Compare }

enum class FlareGuardRoute { V1, V2Rule, V2ModelAssisted, ForcedV1Fallback, Compare }

enum class RemasterRoute { V1, V2MaskAware, V2ModelAssisted, ForcedV1Fallback, Compare }

enum class SubjectSelectionRoute { V1, V2ManualOrSynthetic, V2ModelAssisted, ForcedV1Fallback, Compare }

/**
 * Resolved route selection for all features. This is the output of route resolution —
 * the actual routes that will be used for rendering. Production callers should obtain
 * this via [RouteResolver], not via direct global state inference.
 *
 * The `nativeRender` field drives [RenderPipelinePlanner]. The feature-specific fields
 * are consumed by Flare, Remaster, and Subject Selection after route resolution.
 */
data class ExperimentalLabSelection(
    val nativeRender: NativeRenderRoute = NativeRenderRoute.V1,
    val flareGuard: FlareGuardRoute = FlareGuardRoute.V1,
    val remaster: RemasterRoute = RemasterRoute.V1,
    val subjectSelection: SubjectSelectionRoute = SubjectSelectionRoute.V1,
)

/**
 * Derive the resolved [ExperimentalLabSelection] purely from a document engine.
 * This is the document engine default — no debug overrides.
 */
internal fun routingForCorrectionEngine(engine: CorrectionEngine): ExperimentalLabSelection =
    when (engine) {
        CorrectionEngine.Engine1 -> ExperimentalLabSelection()
        CorrectionEngine.Engine2 ->
            ExperimentalLabSelection(
                nativeRender = NativeRenderRoute.V2,
                flareGuard = FlareGuardRoute.V2Rule,
                remaster = RemasterRoute.V2MaskAware,
                subjectSelection = SubjectSelectionRoute.V2ManualOrSynthetic,
            )
    }

/**
 * Debug-only experimental lab controller. Stores per-feature debug overrides as nullable
 * values (null = follow document engine). In release builds, all mutations are refused
 * and the controller always returns [DebugFeatureOverrides.None].
 *
 * The state exposed as [overridesState] is a flow of raw [DebugFeatureOverrides] objects —
 * not a global resolved selection mutated by whichever engine last queried it. The UI
 * resolves display state from the current engine + raw overrides + model availability.
 */
object ExperimentalLabController {
    private val overrides = AtomicReference(DebugFeatureOverrides.None)
    private val overridesFlow = MutableStateFlow(DebugFeatureOverrides.None)

    /**
     * Current debug overrides. In release builds this is always [DebugFeatureOverrides.None].
     */
    fun debugOverrides(): DebugFeatureOverrides =
        if (BuildConfig.DEBUG) overrides.get() else DebugFeatureOverrides.None

    /**
     * State flow of raw [DebugFeatureOverrides]. Stable — does not change with the
     * document engine. UI and renderer subscribe to this and resolve display state
     * from the current engine + overrides.
     */
    val overridesState: StateFlow<DebugFeatureOverrides> get() = overridesFlow.asStateFlow()

    /**
     * Resolved selection for the given document engine. Used by the UI to display
     * the current effective state and by tests that need to verify route resolution.
     */
    fun resolvedSelection(
        engine: CorrectionEngine,
        availability: RouteModelAvailability = ModelAvailabilityRegistry.routeAvailability(),
    ): ExperimentalLabSelection {
        val o = debugOverrides()
        return RouteResolver.toLegacySelection(engine, o, availability)
    }

    /**
     * Update debug overrides. Only callable in debug builds. The transform receives
     * the current [DebugFeatureOverrides] and returns the new one.
     */
    fun updateDebugOverrides(transform: (DebugFeatureOverrides) -> DebugFeatureOverrides) {
        check(BuildConfig.DEBUG) { "Experimental Lab is unavailable in release builds" }
        val updated = transform(overrides.get())
        overrides.set(updated)
        overridesFlow.value = updated
    }

    /**
     * Set a single per-feature override. `null` means "Follow Document Engine".
     */
    fun setNativeOverride(route: NativeRenderRoute?) {
        updateDebugOverrides { it.copy(nativeRender = route) }
    }

    fun setFlareGuardOverride(route: FlareGuardRoute?) {
        updateDebugOverrides { it.copy(flareGuard = route) }
    }

    fun setRemasterOverride(route: RemasterRoute?) {
        updateDebugOverrides { it.copy(remaster = route) }
    }

    fun setSubjectSelectionOverride(route: SubjectSelectionRoute?) {
        updateDebugOverrides { it.copy(subjectSelection = route) }
    }

    internal fun resetForTest() {
        overrides.set(DebugFeatureOverrides.None)
        overridesFlow.value = DebugFeatureOverrides.None
    }
}

class OwnedDebugComparisonArtifact private constructor(
    val artifactId: Long,
    val fixtureVersion: String,
    val width: Int,
    val height: Int,
    val resolutionLevel: DebugComparisonResolution,
    val evaluatedWidth: Int,
    val evaluatedHeight: Int,
    val baseline: Bitmap,
    val experimental: Bitmap,
    val mask: Bitmap?,
    val differenceHeatmap: Bitmap,
    val metrics: ImageQualityMetricsV2,
    val baselineContracts: AlgorithmContractSet,
    val experimentalContracts: AlgorithmContractSet,
    val baseProvenance: BaseProvenanceChain,
    val algorithmDecision: String?,
    val knownTransientBytes: Long?,
    val durationMillis: Long?,
) : AutoCloseable {
    private val closeRequested = AtomicBoolean(false)
    private val references = AtomicInteger(1)
    private val recycled = AtomicBoolean(false)
    private val reservation =
        RetainedMemoryLedger.reserve("comparison-artifact-$artifactId").also { retained ->
            retained.replace(
                RetainedMemoryCategory.NativeBitmap,
                listOfNotNull(baseline, experimental, mask, differenceHeatmap)
                    .fold(0L) { total, bitmap ->
                        BitmapMemoryBudget.saturatingAdd(total, BitmapMemoryBudget.bytes(bitmap))
                    },
            )
        }

    val isClosed: Boolean
        get() = recycled.get()

    val canPublish: Boolean
        get() = !closeRequested.get() && !isClosed

    val retainedBitmapBytes: Long
        get() =
            listOfNotNull(baseline, experimental, mask, differenceHeatmap)
                .filterNot(Bitmap::isRecycled)
                .fold(0L) { total, bitmap ->
                    BitmapMemoryBudget.saturatingAdd(total, BitmapMemoryBudget.bytes(bitmap))
                }

    fun labeledBitmaps(): List<Pair<String, Bitmap>> {
        check(canPublish) { "comparison artifact is closing or closed" }
        return listOf(
            "V1" to baseline,
            "V2" to experimental,
            "차이" to differenceHeatmap,
        ) + listOfNotNull(mask?.let { "마스크" to it })
    }

    fun compactMetricJson(): String =
        buildString {
            append('{')
            append("\"fixtureVersion\":\"").append(fixtureVersion).append("\",")
            append("\"changedPixelRatio\":").append(metrics.changedPixelRatio).append(',')
            append("\"maximumChannelDelta\":").append(metrics.maximumChannelDelta).append(',')
            append("\"p95ChannelDelta\":").append(metrics.p95ChannelDelta).append(',')
            append("\"lumaMae\":").append(metrics.lumaMeanAbsoluteError).append(',')
            append("\"chromaMae\":").append(metrics.chromaMeanAbsoluteError)
            append('}')
        }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        releaseReference()
    }

    fun retain(): AutoCloseable {
        while (true) {
            check(!closeRequested.get()) { "comparison artifact is closing" }
            val current = references.get()
            check(current > 0) { "comparison artifact is closed" }
            if (references.compareAndSet(current, current + 1)) {
                if (closeRequested.get()) {
                    releaseReference()
                    error("comparison artifact began closing")
                }
                break
            }
        }
        val leaseClosed = AtomicBoolean(false)
        return AutoCloseable {
            if (leaseClosed.compareAndSet(false, true)) releaseReference()
        }
    }

    private fun releaseReference() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "comparison artifact reference underflow" }
        if (remaining != 0 || !closeRequested.get()) return
        if (!recycled.compareAndSet(false, true)) return
        listOfNotNull(baseline, experimental, mask, differenceHeatmap).forEach { bitmap ->
            bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
        reservation.close()
    }

    companion object {
        private val ids = AtomicLong()

        fun create(source: DebugComparisonArtifact): OwnedDebugComparisonArtifact {
            val owned = ArrayList<Bitmap>(4)
            fun create(pixels: IntArray): Bitmap =
                Bitmap.createBitmap(
                    pixels,
                    source.width,
                    source.height,
                    Bitmap.Config.ARGB_8888,
                ).also(owned::add)
            return try {
                OwnedDebugComparisonArtifact(
                    artifactId = ids.incrementAndGet(),
                    fixtureVersion = source.fixtureVersion,
                    width = source.width,
                    height = source.height,
                    resolutionLevel = source.resolutionLevel,
                    evaluatedWidth = source.evaluatedWidth,
                    evaluatedHeight = source.evaluatedHeight,
                    baseline = create(source.baselineArgb),
                    experimental = create(source.experimentalArgb),
                    mask = source.maskArgb?.let(::create),
                    differenceHeatmap = create(source.differenceHeatmapArgb),
                    metrics = source.metrics,
                    baselineContracts = source.baselineContracts,
                    experimentalContracts = source.experimentalContracts,
                    baseProvenance = source.baseProvenance,
                    algorithmDecision = source.algorithmDecision,
                    knownTransientBytes = source.knownTransientBytes,
                    durationMillis = source.durationMillis,
                )
            } catch (failure: Throwable) {
                owned.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
                throw failure
            }
        }
    }
}

object ExperimentalComparisonStore {
    private val lock = Any()
    private val mutable = MutableStateFlow<OwnedDebugComparisonArtifact?>(null)
    val latest: StateFlow<OwnedDebugComparisonArtifact?> = mutable.asStateFlow()

    /** Takes ownership of [artifact]. */
    fun publishDebug(artifact: OwnedDebugComparisonArtifact) {
        check(BuildConfig.DEBUG)
        check(artifact.canPublish)
        synchronized(lock) {
            val previous = mutable.value
            previous?.takeUnless { it === artifact }?.close()
            mutable.value = artifact
        }
    }

    fun clear() {
        synchronized(lock) {
            val previous = mutable.value
            mutable.value = null
            previous?.close()
        }
    }
}
