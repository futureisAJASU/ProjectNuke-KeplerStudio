package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CorrectionEngineStateTransitionTest {
    @Test
    fun openingDocumentStartsAsOriginalAndClearsPriorState() {
        val stale =
            CorrectionEngineState(
                documentEngine = CorrectionEngine.Engine2,
                pendingEngine = CorrectionEngine.Engine1,
                visiblePreview =
                    rendered(
                        requested = NativeRenderRoute.V2,
                        actual = NativeRenderRoute.V1,
                        decision = RenderRouteDecision.RuntimeFallbackToV1,
                    ),
                lastRenderFailure = failureResult().let {
                    CorrectionEngineState().withFailedRender(CorrectionEngine.Engine2, it)
                        .lastRenderFailure
                },
            )

        val opened = stale.forOpenedDocument(CorrectionEngine.Engine1)

        assertEquals(CorrectionEngine.Engine1, opened.documentEngine)
        assertEquals(PreviewResultClass.Original, opened.previewResultClass)
        assertTrue(opened.previewIsOriginal)
        assertNull(opened.previewEngine)
        assertNull(opened.pendingEngine)
        assertNull(opened.lastRenderFailure)
    }

    @Test
    fun successfulV2SettlementPublishesTruthAndClearsPending() {
        val state =
            CorrectionEngineState(
                documentEngine = CorrectionEngine.Engine1,
                pendingEngine = CorrectionEngine.Engine2,
                visiblePreview = VisiblePreviewState.Original,
            )
        val result =
            successResult(
                requested = NativeRenderRoute.V2,
                actual = NativeRenderRoute.V2,
                decision = RenderRouteDecision.FollowDocument,
            )

        val settled = state.withSuccessfulRender(CorrectionEngine.Engine2, result)

        assertEquals(CorrectionEngine.Engine2, settled.documentEngine)
        assertEquals(CorrectionEngine.Engine2, settled.previewEngine)
        assertEquals(NativeRenderRoute.V2, settled.previewRoute)
        assertEquals(PreviewResultClass.V2, settled.previewResultClass)
        assertFalse(settled.previewIsOriginal)
        assertNull(settled.pendingEngine)
        assertNull(settled.lastRenderFailure)
        result.output.recycle()
    }

    @Test
    fun runtimeFallbackIsDistinctFromDebugForcedV1() {
        val fallback =
            CorrectionEngineState(documentEngine = CorrectionEngine.Engine2)
                .withSuccessfulRender(
                    CorrectionEngine.Engine2,
                    successResult(
                        requested = NativeRenderRoute.V2,
                        actual = NativeRenderRoute.V1,
                        decision = RenderRouteDecision.RuntimeFallbackToV1,
                    ),
                )
        val forced =
            CorrectionEngineState(documentEngine = CorrectionEngine.Engine2)
                .withSuccessfulRender(
                    CorrectionEngine.Engine2,
                    successResult(
                        requested = NativeRenderRoute.V1,
                        actual = NativeRenderRoute.V1,
                        decision = RenderRouteDecision.DebugForcedV1,
                    ),
                )

        assertEquals(PreviewResultClass.V2FallbackToV1, fallback.previewResultClass)
        assertEquals(RenderFallbackReason.V2RenderFailed, fallback.fallbackReason)
        assertTrue(fallback.usedFallback)
        assertEquals(PreviewResultClass.DebugForcedV1, forced.previewResultClass)
        assertNull(forced.fallbackReason)
        assertFalse(forced.usedFallback)
    }

    @Test
    fun debugForcedV2NeverBecomesForcedV1() {
        val result =
            successResult(
                requested = NativeRenderRoute.V2,
                actual = NativeRenderRoute.V2,
                decision = RenderRouteDecision.DebugForcedV2,
            )
        val state =
            CorrectionEngineState(documentEngine = CorrectionEngine.Engine1)
                .withSuccessfulRender(CorrectionEngine.Engine1, result)

        assertEquals(PreviewResultClass.DebugForcedV2, state.previewResultClass)
        assertEquals(CorrectionEngine.Engine2, state.previewEngine)
        result.output.recycle()
    }

    @Test
    fun failurePreservesVisiblePreviewAndSettlesPending() {
        val visible =
            rendered(
                requested = NativeRenderRoute.V1,
                actual = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
            )
        val state =
            CorrectionEngineState(
                documentEngine = CorrectionEngine.Engine2,
                pendingEngine = CorrectionEngine.Engine2,
                visiblePreview = visible,
            )

        val failed = state.withFailedRender(CorrectionEngine.Engine2, failureResult())

        assertEquals(visible, failed.visiblePreview)
        assertEquals(PreviewResultClass.V1, failed.previewResultClass)
        assertNotNull(failed.lastRenderFailure)
        assertNull(failed.pendingEngine)
    }

    @Test
    fun laterV2SuccessClearsFallbackAndFailure() {
        val fallback =
            CorrectionEngineState(
                documentEngine = CorrectionEngine.Engine2,
                visiblePreview =
                    rendered(
                        requested = NativeRenderRoute.V2,
                        actual = NativeRenderRoute.V1,
                        decision = RenderRouteDecision.RuntimeFallbackToV1,
                    ),
            ).withFailedRender(CorrectionEngine.Engine2, failureResult())
        val success =
            successResult(
                requested = NativeRenderRoute.V2,
                actual = NativeRenderRoute.V2,
                decision = RenderRouteDecision.FollowDocument,
            )

        val recovered = fallback.withSuccessfulRender(CorrectionEngine.Engine2, success)

        assertEquals(PreviewResultClass.V2, recovered.previewResultClass)
        assertNull(recovered.fallbackReason)
        assertNull(recovered.lastRenderFailure)
        success.output.recycle()
    }

    private fun rendered(
        requested: NativeRenderRoute,
        actual: NativeRenderRoute,
        decision: RenderRouteDecision,
    ) =
        VisiblePreviewState.Rendered(
            requestedRoute = requested,
            actualRoute = actual,
            decision = decision,
            algorithmVersion = if (actual == NativeRenderRoute.V2) "native-v2" else "native-v1",
        )

    private fun successResult(
        requested: NativeRenderRoute,
        actual: NativeRenderRoute,
        decision: RenderRouteDecision,
    ) =
        RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = requested,
            output = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            actualRoute = actual,
            decision = decision,
            usedDebugOverride =
                decision == RenderRouteDecision.DebugForcedV1 ||
                    decision == RenderRouteDecision.DebugForcedV2,
            algorithmVersion = if (actual == NativeRenderRoute.V2) "native-v2" else "native-v1",
            participation = RenderParticipation(),
            durationMillis = 1L,
            knownTransientBytes = 4L,
        )

    private fun failureResult() =
        RenderResult.Failure(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V2,
            attemptedRoute = NativeRenderRoute.V2,
            kind = RenderFailureKind.NativeV2Failed,
            message = "simulated failure",
        )
}
