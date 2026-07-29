package com.projectnuke.keplerstudio.editor

import com.projectnuke.keplerstudio.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.FlowCollector
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
 * this via [EditorUiState.renderRouting()] or [RouteResolver], not via direct
 * [ExperimentalLabController] reads.
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
 * Convert a resolved [ExperimentalLabSelection] back to nullable [DebugFeatureOverrides]
 * for the given document engine. A field matching the engine's default becomes null
 * (Follow Document Engine); a field that differs becomes an explicit override.
 */
internal fun ExperimentalLabSelection.toDebugOverrides(
    engine: CorrectionEngine,
): DebugFeatureOverrides = DebugFeatureOverrides(
    nativeRender = nativeRender.takeIf { it != RouteResolver.defaultNativeRoute(engine) },
    flareGuard = flareGuard.takeIf { it != RouteResolver.defaultFlareRoute(engine) },
    remaster = remaster.takeIf { it != RouteResolver.defaultRemasterRoute(engine) },
    subjectSelection = subjectSelection.takeIf { it != RouteResolver.defaultSubjectRoute(engine) },
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
 * Authoritative route resolver entry point for production native preview renders.
 *
 * Derives the effective [ExperimentalLabSelection] from:
 * - the **assigned document engine** ([CorrectionEngineState.documentEngine])
 * - optional debug-only per-feature overrides from [ExperimentalLabController]
 * - the deterministic fallback policy in [RouteResolver]
 *
 * The requested route is always derived from the assigned document engine, never
 * from the old visible-preview engine. This means:
 * - A switch to Engine 2 requests V2 (regardless of what the old preview was).
 * - A switch to Engine 1 requests V1.
 * - After a V2→V1 fallback, subsequent param adjustments still **retry Engine 2**
 *   (matching the fallback policy in Phase 5) unless the policy is sticky.
 *
 * No override means the document engine route is used. An explicit debug V1
 * override deliberately forces V1 for that feature only. Release builds ignore
 * debug overrides.
 */
internal fun EditorUiState.renderRouting(): ExperimentalLabSelection {
    val overrides = ExperimentalLabController.debugOverridesCompat()
    val availability = RouteModelAvailability()
    return RouteResolver.toLegacySelection(correctionEngineState.documentEngine, overrides, availability)
}

/**
 * Debug-only experimental lab controller. Stores per-feature debug overrides as nullable
 * values (null = follow document engine). In release builds, all mutations are refused
 * and the controller always returns [DebugFeatureOverrides.None].
 *
 * The state exposed as [overrides] is a flow of raw [DebugFeatureOverrides] objects —
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
    fun resolvedSelection(engine: CorrectionEngine): ExperimentalLabSelection {
        val o = debugOverrides()
        return RouteResolver.toLegacySelection(engine, o, RouteModelAvailability())
    }

    /**
     * Legacy state flow: resolved selection for the raw overrides, using Engine 1.
     * This is a simple derivation from the raw overrides flow. The value is recomputed
     * each time the overrides change.
     */
    val state: StateFlow<ExperimentalLabSelection> get() = ResolvedStateFlow(CorrectionEngine.Engine1)

    /**
     * Returns a [StateFlow] that maps the raw overrides flow into a resolved
     * [ExperimentalLabSelection] for the given engine. Each call creates a lightweight
     * wrapper; collect() delegates to the source flow.
     */
    fun stateFor(engine: CorrectionEngine): StateFlow<ExperimentalLabSelection> =
        ResolvedStateFlow(engine)

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

    /**
     * Lightweight [StateFlow] that resolves raw overrides to an
     * [ExperimentalLabSelection] for a fixed engine.
     */
    class ResolvedStateFlow(
        private val engine: CorrectionEngine,
    ) : StateFlow<ExperimentalLabSelection> {
        override val value: ExperimentalLabSelection
            get() = ExperimentalLabController.resolvedSelection(engine)

        @Suppress("RedundantSuspendModifier")
        override suspend fun collect(
            collector: FlowCollector<ExperimentalLabSelection>,
        ): Nothing {
            collector.emit(value)
            overridesFlow.collect { overrides ->
                collector.emit(
                    RouteResolver.toLegacySelection(engine, overrides, RouteModelAvailability()),
                )
            }
        }

        override val replayCache: List<ExperimentalLabSelection> get() = listOf(value)
    }
}

object ExperimentalComparisonStore {
    private val mutable = MutableStateFlow<DebugComparisonArtifact?>(null)
    val latest: StateFlow<DebugComparisonArtifact?> = mutable.asStateFlow()

    fun publishDebug(artifact: DebugComparisonArtifact) {
        check(BuildConfig.DEBUG)
        mutable.value = artifact
    }

    fun clear() {
        mutable.value = null
    }
}
