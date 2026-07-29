package com.projectnuke.keplerstudio.editor

/**
 * Raw, nullable debug overrides. A null field follows the assigned document engine.
 * Release builds expose [None] regardless of the stored debug session state.
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

enum class RenderOperation {
    NativePreview,
    AutoEnhance,
    Preset,
    ProcessingEngineChange,
    EngineSwitch,
    Reset,
    QuickEffect,
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

enum class FallbackPolicy {
    /** A V2-native failure may render one V1 fallback; the next edit requests V2 again. */
    RetryV2OnNextOperation,

    /** The operation must either use its requested route or fail. */
    NoFallback,
}

enum class FallbackReason {
    ModelUnavailable,
    V2RenderFailed,
    FeatureUnavailable,
}

enum class RenderRouteDecision {
    FollowDocument,
    StoredVisibleTruth,
    DebugForcedV1,
    DebugForcedV2,
    RuntimeFallbackToV1,
}

data class RouteModelAvailability(
    val flareGuardModelAvailable: Boolean = false,
    val remasterModelAvailable: Boolean = false,
    val subjectSelectionModelAvailable: Boolean = false,
)

/**
 * Authoritative native-route input.
 *
 * [exactRoute] is reserved for operations that must reproduce stored visible truth,
 * such as export and history/Draft materialization. It is mutually exclusive with a
 * debug override.
 */
data class RouteRequest(
    val operation: RenderOperation,
    val assignedDocumentEngine: CorrectionEngine,
    val debugOverride: NativeRenderRoute? = null,
    val exactRoute: NativeRenderRoute? = null,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryV2OnNextOperation,
) {
    init {
        require(debugOverride == null || exactRoute == null)
        require(exactRoute != NativeRenderRoute.Compare)
    }
}

data class ResolvedNativeRoute(
    val requestedRoute: NativeRenderRoute,
    val primaryRoute: NativeRenderRoute,
    val decision: RenderRouteDecision,
    val fallbackAllowed: Boolean,
    val usedDebugOverride: Boolean,
)

data class ResolvedFeatureRoute<T : Enum<T>>(
    val requestedRoute: T,
    val actualRoute: T,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
)

/**
 * Single route authority for document rendering and feature operations.
 *
 * Document rendering is operation-aware: export never changes the stored visible route,
 * while ordinary edits, engine switches, Draft restore, history materialization, and
 * selection work can use the explicit one-operation V2 fallback policy.
 */
object RouteResolver {
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

    fun resolveNativeRoute(request: RouteRequest): ResolvedNativeRoute {
        val exact = request.exactRoute
        val debug = request.debugOverride?.takeUnless { it == NativeRenderRoute.Compare }
        val requested =
            exact ?: debug ?: defaultNativeRoute(request.assignedDocumentEngine)
        val decision =
            when {
                exact != null -> RenderRouteDecision.StoredVisibleTruth
                debug == NativeRenderRoute.V1 &&
                    request.assignedDocumentEngine == CorrectionEngine.Engine2 ->
                    RenderRouteDecision.DebugForcedV1
                debug == NativeRenderRoute.V2 &&
                    request.assignedDocumentEngine == CorrectionEngine.Engine1 ->
                    RenderRouteDecision.DebugForcedV2
                else -> RenderRouteDecision.FollowDocument
            }
        val operationAllowsFallback =
            request.operation !in
                setOf(
                    RenderOperation.ExportClean,
                    RenderOperation.ExportDirty,
                )
        return ResolvedNativeRoute(
            requestedRoute = requested,
            primaryRoute = requested,
            decision = decision,
            fallbackAllowed =
                requested == NativeRenderRoute.V2 &&
                    operationAllowsFallback &&
                    request.fallbackPolicy == FallbackPolicy.RetryV2OnNextOperation,
            usedDebugOverride = debug != null,
        )
    }

    fun resolveFlareRoute(
        engine: CorrectionEngine,
        debugOverride: FlareGuardRoute?,
        modelAvailable: Boolean,
    ): ResolvedFeatureRoute<FlareGuardRoute> =
        resolveFeatureRoute(
            engine = engine,
            debugOverride = debugOverride,
            modelAvailable = modelAvailable,
            defaultRoute = ::defaultFlareRoute,
            v1Route = FlareGuardRoute.V1,
            modelRoute = FlareGuardRoute.V2ModelAssisted,
            fallbackRoute = FlareGuardRoute.V2Rule,
            forcedV1Route = FlareGuardRoute.ForcedV1Fallback,
            compareRoute = FlareGuardRoute.Compare,
        )

    fun resolveRemasterRoute(
        engine: CorrectionEngine,
        debugOverride: RemasterRoute?,
        modelAvailable: Boolean,
    ): ResolvedFeatureRoute<RemasterRoute> =
        resolveFeatureRoute(
            engine = engine,
            debugOverride = debugOverride,
            modelAvailable = modelAvailable,
            defaultRoute = ::defaultRemasterRoute,
            v1Route = RemasterRoute.V1,
            modelRoute = RemasterRoute.V2ModelAssisted,
            fallbackRoute = RemasterRoute.V2MaskAware,
            forcedV1Route = RemasterRoute.ForcedV1Fallback,
            compareRoute = RemasterRoute.Compare,
        )

    fun resolveSubjectRoute(
        engine: CorrectionEngine,
        debugOverride: SubjectSelectionRoute?,
        modelAvailable: Boolean,
    ): ResolvedFeatureRoute<SubjectSelectionRoute> =
        resolveFeatureRoute(
            engine = engine,
            debugOverride = debugOverride,
            modelAvailable = modelAvailable,
            defaultRoute = ::defaultSubjectRoute,
            v1Route = SubjectSelectionRoute.V1,
            modelRoute = SubjectSelectionRoute.V2ModelAssisted,
            fallbackRoute = SubjectSelectionRoute.V2ManualOrSynthetic,
            forcedV1Route = SubjectSelectionRoute.ForcedV1Fallback,
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
        forcedV1Route: T,
        compareRoute: T,
    ): ResolvedFeatureRoute<T> {
        val requested = debugOverride ?: defaultRoute(engine)
        val normalized = if (requested == compareRoute) fallbackRoute else requested
        val actual: T
        val fallback: FallbackReason?
        when {
            normalized == forcedV1Route -> {
                actual = v1Route
                fallback = null
            }
            normalized == modelRoute && !modelAvailable -> {
                actual = fallbackRoute
                fallback = FallbackReason.ModelUnavailable
            }
            else -> {
                actual = normalized
                fallback = null
            }
        }
        return ResolvedFeatureRoute(
            requestedRoute = normalized,
            actualRoute = actual,
            fallbackReason = fallback,
            usedDebugOverride = debugOverride != null,
        )
    }

    fun toLegacySelection(
        engine: CorrectionEngine,
        overrides: DebugFeatureOverrides,
        availability: RouteModelAvailability,
        operation: RenderOperation = RenderOperation.NativePreview,
        exactNativeRoute: NativeRenderRoute? = null,
        fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryV2OnNextOperation,
    ): ExperimentalLabSelection {
        val native =
            resolveNativeRoute(
                RouteRequest(
                    operation = operation,
                    assignedDocumentEngine = engine,
                    debugOverride = overrides.nativeRender.takeIf { exactNativeRoute == null },
                    exactRoute = exactNativeRoute,
                    fallbackPolicy = fallbackPolicy,
                )
            )
        return ExperimentalLabSelection(
            nativeRender = native.primaryRoute,
            flareGuard =
                resolveFlareRoute(
                    engine,
                    overrides.flareGuard,
                    availability.flareGuardModelAvailable,
                ).actualRoute,
            remaster =
                resolveRemasterRoute(
                    engine,
                    overrides.remaster,
                    availability.remasterModelAvailable,
                ).actualRoute,
            subjectSelection =
                resolveSubjectRoute(
                    engine,
                    overrides.subjectSelection,
                    availability.subjectSelectionModelAvailable,
                ).actualRoute,
        )
    }

    fun nativeRouteChanged(
        engine: CorrectionEngine,
        debugOverride: NativeRenderRoute?,
        otherRoute: NativeRenderRoute,
    ): Boolean =
        resolveNativeRoute(
            RouteRequest(
                operation = RenderOperation.NativePreview,
                assignedDocumentEngine = engine,
                debugOverride = debugOverride,
            )
        ).primaryRoute != otherRoute
}

internal fun ExperimentalLabController.debugOverridesCompat(): DebugFeatureOverrides =
    debugOverrides()
