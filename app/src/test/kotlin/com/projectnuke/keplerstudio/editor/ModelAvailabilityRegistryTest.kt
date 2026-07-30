package com.projectnuke.keplerstudio.editor

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.Before
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
        ModelAvailabilityRegistry.reportEdgeSession(false, "asset missing")

        val availability = ModelAvailabilityRegistry.routeAvailability()
        val edge = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        assertEquals(ModelCapabilityPhase.Failed, edge.phase)
        assertEquals(null, edge.assetPresent)
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
}
