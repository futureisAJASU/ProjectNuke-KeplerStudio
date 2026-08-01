package com.projectnuke.keplerstudio.editor

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.Before
import java.util.concurrent.CountDownLatch
import org.junit.Test

class ModelAvailabilityRegistryTest {
    @Before
    fun resetRegistry() {
        ModelAvailabilityRegistry.resetForTest()
    }

    @Test
    fun hashValidityAloneDoesNotClaimExecutableModel() {
        ModelAvailabilityRegistry.reportLoad(
            ModelFeature.FlareGuard,
            ModelLoadResult.RuntimeUnavailable("LiteRT unavailable"),
        )

        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.FlareGuard)
        assertTrue(state.assetPresent == true)
        assertTrue(state.assetValid == true)
        assertEquals(ModelCapabilityPhase.RuntimeUnavailable, state.phase)
        assertFalse(state.executable)
        assertFalse(ModelAvailabilityRegistry.routeAvailability().flareGuardModelAvailable)
    }

    @Test
    fun readyRunnerFeedsTheSameRouteAvailabilityUsedByTheLab() {
        ModelAvailabilityRegistry.reportLoad(
            ModelFeature.FlareGuard,
            ModelLoadResult.Ready(Unit),
        )

        assertTrue(ModelAvailabilityRegistry.routeAvailability().flareGuardModelAvailable)
    }

    @Test
    fun missingEdgeSessionDoesNotDisableManualRoutes() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetMissing("asset missing"))

        val availability = ModelAvailabilityRegistry.routeAvailability()
        val edge = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.AssetMissing, edge.phase)
        assertEquals(false, edge.assetPresent)
        assertFalse(availability.remasterModelAvailable)
        assertFalse(availability.subjectSelectionModelAvailable)
        assertTrue(
            RouteResolver.resolveRemasterRoute(
                CorrectionEngine.Engine2,
                RemasterRoute.V2MaskAware,
                availability.remasterModelAvailable,
            ).actualRoute == RemasterRoute.V2MaskAware
        )
    }

    @Test
    fun edgeLoadPublishesOneAtomicSnapshotForBothFeatures() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val snapshot = ModelAvailabilityRegistry.snapshot()
        assertEquals(
            snapshot.capabilities[ModelFeature.Remaster],
            snapshot.capabilities[ModelFeature.SubjectSelection],
        )
        assertTrue(snapshot.routeAvailability().remasterModelAvailable)
        assertTrue(snapshot.routeAvailability().subjectSelectionModelAvailable)
    }

    @Test
    fun concurrentFlareAndEdgeReportsDoNotLoseUnrelatedCapability() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        Thread {
            start.await()
            repeat(100) {
                ModelAvailabilityRegistry.reportLoad(
                    ModelFeature.FlareGuard,
                    ModelLoadResult.Ready(Unit),
                )
            }
            done.countDown()
        }.start()
        Thread {
            start.await()
            repeat(100) {
                ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
            }
            done.countDown()
        }.start()

        start.countDown()
        done.await()

        val availability = ModelAvailabilityRegistry.snapshot().routeAvailability()
        assertTrue(availability.flareGuardModelAvailable)
        assertTrue(availability.remasterModelAvailable)
        assertTrue(availability.subjectSelectionModelAvailable)
    }

    @Test
    fun staleProbeCannotDowngradeReadySession() {
        val ready =
            reduceModelCapability(
                ModelCapabilityState(
                    phase = ModelCapabilityPhase.Loadable,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                ),
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Session,
                    generation = 7L,
                    phase = ModelCapabilityPhase.Ready,
                ),
                sequence = 1L,
            )

        val afterProbe =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Probe,
                    generation = 1L,
                    phase = ModelCapabilityPhase.Loadable,
                ),
                sequence = 2L,
            )

        assertEquals(ModelCapabilityPhase.Ready, afterProbe.phase)
        assertEquals(7L, afterProbe.sessionGeneration)
    }

    @Test
    fun currentSessionClosePreservesFactsAndOlderCloseIsIgnored() {
        val ready =
            ModelCapabilityState(
                phase = ModelCapabilityPhase.Ready,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
                sessionGeneration = 9L,
            )
        val staleClose =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Session,
                    8L,
                    ModelCapabilityPhase.Unloaded,
                ),
                sequence = 2L,
            )
        assertEquals(ready, staleClose)

        val closed =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Session,
                    9L,
                    ModelCapabilityPhase.Unloaded,
                ),
                sequence = 3L,
            )
        assertEquals(ModelCapabilityPhase.Loadable, closed.phase)
        assertTrue(closed.factsLoadable)
    }

    @Test
    fun loadFailurePreservesKnownAssetAndRuntimeFacts() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("creation failed"))

        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.assetPresent == true)
        assertTrue(state.assetValid == true)
        assertTrue(state.runtimeAvailable == true)
        assertTrue(state.contractSupported == true)
    }

    @Test
    fun loaderGateReturnsExactRegistryRejectionCategories() {
        assertTrue(ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster)
            is ModelLoadResult.RuntimeUnavailable)

        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetMissing("missing"))
        assertTrue(ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster)
            is ModelLoadResult.AssetMissing)

        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetInvalid("invalid"))
        assertTrue(ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster)
            is ModelLoadResult.AssetInvalid)

        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.UnsupportedContract("contract"))
        assertTrue(ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster)
            is ModelLoadResult.UnsupportedContract)
    }

    @Test
    fun loadableRegistryCapabilityAuthorizesLoader() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertEquals(null, ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster))
        assertEquals(null, ModelAvailabilityRegistry.loaderRejection(ModelFeature.SubjectSelection))
    }
    @Test
    fun lateLoaderCannotDowngradeOrFailAnActiveReadySession() {
        val ready =
            reduceModelCapability(
                ModelCapabilityState(
                    phase = ModelCapabilityPhase.Loadable,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                    loadGeneration = 12L,
                ),
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Session,
                    generation = 21L,
                    phase = ModelCapabilityPhase.Ready,
                ),
                sequence = 1L,
            )

        val afterLateLoadable =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Loader,
                    generation = 13L,
                    phase = ModelCapabilityPhase.Loadable,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                ),
                sequence = 2L,
            )
        val afterLateFailure =
            reduceModelCapability(
                afterLateLoadable,
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Loader,
                    generation = 14L,
                    phase = ModelCapabilityPhase.Failed,
                    failure =
                        ModelCapabilityFailure(
                            ModelCapabilityPhase.Failed,
                            "late loader result",
                        ),
                ),
                sequence = 3L,
            )

        assertEquals(ModelCapabilityPhase.Ready, afterLateFailure.phase)
        assertTrue(afterLateFailure.sessionActive)
        assertEquals(null, afterLateFailure.lastFailure)
        assertEquals(14L, afterLateFailure.loadGeneration)
    }

    @Test
    fun currentSessionCloseEndsProtectionButPreservesLoadFacts() {
        val ready =
            reduceModelCapability(
                ModelCapabilityState(
                    phase = ModelCapabilityPhase.Loadable,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                ),
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Session,
                    5L,
                    ModelCapabilityPhase.Ready,
                ),
                sequence = 1L,
            )
        val closed =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Session,
                    5L,
                    ModelCapabilityPhase.Unloaded,
                ),
                sequence = 2L,
            )

        assertFalse(closed.sessionActive)
        assertEquals(ModelCapabilityPhase.Loadable, closed.phase)
        assertTrue(closed.factsLoadable)
    }

}
