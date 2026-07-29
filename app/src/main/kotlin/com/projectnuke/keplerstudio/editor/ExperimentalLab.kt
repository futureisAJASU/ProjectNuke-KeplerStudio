package com.projectnuke.keplerstudio.editor

import com.projectnuke.keplerstudio.BuildConfig
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
 * - the effective preview engine ([CorrectionEngineState.previewEngine], falling back to
 *   [CorrectionEngineState.documentEngine] when no preview has been rendered yet)
 * - optional debug-only per-feature overrides from [ExperimentalLabController]
 * - the deterministic fallback policy in [RouteResolver]
 *
 * Using the effective preview engine ensures that when a V2 document falls back to V1,
 * subsequent param adjustments render with V1 matching what the user sees, rather than
 * retrying V2 (which would fail and fall back again, causing flicker).
 *
 * No override means the effective engine route is used. An explicit debug V1 override
 * deliberately forces V1 for that feature. One feature override does not modify
 * unrelated features. Release builds ignore debug overrides.
 */
internal fun EditorUiState.renderRouting(): ExperimentalLabSelection {
    val overrides = ExperimentalLabController.debugOverridesCompat()
    val effectiveEngine = correctionEngineState.previewEngine ?: correctionEngineState.documentEngine
    return RouteResolver.toLegacySelection(effectiveEngine, overrides)
}

/**
 * Export routing must match the visible preview, not just the document engine.
 * When the preview is a V2-fallback-to-V1, export uses V1 to match what the user sees.
 * When a debug override produced the visible preview, export uses the same override.
 */
internal fun EditorUiState.renderRoutingForExport(): ExperimentalLabSelection {
    val overrides = ExperimentalLabController.debugOverridesCompat()
    val effectiveEngine = correctionEngineState.previewEngine ?: correctionEngineState.documentEngine
    return RouteResolver.toLegacySelection(effectiveEngine, overrides)
}

/**
 * Debug-only experimental lab controller. Stores per-feature debug overrides as nullable
 * values (null = follow document engine). In release builds, all mutations are refused
 * and the controller always returns [DebugFeatureOverrides.None].
 *
 * The state exposed as [ExperimentalLabSelection] is resolved at read time using the
 * document engine from the current [EditorUiState]. This ensures the UI always shows
 * the effective route, not raw debug state.
 */
object ExperimentalLabController {
    private val overrides = AtomicReference(DebugFeatureOverrides.None)
    private val mutableState = MutableStateFlow(routingForCorrectionEngine(CorrectionEngine.Engine1))

    /**
     * Current debug overrides. In release builds this is always [DebugFeatureOverrides.None].
     */
    fun debugOverrides(): DebugFeatureOverrides =
        if (BuildConfig.DEBUG) overrides.get() else DebugFeatureOverrides.None

    /**
     * Resolved selection for the given document engine. Used by the UI to display
     * the current effective state and by tests that need to verify route resolution.
     */
    fun resolvedSelection(engine: CorrectionEngine): ExperimentalLabSelection {
        val o = debugOverrides()
        return RouteResolver.toLegacySelection(engine, o)
    }

    /**
     * State flow of the resolved selection for the given engine. Updates when
     * debug overrides change.
     */
    fun stateFor(engine: CorrectionEngine): StateFlow<ExperimentalLabSelection> {
        val sel = resolvedSelection(engine)
        if (mutableState.value != sel) mutableState.value = sel
        return mutableState.asStateFlow()
    }

    /** Legacy state flow; resolves for whatever engine is set at first read. */
    val state: StateFlow<ExperimentalLabSelection> get() = mutableState.asStateFlow()

    /**
     * Update debug overrides. Only callable in debug builds. The transform receives
     * the current [DebugFeatureOverrides] and returns the new one.
     */
    fun updateDebugOverrides(transform: (DebugFeatureOverrides) -> DebugFeatureOverrides) {
        check(BuildConfig.DEBUG) { "Experimental Lab is unavailable in release builds" }
        val updated = transform(overrides.get())
        overrides.set(updated)
        mutableState.value = routingForCorrectionEngine(
            if (updated.nativeRender == NativeRenderRoute.V1 &&
                updated.flareGuard == FlareGuardRoute.V1 &&
                updated.remaster == RemasterRoute.V1 &&
                updated.subjectSelection == SubjectSelectionRoute.V1
            ) CorrectionEngine.Engine1 else CorrectionEngine.Engine2
        )
    }

    /**
     * Legacy: update debug selection via [ExperimentalLabSelection] transform.
     * Converts to/from [DebugFeatureOverrides] internally.
     */
    fun updateDebug(transform: (ExperimentalLabSelection) -> ExperimentalLabSelection) {
        check(BuildConfig.DEBUG) { "Experimental Lab is unavailable in release builds" }
        val current = mutableState.value
        val updated = transform(current)
        val o = DebugFeatureOverrides(
            nativeRender = updated.nativeRender.takeIf { it != RouteResolver.defaultNativeRoute(currentEngineFromSelection(updated)) },
            flareGuard = updated.flareGuard.takeIf { it != RouteResolver.defaultFlareRoute(currentEngineFromSelection(updated)) },
            remaster = updated.remaster.takeIf { it != RouteResolver.defaultRemasterRoute(currentEngineFromSelection(updated)) },
            subjectSelection = updated.subjectSelection.takeIf { it != RouteResolver.defaultSubjectRoute(currentEngineFromSelection(updated)) },
        )
        overrides.set(o)
        mutableState.value = updated
    }

    internal fun resetForTest() {
        overrides.set(DebugFeatureOverrides.None)
        mutableState.value = routingForCorrectionEngine(CorrectionEngine.Engine1)
    }
}

private fun currentEngineFromSelection(sel: ExperimentalLabSelection): CorrectionEngine =
    if (sel.nativeRender == NativeRenderRoute.V1) CorrectionEngine.Engine1
    else CorrectionEngine.Engine2

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
