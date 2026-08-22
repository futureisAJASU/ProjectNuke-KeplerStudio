package com.projectnuke.keplerstudio.editor

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import java.util.concurrent.CountDownLatch
import org.junit.Test

class ModelAvailabilityRegistryTest {
    @Before
    fun resetRegistry() {
        ModelAvailabilityRegistry.resetForTest()
    }

    @After
    fun resetRegistryAfter() {
        // These tests mutate the process-global registry without starting a
        // model session. Reset at the boundary so the next Robolectric class in
        // the same worker observes a clean registry (clean test contract).
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
        assertFalse(state.canAttemptModelUse)
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
    fun validatedCapabilityTokenBecomesStaleWhenAProbeStarts() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val token =
            (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
                as ModelLoadResult.Ready).runner
        assertTrue(ModelAvailabilityRegistry.isCurrent(token))

        ModelAvailabilityRegistry.beginProbe()

        assertFalse(ModelAvailabilityRegistry.isCurrent(token))
    }

    @Test
    fun olderValidationEpochStaysStaleAfterNewerProbeSucceeds() {
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val tokenA =
            (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.Remaster)
                as ModelLoadResult.Ready).runner

        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val tokenB =
            (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.Remaster)
                as ModelLoadResult.Ready).runner

        assertFalse(ModelAvailabilityRegistry.isCurrent(tokenA))
        assertTrue(ModelAvailabilityRegistry.isCurrent(tokenB))
        assertTrue(tokenA.validationGeneration < tokenB.validationGeneration)
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

    @Test
    fun validatedLoadableCanAttemptModelUse() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertTrue(state.canAttemptModelUse)
    }

    @Test
    fun readyLiveSessionReportsSessionReady() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportSessionReady(listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertTrue(state.sessionReady)
        assertTrue(state.canAttemptModelUse)
    }

    @Test
    fun unloadedValidFactsRemainsAttemptable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val sessionGen = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionGeneration
        ModelAvailabilityRegistry.reportSessionClosed(listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection), sessionGen)
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertTrue(state.phase == ModelCapabilityPhase.Unloaded || state.phase == ModelCapabilityPhase.Loadable)
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
    }

    @Test
    fun failedWithValidFactsIsRetryable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("transient failure"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("이전 로드 실패 · 다시 시도 가능", state.statusLabel)
    }

    @Test
    fun failedWithIncompleteFactsIsNotRetryable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetMissing("missing"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.AssetMissing, state.phase)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun assetMissingIsNotRetryable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetMissing("missing"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun assetInvalidIsNotRetryable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetInvalid("invalid"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun runtimeUnavailableIsNotRetryable() {
        ModelAvailabilityRegistry.reportLoad(ModelFeature.FlareGuard, ModelLoadResult.RuntimeUnavailable("unavailable"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.FlareGuard)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun contractUnsupportedIsNotRetryable() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.UnsupportedContract("unsupported"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun runnerUnavailableIsNotRetryable() {
        // Use a state with missing runnerImplemented
        val observation = ModelCapabilityObservation(
            publisher = ModelCapabilityPublisher.Probe,
            generation = 1L,
            phase = ModelCapabilityPhase.RunnerUnavailable,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = false,
        )
        ModelAvailabilityRegistry.applyForTest(ModelFeature.FlareGuard, observation)
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.FlareGuard)
        assertEquals(ModelCapabilityPhase.RunnerUnavailable, state.phase)
        assertFalse(state.canAttemptModelUse)
    }

    @Test
    fun loaderGateAllowsRetryableFailed() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("transient"))
        assertEquals(null, ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster))
    }

    @Test
    fun validatedTokenCanBeIssuedAfterRetryableFailed() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("transient"))
        val tokenResult = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.Remaster)
        assertTrue(tokenResult is ModelLoadResult.Ready)
    }

    @Test
    fun validationEpochEnforcedOnRetry() {
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val token = (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.Remaster) as ModelLoadResult.Ready).runner
        ModelAvailabilityRegistry.beginProbe()
        assertFalse(ModelAvailabilityRegistry.isCurrent(token))
    }

    @Test
    fun newLoadingTransitionDoesNotReportReady() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("failed"))
        val stateBefore = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertTrue(stateBefore.canAttemptModelUse)
        assertTrue(stateBefore.factsLoadable)
        val loadGen = ModelAvailabilityRegistry.reportEdgeLoading()
        val stateLoading = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Loading, stateLoading.phase)
        assertFalse(stateLoading.sessionReady)
        assertFalse(stateLoading.canAttemptModelUse)
    }

    @Test
    fun retrySuccessClearsPreviousFailureAndBecomesReady() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("first"))
        val failState = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Failed, failState.phase)
        assertTrue(failState.canAttemptModelUse)
        assertTrue(failState.factsLoadable)
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val readyState = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Loadable, readyState.phase)
        assertTrue(readyState.factsLoadable)
        assertEquals(null, readyState.lastFailure)
    }

    @Test
    fun repeatedRetryFailureStaysRetryableWhenFactsValid() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("first"))
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("second"))
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)
    }

    @Test
    fun lateLoaderFailedCannotDowngradeActiveSessionReady() {
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
        val afterLateFailure =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Loader,
                    generation = 14L,
                    phase = ModelCapabilityPhase.Failed,
                    failure = ModelCapabilityFailure(ModelCapabilityPhase.Failed, "late loader result"),
                ),
                sequence = 2L,
            )
        assertEquals(ModelCapabilityPhase.Ready, afterLateFailure.phase)
        assertTrue(afterLateFailure.sessionActive)
        assertTrue(afterLateFailure.sessionReady)
        assertTrue(afterLateFailure.canAttemptModelUse)
        assertEquals(null, afterLateFailure.lastFailure)
    }

    @Test
    fun staleSessionCloseStillCannotCloseNewerSession() {
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
                    publisher = ModelCapabilityPublisher.Session,
                    8L,
                    ModelCapabilityPhase.Unloaded,
                ),
                sequence = 2L,
            )
        assertEquals(ready, staleClose)
    }

    @Test
    fun currentSessionCloseSettlesBackToLoadable() {
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
                    5L,
                    ModelCapabilityPhase.Ready,
                ),
                sequence = 1L,
            )
        val sessionGen = ready.sessionGeneration
        val closed =
            reduceModelCapability(
                ready,
                ModelCapabilityObservation(
                    publisher = ModelCapabilityPublisher.Session,
                    sessionGen,
                    ModelCapabilityPhase.Unloaded,
                ),
                sequence = 2L,
            )
        assertFalse(closed.sessionActive)
        assertEquals(ModelCapabilityPhase.Loadable, closed.phase)
        assertTrue(closed.factsLoadable)
        assertTrue(closed.canAttemptModelUse)
        assertFalse(closed.sessionReady)
    }

    @Test
    fun edgeMaskerRemasterAndSubjectSelectionSynchronized() {
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val remaster = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        val subject = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertEquals(remaster.phase, subject.phase)
        assertEquals(remaster.sessionActive, subject.sessionActive)
        assertEquals(remaster.factsLoadable, subject.factsLoadable)
        assertEquals(remaster.canAttemptModelUse, subject.canAttemptModelUse)
    }
}
