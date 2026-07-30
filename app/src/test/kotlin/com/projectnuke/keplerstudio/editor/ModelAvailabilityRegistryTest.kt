package com.projectnuke.keplerstudio.editor

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ModelAvailabilityRegistryTest {
    @Test
    fun hashValidityAloneDoesNotClaimExecutableModel() {
        ModelAvailabilityRegistry.reportLoad(
            ModelFeature.FlareGuard,
            ModelLoadResult.RuntimeUnavailable("LiteRT unavailable"),
        )

        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.FlareGuard)
        assertTrue(state.assetPresent)
        assertTrue(state.assetValid)
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
}
