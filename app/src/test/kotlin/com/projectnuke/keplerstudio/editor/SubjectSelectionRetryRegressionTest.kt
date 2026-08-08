package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before

/**
 * Regression tests for Subject Selection retry behavior.
 *
 * Verifies that a retryable Failed capability preserves the model-assisted route
 * instead of falling back solely because the previous load failed.
 */
class SubjectSelectionRetryRegressionTest {

    @Before
    fun resetRegistry() {
        ModelAvailabilityRegistry.resetForTest()
    }

    /**
     * SubjectSelection capability = Failed + factsLoadable
     * Requested route = V2ModelAssisted
     *
     * Production action must NOT reject/fallback solely because previous load failed.
     * Route resolution must return V2ModelAssisted when canAttemptModelUse is true.
     */
    @Test
    fun subjectSelectionRetryableFailedPreservesModelAssistedRoute() {
        // Seed valid capability through real Probe
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )

        // Simulate transient load failure
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Loader,
                generation = 1L,
                phase = ModelCapabilityPhase.Failed,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
                failure = ModelCapabilityFailure(ModelCapabilityPhase.Failed, "transient"),
            ),
        )

        val state = ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]!!
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)

        // Verify route resolution uses canAttemptModelUse
        val resolution = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2,
            SubjectSelectionRoute.V2ModelAssisted,
            modelAvailable = true, // canAttemptModelUse == true
        )

        assertEquals(SubjectSelectionRoute.V2ModelAssisted, resolution.actualRoute)
        assertNull(resolution.fallbackReason)
    }

    /**
     * SubjectSelection capability = structurally invalid (AssetMissing)
     * Requested route = V2ModelAssisted
     *
     * Production action MUST reject/fallback because capability is genuinely unavailable.
     */
    @Test
    fun subjectSelectionStructurallyUnavailableFallsBack() {
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.AssetMissing,
                assetPresent = false,
            ),
        )

        val state = ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]!!
        assertEquals(ModelCapabilityPhase.AssetMissing, state.phase)
        assertFalse(state.canAttemptModelUse)

        // Route resolution should fall back when modelAvailable is false
        val resolution = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2,
            SubjectSelectionRoute.V2ModelAssisted,
            modelAvailable = false, // canAttemptModelUse == false
        )

        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, resolution.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, resolution.fallbackReason)
    }

    /**
     * Verify that RouteModelAvailability uses canAttemptModelUse for subjectSelectionModelAvailable.
     * This is the derived state consumed by Experimental Lab and production actions.
     */
    @Test
    fun routeAvailabilityReflectsCanAttemptModelUse() {
        // Test Loadable
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )
        assertTrue(ModelAvailabilityRegistry.routeAvailability().subjectSelectionModelAvailable)

        // Test Failed + factsLoadable (retryable)
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Loader,
                generation = 1L,
                phase = ModelCapabilityPhase.Failed,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
                failure = ModelCapabilityFailure(ModelCapabilityPhase.Failed, "transient"),
            ),
        )
        assertTrue(ModelAvailabilityRegistry.routeAvailability().subjectSelectionModelAvailable)

        // Test AssetMissing (non-retryable)
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.SubjectSelection,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 2L,
                phase = ModelCapabilityPhase.AssetMissing,
                assetPresent = false,
            ),
        )
        assertFalse(ModelAvailabilityRegistry.routeAvailability().subjectSelectionModelAvailable)
    }
}