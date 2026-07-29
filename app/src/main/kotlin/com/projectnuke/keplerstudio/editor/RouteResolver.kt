package com.projectnuke.keplerstudio.editor

/**
 * Authoritative route resolver for the editor.
 *
 * Each production render path goes through [RouteResolver] to determine the
 * requested route and possible fallback. The resolver never uses the old
 * visible-preview engine to decide the requested route — the requested route
 * is always derived from the **target/assigned document engine**.
 *
 * `previewEngine` describes the bitmap already visible; it is never an input
 * to route resolution.
 */

// ─── Debug overrides ──────────────────────────────────────────────────────────

/**
 * Nullable per-feature debug overrides. `null` means "Follow Document Engine".
 * In release builds this is always [DebugFeatureOverrides.None].
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

// ─── Operations ───────────────────────────────────────────────────────────────

/**
 * The kind of operation being routed. Different operations may have different
 * fallback behavior even when the document engine is the same.
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

// ─── Fallback ──────────────────────────────────────────────────────────────────

/** Why the actual route differs from the requested route, if it does. */
enum class FallbackReason {
    ModelUnavailable,
    V2RenderFailed,
    FeatureUnavailable,
    DebugForcedV1,
    DebugForcedV2,
}

// ─── Model availability ─────────────────────────────────────────────────────────

/**
 * Model availability flags relevant to route resolution. When a model-required
 * route cannot run because the model is unavailable, the resolver reports
 * [FallbackReason.ModelUnavailable] and the actual route is downgraded.
 */
data class RouteModelAvailability(
    val flareGuardModelAvailable: Boolean = false,
    val remasterModelAvailable: Boolean = false,
    val subjectSelectionModelAvailable: Boolean = false,
)

// ─── Structured route request ──────────────────────────────────────────────────

/**
 * The structured request for a native render route. This is the unambiguous input
 * to the resolver — every production caller constructs a [RouteRequest] instead
 * of inferring routing from the old visible-preview engine.
 *
 * @param operation The kind of operation (preview, engine switch, export, etc.)
 * @param effectiveDocumentEngine The engine interpretation assigned to the document.
 *   For an engine switch, this is the **target** engine, not the old preview engine.
 * @param debugOverride Nullable per-feature override; null means "follow engine".
 * @param availability Actual runtime model availability for feature routes.
 * @param identity Document/operation identity for stale/supersession checks.
 */
data class RouteRequest(
    val operation: RenderOperation,
    val effectiveDocumentEngine: CorrectionEngine,
    val debugOverride: DebugFeatureOverrides,
    val availability: RouteModelAvailability = RouteModelAvailability(),
)

// ─── Structured route result ───────────────────────────────────────────────────

/**
 * The resolved route for native rendering. Both what was requested and what
 * will actually be used after any fallback decision.
 */
data class ResolvedNativeRoute(
    val requestedRoute: NativeRenderRoute,
    val actualRoute: NativeRenderRoute,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
)

/**
 * The resolved route for a specific V2 feature operation (Flare/Remaster/Subject).
 */
data class ResolvedFeatureRoute<T : Enum<T>>(
    val requestedRoute: T,
    val actualRoute: T,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
)

// ─── Resolver ─────────────────────────────────────────────────────────────────

/**
 * The single source of truth for route resolution.
 *
 * Every production render path (preview, export, selection, Flare, Remaster,
 * Subject, Draft restore, history materialization, engine switch) goes through
 * this resolver. The resolver never derives the requested route from the old
 * visible-preview engine.
 */
object RouteResolver {

    // ─── Default routes ────────────────────────────────────────────────────────

    fun defaultNativeRoute(engine: CorrectionEngine): NativeRenderRoute =
        when (engine) {
            CorrectionEngine.Engine1 -> NativeRenderRoute.V1
            CorrectionEngine.Engine2 -> NativeRenderRoute.V2
        }

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

    // ─── Native route resolution ───────────────────────────────────────────────

    /**
     * Resolve the effective native render route.
     *
     * Debug override takes precedence when non-null. Otherwise the document
     * engine decides. `Compare` is mapped back to V2 (debug-only display value).
     *
     * For [RenderOperation.EngineSwitch], the requested route is derived from the
     * target engine — never from the old visible-preview engine.
     */
    fun resolveNativeRoute(
        engine: CorrectionEngine,
        debugOverride: NativeRenderRoute?,
    ): ResolvedNativeRoute {
        val requested = debugOverride ?: defaultNativeRoute(engine)
        val normalized = if (requested == NativeRenderRoute.Compare) NativeRenderRoute.V2 else requested

        val actual: NativeRenderRoute
        val fallback: FallbackReason?

        if (debugOverride == NativeRenderRoute.V1 && engine == CorrectionEngine.Engine2) {
            actual = NativeRenderRoute.V1
            fallback = FallbackReason.DebugForcedV1
        } else if (debugOverride == NativeRenderRoute.V2 && engine == CorrectionEngine.Engine1) {
            actual = NativeRenderRoute.V2
            fallback = FallbackReason.DebugForcedV2
        } else {
            actual = normalized
            fallback = null
        }

        return ResolvedNativeRoute(
            requestedRoute = normalized,
            actualRoute = actual,
            fallbackReason = fallback,
            usedDebugOverride = debugOverride != null,
        )
    }

    // ─── Feature route resolution ──────────────────────────────────────────────

    fun resolveFlareRoute(
        engine: CorrectionEngine,
        debugOverride: FlareGuardRoute?,
        modelAvailable: Boolean,
    ): ResolvedFeatureRoute<FlareGuardRoute> = resolveFeatureRoute(
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
    ): ResolvedFeatureRoute<RemasterRoute> = resolveFeatureRoute(
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
    ): ResolvedFeatureRoute<SubjectSelectionRoute> = resolveFeatureRoute(
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
    ): ResolvedFeatureRoute<T> {
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

        return ResolvedFeatureRoute(
            requestedRoute = if (normalized == forcedV1FallbackRoute) v1Route else normalized,
            actualRoute = actual,
            fallbackReason = fallback,
            usedDebugOverride = usedOverride,
        )
    }

    // ─── Legacy selection bridge ────────────────────────────────────────────────

    /**
     * Bridge to the legacy [RenderPipelinePlanner]. Produces the
     * [ExperimentalLabSelection] from the current document engine and overrides,
     * using actual model availability for feature routes.
     */
    fun toLegacySelection(
        engine: CorrectionEngine,
        overrides: DebugFeatureOverrides,
        availability: RouteModelAvailability,
    ): ExperimentalLabSelection {
        val native = resolveNativeRoute(engine, overrides.nativeRender)
        val flare = resolveFlareRoute(engine, overrides.flareGuard, availability.flareGuardModelAvailable)
        val remaster = resolveRemasterRoute(engine, overrides.remaster, availability.remasterModelAvailable)
        val subject = resolveSubjectRoute(engine, overrides.subjectSelection, availability.subjectSelectionModelAvailable)
        return ExperimentalLabSelection(
            nativeRender = native.actualRoute,
            flareGuard = flare.actualRoute,
            remaster = remaster.actualRoute,
            subjectSelection = subject.actualRoute,
        )
    }

    /**
     * Resolve the native route only, returning a selection suitable for
     * [RenderPipelinePlanner] without overriding feature routes.
     *
     * Use this when only the native pipeline matters (preview, export) and
     * feature-specific rendering (Flare/Remaster/Subject) handles its own routing.
     */
    fun toNativeSelection(
        engine: CorrectionEngine,
        debugOverride: NativeRenderRoute?,
    ): ExperimentalLabSelection {
        val resolved = resolveNativeRoute(engine, debugOverride)
        return routingForCorrectionEngine(engine).copy(nativeRender = resolved.actualRoute)
    }

    /**
     * Determine whether the native route for the given engine and override
     * differs from the other engine/document route.
     */
    fun nativeRouteChanged(
        engine: CorrectionEngine,
        debugOverride: NativeRenderRoute?,
        otherRoute: NativeRenderRoute,
    ): Boolean = resolveNativeRoute(engine, debugOverride).actualRoute != otherRoute
}

// ─── Convenience extension ─────────────────────────────────────────────────────

/**
 * Convenience: read debug overrides from the controller.
 * In release builds this always returns [DebugFeatureOverrides.None].
 */
internal fun ExperimentalLabController.debugOverridesCompat(): DebugFeatureOverrides = debugOverrides()
