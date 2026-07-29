package com.projectnuke.keplerstudio.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for [CorrectionEngineState] state transitions:
 * - [withSuccessfulRender] correctly classifies V1, V2, and V2-fallback-to-V1
 * - [withFailedRender] preserves the old preview class and records the failure
 * - Fallback state is cleared when a subsequent V2 render succeeds
 * - The effective preview engine drives routing decisions
 */
class CorrectionEngineStateTransitionTest {

    @After
    fun tearDown() {
        ExperimentalLabController.resetForTest()
    }

    @Test
    fun successfulV2RenderOnEngine2DocumentProducesV2ResultClass() {
        val state = CorrectionEngineState(
            documentEngine = CorrectionEngine.Engine2,
            previewResultClass = PreviewResultClass.NoDocument,
        )
        val updated = state.withSuccessfulRender(
            documentEngine = CorrectionEngine.Engine2,
            route = NativeRenderRoute.V2,
            debugOverrideActive = false,
        )
        assertEquals(PreviewResultClass.V2, updated.previewResultClass)
        assertEquals(CorrectionEngine.Engine2, updated.previewEngine)
        assertEquals(NativeRenderRoute.V2, updated.previewRoute)
        assertNull(updated.fallbackReason)
        assertFalse(updated.usedFallback)
        assertFalse(updated.debugOverrideActive)
    }

    @Test
    fun successfulV1RenderOnEngine2DocumentProducesV2FallbackToV1() {
        val state = CorrectionEngineState(
            documentEngine = CorrectionEngine.Engine2,
            previewResultClass = PreviewResultClass.NoDocument,
        )
        val updated = state.withSuccessfulRender(
            documentEngine = CorrectionEngine.Engine2,
            route = NativeRenderRoute.V1,
            debugOverrideActive = false,
        )
        assertEquals(PreviewResultClass.V2FallbackToV1, updated.previewResultClass)
        assertEquals(CorrectionEngine.Engine1, updated.previewEngine)
        assertEquals(NativeRenderRoute.V1, updated.previewRoute)
        assertEquals(RenderFallbackReason.V2RenderFailed, updated.fallbackReason)
        assertTrue(updated.usedFallback)
    }

    @Test
    fun successfulV1RenderOnEngine1DocumentProducesV1ResultClass() {
        val state = CorrectionEngineState(
            documentEngine = CorrectionEngine.Engine1,
            previewResultClass = PreviewResultClass.NoDocument,
        )
        val updated = state.withSuccessfulRender(
            documentEngine = CorrectionEngine.Engine1,
            route = NativeRenderRoute.V1,
            debugOverrideActive = false,
        )
        assertEquals(PreviewResultClass.V1, updated.previewResultClass)
        assertEquals(CorrectionEngine.Engine1, updated.previewEngine)
        assertNull(updated.fallbackReason)
        assertFalse(updated.usedFallback)
    }

    @Test
    fun subsequentV2SuccessClearsOldFallback() {
        val fallbackState = CorrectionEngineState(
            documentEngine = CorrectionEngine.Engine2,
            previewEngine = CorrectionEngine.Engine1,
            previewResultClass = PreviewResultClass.V2FallbackToV1,
            fallbackReason = RenderFallbackReason.V2RenderFailed,
        )
        assertTrue(fallbackState.usedFallback)

        val recovered = fallbackState.withSuccessfulRender(
            documentEngine = CorrectionEngine.Engine2,
            route = NativeRenderRoute.V2,
            debugOverrideActive = false,
        )
        assertEquals(PreviewResultClass.V2, recovered.previewResultClass)
        assertEquals(CorrectionEngine.Engine2, recovered.previewEngine)
        assertNull(recovered.fallbackReason)
        assertFalse(recovered.usedFallback)
    }

    @Test
    fun failedRenderPreservesOldPreviewClassAndRecordsFailure() {
        val v1State = CorrectionEngineState(
            documentEngine = CorrectionEngine.Engine1,
            previewEngine = CorrectionEngine.Engine1,
            previewResultClass = PreviewResultClass.V1,
        )
        val failed = v1State.withFailedRender(
            operation = "test",
            requestedEngine = CorrectionEngine.Engine1,
            requestedRoute = NativeRenderRoute.V1,
            reason = "simulated failure",
        )
        assertEquals(PreviewResultClass.V1, failed.previewResultClass)
        assertNotNull(failed.lastRenderFailure)
        assertEquals("simulated failure", failed.lastRenderFailure!!.reason)
        assertNull(failed.pendingEngine)
    }

    @Test
    fun failedRenderOnNoDocumentTransitionsToFailed() {
        val noDocState = CorrectionEngineState(
            previewResultClass = PreviewResultClass.NoDocument,
        )
        val failed = noDocState.withFailedRender(
            operation = "test",
            requestedEngine = CorrectionEngine.Engine2,
            requestedRoute = NativeRenderRoute.V2,
            reason = "V2 unavailable",
        )
        assertEquals(PreviewResultClass.Failed, failed.previewResultClass)
        assertNotNull(failed.lastRenderFailure)
    }

    @Test
    fun isSwitchingTrueWhenPendingEngineIsNonNull() {
        val switching = CorrectionEngineState(
            pendingEngine = CorrectionEngine.Engine2,
        )
        assertTrue(switching.isSwitching)

        val notSwitching = CorrectionEngineState(
            pendingEngine = null,
        )
        assertFalse(notSwitching.isSwitching)
    }

    @Test
    fun previewIsCleanTrueForV1V2AndOriginal() {
        assertTrue(CorrectionEngineState(previewResultClass = PreviewResultClass.V1).previewIsClean)
        assertTrue(CorrectionEngineState(previewResultClass = PreviewResultClass.V2).previewIsClean)
        assertTrue(CorrectionEngineState(previewResultClass = PreviewResultClass.Original).previewIsClean)
        assertFalse(CorrectionEngineState(previewResultClass = PreviewResultClass.V2FallbackToV1).previewIsClean)
        assertFalse(CorrectionEngineState(previewResultClass = PreviewResultClass.Failed).previewIsClean)
        assertFalse(CorrectionEngineState(previewResultClass = PreviewResultClass.NoDocument).previewIsClean)
    }
}
