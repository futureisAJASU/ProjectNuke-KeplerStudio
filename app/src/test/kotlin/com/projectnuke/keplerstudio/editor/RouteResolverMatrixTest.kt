package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteResolverMatrixTest {
    @Test
    fun nativeOperationsFollowAssignedDocumentEngine() {
        val e1 =
            RouteResolver.resolveNativeRoute(
                RouteRequest(RenderOperation.NativePreview, CorrectionEngine.Engine1)
            )
        val e2 =
            RouteResolver.resolveNativeRoute(
                RouteRequest(RenderOperation.SelectionLocal, CorrectionEngine.Engine2)
            )

        assertEquals(NativeRenderRoute.V1, e1.primaryRoute)
        assertEquals(NativeRenderRoute.V2, e2.primaryRoute)
        assertEquals(RenderRouteDecision.FollowDocument, e2.decision)
        assertTrue(e2.fallbackAllowed)
    }

    @Test
    fun engineSwitchUsesTargetEngineAndExplicitDebugDecision() {
        val toE2 =
            RouteResolver.resolveNativeRoute(
                RouteRequest(RenderOperation.EngineSwitch, CorrectionEngine.Engine2)
            )
        val forcedV1 =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    operation = RenderOperation.EngineSwitch,
                    assignedDocumentEngine = CorrectionEngine.Engine2,
                    debugOverride = NativeRenderRoute.V1,
                )
            )
        val forcedV2 =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    operation = RenderOperation.EngineSwitch,
                    assignedDocumentEngine = CorrectionEngine.Engine1,
                    debugOverride = NativeRenderRoute.V2,
                )
            )

        assertEquals(NativeRenderRoute.V2, toE2.primaryRoute)
        assertEquals(RenderRouteDecision.DebugForcedV1, forcedV1.decision)
        assertEquals(NativeRenderRoute.V1, forcedV1.primaryRoute)
        assertFalse(forcedV1.fallbackAllowed)
        assertEquals(RenderRouteDecision.DebugForcedV2, forcedV2.decision)
        assertEquals(NativeRenderRoute.V2, forcedV2.primaryRoute)
    }

    @Test
    fun storedVisibleTruthIgnoresDebugAndExportCannotFallback() {
        val export =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    operation = RenderOperation.ExportDirty,
                    assignedDocumentEngine = CorrectionEngine.Engine2,
                    exactRoute = NativeRenderRoute.V1,
                    fallbackPolicy = FallbackPolicy.NoFallback,
                )
            )

        assertEquals(NativeRenderRoute.V1, export.requestedRoute)
        assertEquals(NativeRenderRoute.V1, export.primaryRoute)
        assertEquals(RenderRouteDecision.StoredVisibleTruth, export.decision)
        assertFalse(export.usedDebugOverride)
        assertFalse(export.fallbackAllowed)
    }

    @Test
    fun noFallbackPolicyDisablesOrdinaryV2Fallback() {
        val route =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    operation = RenderOperation.HistoryMaterialization,
                    assignedDocumentEngine = CorrectionEngine.Engine2,
                    fallbackPolicy = FallbackPolicy.NoFallback,
                )
            )
        assertEquals(NativeRenderRoute.V2, route.primaryRoute)
        assertFalse(route.fallbackAllowed)
    }

    @Test
    fun featureModelRoutesUseExplicitDeterministicFallbacks() {
        val flare =
            RouteResolver.resolveFlareRoute(
                CorrectionEngine.Engine2,
                FlareGuardRoute.V2ModelAssisted,
                modelAvailable = false,
            )
        val remaster =
            RouteResolver.resolveRemasterRoute(
                CorrectionEngine.Engine2,
                RemasterRoute.V2ModelAssisted,
                modelAvailable = false,
            )
        val subject =
            RouteResolver.resolveSubjectRoute(
                CorrectionEngine.Engine2,
                SubjectSelectionRoute.V2ModelAssisted,
                modelAvailable = false,
            )

        assertEquals(FlareGuardRoute.V2Rule, flare.actualRoute)
        assertEquals(RemasterRoute.V2MaskAware, remaster.actualRoute)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, subject.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, flare.fallbackReason)
        assertEquals(FallbackReason.ModelUnavailable, remaster.fallbackReason)
        assertEquals(FallbackReason.ModelUnavailable, subject.fallbackReason)
    }

    @Test
    fun forcedFeatureV1IsOverrideNotRuntimeFailure() {
        val route =
            RouteResolver.resolveFlareRoute(
                CorrectionEngine.Engine2,
                FlareGuardRoute.ForcedV1Fallback,
                modelAvailable = false,
            )

        assertEquals(FlareGuardRoute.V1, route.actualRoute)
        assertNull(route.fallbackReason)
        assertTrue(route.usedDebugOverride)
    }
}
