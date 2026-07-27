package com.projectnuke.keplerstudio.editor

/**
 * Authoritative route resolver for the editor.
 *
 * This single resolver derives the effective route for every production render operation from:
 * - the current document engine ([CorrectionEngine])
 * - the operation type ([RenderOperation])
 * - an optional per-feature debug override (nullable — null means "follow document engine")
 * - model availability where relevant
 * - a deterministic fallback policy
 *
 * Debug overrides are represented as nullable values, not as complete selections whose V1
 * defaults accidentally override Engine 2. An override of `null` means "no override" and
 * therefore "use the document engine route". An explicit `V1` override deliberately forces
 * V1 for that feature only, without touching unrelated features.
 *
 * Release builds ignore debug overrides entirely — [DebugFeatureOverrides] is always empty
 * (all null) because [ExperimentalLabController.updateDebug] is guarded by `BuildConfig.DEBUG`.
 */
data class DebugFeatureOverrides(
    val nativeRender: NativeRenderRoute? = null,
    val flareGuard: FlareGuardRoute? = null,
    val remaster: RemasterRoute? = null,
    val subjectSelection: SubjectSelectionRoute? = null,
) {
    companion object {
        val None = DebugFeatureOverrides()
    }
}

/**
 * The kind of operation being routed. Different operations may share or differ in route
 * policy even when the document engine is the same.
 */
enum class RenderOperation {
    NativePreview,
    AutoEnhance,
    Preset,
    ProcessingEngineChange,
    EngineSwitch,
    SelectionGlobal,
    SelectionLocal,
    SelectionLivePreview,
    SelectionNativeBake,
    DraftRestore,
    HistoryMaterialization,
    ExportClean,
    ExportDirty,
    FlareGuard,
    Remaster,
    SubjectSelection,
    SubjectSelectionBake,
}

/**
 * The effective, resolved route for the document-based native render pipeline.
 *
 * [requestedRoute] is what the document engine + operation dictates before fallback.
 * [actualRoute] is what was or will be used after any fallback decision.
 */
data class EffectiveNativeRoute(
    val requestedRoute: NativeRenderRoute,
    val actualRoute: NativeRenderRoute,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
)

/** Why the actual route differs from the requested route, if it does. */
enum class FallbackReason {
    ModelUnavailable,
    V2RenderFailed,
    FeatureUnavailable,
    DebugForcedV1,
    DebugForcedV2,
}

/**
 * The effective route for a specific V2 feature operation (Flare/Remaster/Subject).
 */
data class EffectiveFeatureRoute<T : Enum<T>>(
    val requestedRoute: T,
    val actualRoute: T,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
)

/**
 * Model availability flags relevant to route resolution. When a model-required route
 * cannot run because the model is unavailable, the resolver reports [FallbackReason.ModelUnavailable]
 * and the actual route is downgraded to a rule/manual/V1 path.
 */
data class RouteModelAvailability(
    val flareGuardModelAvailable: Boolean = false,
    val remasterModelAvailable: Boolean = false,
    val subjectSelectionModelAvailable: Boolean = false,
)

/**
 * The single source of truth for route resolution.
 *
 * Every production render path (preview, export, selection, Flare, Remaster, Subject,
 * Draft restore, history materialization, engine switch) goes through this resolver.
 * Direct reads of `ExperimentalLabController.snapshot()` are no longer used by production.
 */
object RouteResolver {
    /**
     * Resolve the document-engine default route for native render.
     * Engine 1 → V1, Engine 2 → V2.
     */
    fun defaultNativeRoute(engine: CorrectionEngine): NativeRenderRoute =
        when (engine) {
            CorrectionEngine.Engine1 -> NativeRenderRoute.V1
            CorrectionEngine.Engine2 -> NativeRenderRoute.V2
        }

    /**
     * Resolve the document-engine default route for each V2 feature.
     * Engine 1 → V1-class routes. Engine 2 → V2-rule/manual paths (model-assisted only if model present).
     */
    fun defaultFlareRoute(engine: CorrectionEngine): FlareGuardRoute =
        when (engine) {
            CorrectionEngine.Engine1 -> FlareGuardRoute.V1
            CorrectionEngine.Engine2 -> FlareGuardRoute.V2Rule
        }

    fun defaultRemasterRoute(engine: CorrectionEngine): RemasterRoute =
        when (engine) {
            CorrectionEngine.Engine1 -> RemasterRoute.V1
            CorrectionEngine.Engine2 -> RemasterRoute.V2MaskAware
        }

    fun defaultSubjectRoute(engine: CorrectionEngine): SubjectSelectionRoute =
        when (engine) {
            CorrectionEngine.Engine1 -> SubjectSelectionRoute.V1
            CorrectionEngine.Engine2 -> SubjectSelectionRoute.V2ManualOrSynthetic
        }

    /**
     * Resolve the effective native render route for [operation].
     *
     * Debug override takes precedence when non-null. Otherwise the document engine decides.
     * If a debug override forces V2 but the document engine is V1, the override wins.
     * If a debug override forces V1 but the document engine is V2, the override wins
     * (this is the "explicitly force V1" case).
     *
     * `Compare` is not a valid production route; it is mapped back to V2 for native render.
     */
    fun resolveNativeRoute(
        engine: CorrectionEngine,
        operation: RenderOperation,
        debugOverride: NativeRenderRoute?,
    ): EffectiveNativeRoute {
        val requested = debugOverride ?: defaultNativeRoute(engine)
        val normalizedRequest = if (requested == NativeRenderRoute.Compare) NativeRenderRoute.V2 else requested

        val actual: NativeRenderRoute
        val fallback: FallbackReason?

        if (debugOverride == NativeRenderRoute.V1 && engine == CorrectionEngine.Engine2) {
            actual = NativeRenderRoute.V1
            fallback = FallbackReason.DebugForcedV1
        } else if (debugOverride == NativeRenderRoute.V2 && engine == CorrectionEngine.Engine1) {
            actual = NativeRenderRoute.V2
            fallback = FallbackReason.DebugForcedV2
        } else {
            actual = normalizedRequest
            fallback = null
        }

        return EffectiveNativeRoute(
            requestedRoute = normalizedRequest,
            actualRoute = actual,
            fallbackReason = fallback,
            usedDebugOverride = debugOverride != null,
        )
    }

    fun resolveFlareRoute(
        engine: CorrectionEngine,
        debugOverride: FlareGuardRoute?,
        modelAvailable: Boolean,
    ): EffectiveFeatureRoute<FlareGuardRoute> = resolveFeatureRoute(
        engine = engine,
        debugOverride = debugOverride,
        modelAvailable = modelAvailable,
        defaultRoute = ::defaultFlareRoute,
        v1Route = FlareGuardRoute.V1,
        modelRoute = FlareGuardRoute.V2ModelAssisted,
        fallbackRoute = FlareGuardRoute.V2Rule,
        forcedV1FallbackRoute = FlareGuardRoute.ForcedV1Fallback,
        compareRoute = FlareGuardRoute.Compare,
    )

    fun resolveRemasterRoute(
        engine: CorrectionEngine,
        debugOverride: RemasterRoute?,
        modelAvailable: Boolean,
    ): EffectiveFeatureRoute<RemasterRoute> = resolveFeatureRoute(
        engine = engine,
        debugOverride = debugOverride,
        modelAvailable = modelAvailable,
        defaultRoute = ::defaultRemasterRoute,
        v1Route = RemasterRoute.V1,
        modelRoute = RemasterRoute.V2ModelAssisted,
        fallbackRoute = RemasterRoute.V2MaskAware,
        forcedV1FallbackRoute = RemasterRoute.ForcedV1Fallback,
        compareRoute = RemasterRoute.Compare,
    )

    fun resolveSubjectRoute(
        engine: CorrectionEngine,
        debugOverride: SubjectSelectionRoute?,
        modelAvailable: Boolean,
    ): EffectiveFeatureRoute<SubjectSelectionRoute> = resolveFeatureRoute(
        engine = engine,
        debugOverride = debugOverride,
        modelAvailable = modelAvailable,
        defaultRoute = ::defaultSubjectRoute,
        v1Route = SubjectSelectionRoute.V1,
        modelRoute = SubjectSelectionRoute.V2ModelAssisted,
        fallbackRoute = SubjectSelectionRoute.V2ManualOrSynthetic,
        forcedV1FallbackRoute = SubjectSelectionRoute.ForcedV1Fallback,
        compareRoute = SubjectSelectionRoute.Compare,
    )

    /**
     * Generic feature-route resolver shared by Flare/Remaster/Subject.
     *
     * - No override → use the document engine default.
     * - Explicit V1 or ForcedV1Fallback override → force V1 for this feature.
     * - Compare override → mapped to the V2-rule/manual path (Compare is debug-only display).
     * - Model-assisted override when model unavailable → downgrade to fallback route.
     */
    private inline fun <T : Enum<T>> resolveFeatureRoute(
        engine: CorrectionEngine,
        debugOverride: T?,
        modelAvailable: Boolean,
        defaultRoute: (CorrectionEngine) -> T,
        v1Route: T,
        modelRoute: T,
        fallbackRoute: T,
        forcedV1FallbackRoute: T,
        compareRoute: T,
    ): EffectiveFeatureRoute<T> {
        val requested = debugOverride ?: defaultRoute(engine)
        val normalized = if (requested == compareRoute) fallbackRoute else requested

        var actual = normalized
        var fallback: FallbackReason? = null
        val usedOverride = debugOverride != null

        if (normalized == forcedV1FallbackRoute) {
            actual = v1Route
            fallback = FallbackReason.DebugForcedV1
        } else if (normalized == modelRoute && !modelAvailable) {
            actual = fallbackRoute
            fallback = FallbackReason.ModelUnavailable
        }

        return EffectiveFeatureRoute(
            requestedRoute = if (normalized == forcedV1FallbackRoute) v1Route else normalized,
            actualRoute = actual,
            fallbackReason = fallback,
            usedDebugOverride = usedOverride,
        )
    }

    /**
     * Build the [ExperimentalLabSelection] that the legacy [RenderPipelinePlanner] expects,
     * derived from the effective native route. This bridges the resolver to the existing
     * planner without requiring a planner rewrite.
     */
    fun toLegacySelection(nativeRoute: NativeRenderRoute): ExperimentalLabSelection =
        ExperimentalLabSelection().copy(nativeRender = nativeRoute)

    fun toLegacySelection(
        engine: CorrectionEngine,
        overrides: DebugFeatureOverrides,
    ): ExperimentalLabSelection {
        val native = resolveNativeRoute(engine, RenderOperation.NativePreview, overrides.nativeRender)
        val flare = resolveFlareRoute(engine, overrides.flareGuard, modelAvailable = false)
        val remaster = resolveRemasterRoute(engine, overrides.remaster, modelAvailable = false)
        val subject = resolveSubjectRoute(engine, overrides.subjectSelection, modelAvailable = false)
        return ExperimentalLabSelection(
            nativeRender = native.actualRoute,
            flareGuard = flare.actualRoute,
            remaster = remaster.actualRoute,
            subjectSelection = subject.actualRoute,
        )
    }
}

/**
 * Convenience: read debug overrides from the controller.
 * In release builds this always returns [DebugFeatureOverrides.None].
 */
internal fun ExperimentalLabController.debugOverridesCompat(): DebugFeatureOverrides = debugOverrides()
