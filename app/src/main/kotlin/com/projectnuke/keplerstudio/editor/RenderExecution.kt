package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlinx.coroutines.CancellationException

data class RenderIdentity(
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
)

data class RenderParticipation(
    val model: Boolean = false,
    val rule: Boolean = false,
    val manual: Boolean = false,
)

internal data class AlgorithmVersionResolution(
    val executedVersion: String,
    val migratedFromVersion: String?,
)

internal object PixelContractVersion {
    const val V1 = AlgorithmContracts.NATIVE_V1
    const val V2 = AlgorithmContracts.NATIVE_V2

    fun current(route: NativeRenderRoute): String =
        if (route == NativeRenderRoute.V2) V2 else V1

    fun normalizeHistorical(version: String?): String? =
        AlgorithmContractSet.fromLegacy(version).nativeRenderContract
}

internal fun resolveExecutedAlgorithmVersion(
    actualRoute: NativeRenderRoute,
    storedVersion: String?,
): AlgorithmVersionResolution {
    val current = PixelContractVersion.current(actualRoute)
    val storedSet = AlgorithmContractSet.fromLegacy(storedVersion)
    val stored = storedSet.nativeRenderContract
    return AlgorithmVersionResolution(
        executedVersion = current,
        migratedFromVersion =
            storedVersion
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeUnless {
                    stored == current && storedSet.migratedFromLegacy == null
                },
    )
}

internal data class RenderRequest(
    val operation: RenderOperation,
    val basePreview: Bitmap,
    val params: EditParams,
    val engines: EngineSelection,
    val assignedDocumentEngine: CorrectionEngine,
    val identity: RenderIdentity,
    val debugOverride: NativeRenderRoute? = null,
    val storedRequestedRoute: NativeRenderRoute? = null,
    val exactRoute: NativeRenderRoute? = null,
    val storedDecision: RenderRouteDecision? = null,
    val storedAlgorithmVersion: String? = null,
    val storedParticipation: RenderParticipation? = null,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryV2OnNextOperation,
    val look: PresetColorLook? = null,
    val quickEffects: List<ActiveQuickEffect> = emptyList(),
    val selectionLayers: List<SelectionLayer> = emptyList(),
    val diagnostics: MemoryTrackerScope? = null,
)

enum class RenderFailureKind {
    AllocationRejected,
    NativeV1Failed,
    NativeV2Failed,
    FallbackV1Failed,
    Unexpected,
}

sealed interface RenderResult {
    val operation: RenderOperation
    val requestedRoute: NativeRenderRoute

    data class Success(
        override val operation: RenderOperation,
        override val requestedRoute: NativeRenderRoute,
        val output: Bitmap,
        val actualRoute: NativeRenderRoute,
        val decision: RenderRouteDecision,
        val usedDebugOverride: Boolean,
        val algorithmVersion: String,
        val migratedFromAlgorithmVersion: String? = null,
        val participation: RenderParticipation,
        val durationMillis: Long,
        val knownTransientBytes: Long?,
    ) : RenderResult

    data class Failure(
        override val operation: RenderOperation,
        override val requestedRoute: NativeRenderRoute,
        val attemptedRoute: NativeRenderRoute,
        val kind: RenderFailureKind,
        val message: String,
        val fallbackFailureMessage: String? = null,
    ) : RenderResult

    data class Cancelled(
        override val operation: RenderOperation,
        override val requestedRoute: NativeRenderRoute,
        val attemptedRoute: NativeRenderRoute,
    ) : RenderResult

}

internal class V2RenderException(cause: Throwable) :
    RuntimeException(cause.message ?: "V2 rendering failed", cause)

internal class RenderFailedException(val failure: RenderResult.Failure) :
    RuntimeException(failure.message)

internal fun RenderResult.successOrThrow(): RenderResult.Success =
    when (this) {
        is RenderResult.Success -> this
        is RenderResult.Failure -> throw RenderFailedException(this)
        is RenderResult.Cancelled ->
            throw CancellationException("render cancelled: $operation/$attemptedRoute")
    }

/**
 * The production rendering boundary. Every document render resolves its route here,
 * executes selection layers through the same route, and reports the actual route.
 */
internal object EditorRenderer {
    suspend fun render(request: RenderRequest): RenderResult {
        val route =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    operation = request.operation,
                    assignedDocumentEngine = request.assignedDocumentEngine,
                    debugOverride = request.debugOverride,
                    storedRequestedRoute = request.storedRequestedRoute,
                    exactRoute = request.exactRoute,
                    storedDecision = request.storedDecision,
                    fallbackPolicy = request.fallbackPolicy,
                )
            )
        val started = System.nanoTime()
        return try {
            val output = renderRoute(request, route.primaryRoute)
            success(
                request = request,
                requestedRoute = route.requestedRoute,
                actualRoute = route.primaryRoute,
                decision = route.decision,
                usedDebugOverride = route.usedDebugOverride,
                output = output,
                startedNanos = started,
            )
        } catch (cancelled: CancellationException) {
            RenderResult.Cancelled(
                operation = request.operation,
                requestedRoute = route.requestedRoute,
                attemptedRoute = route.primaryRoute,
            )
        } catch (failure: V2RenderException) {
            if (!route.fallbackAllowed) {
                RenderResult.Failure(
                    operation = request.operation,
                    requestedRoute = route.requestedRoute,
                    attemptedRoute = route.primaryRoute,
                    kind = RenderFailureKind.NativeV2Failed,
                    message = failure.cause?.message ?: failure.message.orEmpty(),
                )
            } else {
                renderFallback(request, route, failure, started)
            }
        } catch (failure: BitmapAllocationRejectedException) {
            RenderResult.Failure(
                operation = request.operation,
                requestedRoute = route.requestedRoute,
                attemptedRoute = route.primaryRoute,
                kind = RenderFailureKind.AllocationRejected,
                message = failure.message ?: "Bitmap allocation rejected",
            )
        } catch (failure: Throwable) {
            RenderResult.Failure(
                operation = request.operation,
                requestedRoute = route.requestedRoute,
                attemptedRoute = route.primaryRoute,
                kind =
                    if (route.primaryRoute == NativeRenderRoute.V1)
                        RenderFailureKind.NativeV1Failed
                    else RenderFailureKind.Unexpected,
                message = failure.message ?: failure::class.java.simpleName,
            )
        }
    }

    private suspend fun renderFallback(
        request: RenderRequest,
        route: ResolvedNativeRoute,
        v2Failure: V2RenderException,
        startedNanos: Long,
    ): RenderResult =
        try {
            val output = renderRoute(request, NativeRenderRoute.V1)
            success(
                request = request,
                requestedRoute = route.requestedRoute,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.RuntimeFallbackToV1,
                usedDebugOverride = route.usedDebugOverride,
                output = output,
                startedNanos = startedNanos,
            )
        } catch (cancelled: CancellationException) {
            RenderResult.Cancelled(
                operation = request.operation,
                requestedRoute = route.requestedRoute,
                attemptedRoute = NativeRenderRoute.V1,
            )
        } catch (failure: Throwable) {
            RenderResult.Failure(
                operation = request.operation,
                requestedRoute = route.requestedRoute,
                attemptedRoute = NativeRenderRoute.V1,
                kind = RenderFailureKind.FallbackV1Failed,
                message = v2Failure.cause?.message ?: v2Failure.message.orEmpty(),
                fallbackFailureMessage = failure.message ?: failure::class.java.simpleName,
            )
        }

    private fun success(
        request: RenderRequest,
        requestedRoute: NativeRenderRoute,
        actualRoute: NativeRenderRoute,
        decision: RenderRouteDecision,
        usedDebugOverride: Boolean,
        output: Bitmap,
        startedNanos: Long,
    ): RenderResult.Success =
        resolveExecutedAlgorithmVersion(actualRoute, request.storedAlgorithmVersion).let { version ->
        RenderResult.Success(
            operation = request.operation,
            requestedRoute = requestedRoute,
            output = output,
            actualRoute = actualRoute,
            decision = decision,
            usedDebugOverride = usedDebugOverride,
            // Rendering always executes the current implementation. Historical metadata may
            // describe the source being migrated, but must never relabel these new pixels.
            algorithmVersion = version.executedVersion,
            migratedFromAlgorithmVersion = version.migratedFromVersion,
            participation = request.storedParticipation ?: RenderParticipation(),
            durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L,
            knownTransientBytes =
                BitmapMemoryBudget.saturatingMultiply(
                    BitmapMemoryBudget.bytes(output),
                    if (request.selectionLayers.any(SelectionLayer::enabled)) 2L else 1L,
                ),
        )
        }

    private suspend fun renderRoute(
        request: RenderRequest,
        route: NativeRenderRoute,
    ): Bitmap {
        val enabledLayers = request.selectionLayers.filter(SelectionLayer::enabled)
        if (enabledLayers.isEmpty()) {
            return renderEditedBitmap(
                basePreview = request.basePreview,
                params = request.params,
                engines = request.engines,
                revision = request.identity.revision,
                look = request.look,
                quickEffects = request.quickEffects,
                route = route,
                diagnostics = request.diagnostics,
            )
        }
        var global: Bitmap? = null
        try {
            global =
                renderEditedBitmap(
                    basePreview = request.basePreview,
                    params = request.params,
                    engines = request.engines,
                    revision = request.identity.revision,
                    look = request.look,
                    route = route,
                    diagnostics = request.diagnostics,
                )
            for (layer in enabledLayers) {
                var local: Bitmap? = null
                try {
                    local =
                        renderEditedBitmap(
                            basePreview = request.basePreview,
                            params = mergeSelectionParams(request.params, layer.localParams),
                            engines = request.engines,
                            revision = request.identity.revision,
                            look = request.look,
                            route = route,
                            diagnostics = request.diagnostics,
                        )
                    val blend =
                        NativePhotoCore.nativeBlendSelectionLayerInPlace(
                            target = checkNotNull(global),
                            local = local,
                            mask = layer.bitmap,
                            inverted = layer.inverted,
                            opacity = layer.opacity.coerceIn(0f, 1f),
                            diagnostics = request.diagnostics,
                        )
                    if (blend < 0) {
                        throw IllegalStateException("native selection blend failed: code=$blend")
                    }
                } finally {
                    local?.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }
            applyActiveQuickEffectsToBitmap(
                checkNotNull(global),
                request.quickEffects,
                request.identity.revision,
                request.diagnostics,
            )
            return checkNotNull(global).also { global = null }
        } finally {
            global?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }
}

private fun mergeSelectionParams(base: EditParams, local: EditParams): EditParams =
    EditParams(
        exposure = (base.exposure + local.exposure).coerceIn(-1f, 1f),
        contrast = (base.contrast + local.contrast).coerceIn(-1f, 1f),
        shadows = (base.shadows + local.shadows).coerceIn(-1f, 1f),
        highlights = (base.highlights + local.highlights).coerceIn(-1f, 1f),
        whites = (base.whites + local.whites).coerceIn(-1f, 1f),
        blacks = (base.blacks + local.blacks).coerceIn(-1f, 1f),
        temperature = (base.temperature + local.temperature).coerceIn(-1f, 1f),
        tint = (base.tint + local.tint).coerceIn(-1f, 1f),
        saturation = (base.saturation + local.saturation).coerceIn(-1f, 1f),
        vibrance = (base.vibrance + local.vibrance).coerceIn(-1f, 1f),
        clarity = (base.clarity + local.clarity).coerceIn(-1f, 1f),
        dehaze = (base.dehaze + local.dehaze).coerceIn(-1f, 1f),
        sharpness = (base.sharpness + local.sharpness).coerceIn(0f, 1f),
        noiseReduction = (base.noiseReduction + local.noiseReduction).coerceIn(0f, 1f),
        luminanceNoiseReduction =
            (base.luminanceNoiseReduction + local.luminanceNoiseReduction).coerceIn(0f, 1f),
        colorNoiseReduction =
            (base.colorNoiseReduction + local.colorNoiseReduction).coerceIn(0f, 1f),
        noiseDetailProtection =
            (base.noiseDetailProtection + local.noiseDetailProtection - 0.50f)
                .coerceIn(0f, 1f),
    )
