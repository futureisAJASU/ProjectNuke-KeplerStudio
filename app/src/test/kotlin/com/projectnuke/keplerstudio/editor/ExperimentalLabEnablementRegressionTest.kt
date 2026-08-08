package com.projectnuke.keplerstudio.editor

import com.projectnuke.keplerstudio.ui.isModelAssistedOptionEnabled
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
 *
 * Uses the production helper [isModelAssistedOptionEnabled] consumed by
 * [EditorScreenV2.ExperimentalLabSettingsCard].
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

    private fun assertModelAssistedEnabled(feature: ModelFeature, expected: Boolean) {
        val capability = ModelAvailabilityRegistry.state.value[feature]!!
        val enabled = isModelAssistedOptionEnabled(capability)
        assertEquals(expected, enabled)
    }

    @Test
    fun loadableStateEnablesModelAssistedOption() {
        seedCapability(ModelCapabilityPhase.Loadable)
        assertModelAssistedEnabled(ModelFeature.FlareGuard, true)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, true)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, true)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, true)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
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
        assertModelAssistedEnabled(ModelFeature.FlareGuard, false)
    }
}