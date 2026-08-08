package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before

/**
 * Regression tests for Experimental Lab model-assisted option enablement.
 *
 * Verifies that the Compose panel derives option enablement from the authoritative
 * canAttemptModelUse semantic, not the deprecated executable semantic.
 */
class ExperimentalLabEnablementRegressionTest {

    @Before
    fun resetRegistry() {
        ModelAvailabilityRegistry.resetForTest()
    }

    private fun seedCapability(phase: ModelCapabilityPhase, factsLoadable: Boolean = true) {
        val assetPresent = factsLoadable
        val assetValid = factsLoadable
        val runtimeAvailable = factsLoadable
        val contractSupported = factsLoadable
        val runnerImplemented = factsLoadable

        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = phase,
                assetPresent = assetPresent,
                assetValid = assetValid,
                runtimeAvailable = runtimeAvailable,
                contractSupported = contractSupported,
                runnerImplemented = runnerImplemented,
            ),
        )
    }

    private fun assertFlareModelReady(expected: Boolean) {
        val capability = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!
        val modelReady = capability.canAttemptModelUse == true
        assertEquals(expected, modelReady)
    }

    @Test
    fun loadableStateEnablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        assertFlareModelReady(true)
    }

    @Test
    fun readyStateWithLiveSessionEnablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        // Simulate session ready
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Session,
                generation = 1L,
                phase = ModelCapabilityPhase.Ready,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )
        assertFlareModelReady(true)
    }

    @Test
    fun unloadedWithValidFactsEnablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        // Close session -> Unloaded
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Session,
                generation = 1L,
                phase = ModelCapabilityPhase.Unloaded,
            ),
        )
        assertFlareModelReady(true)
    }

    @Test
    fun failedWithValidFactsEnablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        // Transient failure
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
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
        assertFlareModelReady(true)
    }

    @Test
    fun loadingStateDisablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Loader,
                generation = 1L,
                phase = ModelCapabilityPhase.Loading,
            ),
        )
        assertFlareModelReady(false)
    }

    @Test
    fun assetMissingDisablesModelAssistedOption() {
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.AssetMissing,
                assetPresent = false,
            ),
        )
        assertFlareModelReady(false)
    }

    @Test
    fun assetInvalidDisablesModelAssistedOption() {
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.AssetInvalid,
                assetPresent = true,
                assetValid = false,
            ),
        )
        assertFlareModelReady(false)
    }

    @Test
    fun runtimeUnavailableDisablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Loader,
                generation = 1L,
                phase = ModelCapabilityPhase.RuntimeUnavailable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = false,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )
        assertFlareModelReady(false)
    }

    @Test
    fun contractUnsupportedDisablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Loader,
                generation = 1L,
                phase = ModelCapabilityPhase.ContractUnsupported,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = false,
                runnerImplemented = true,
            ),
        )
        assertFlareModelReady(false)
    }

    @Test
    fun runnerUnavailableDisablesModelAssistedOption() {
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.RunnerUnavailable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = false,
            ),
        )
        assertFlareModelReady(false)
    }
}