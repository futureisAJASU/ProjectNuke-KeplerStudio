package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route matrix test: every document engine × override combination.
 * Verifies that [RouteResolver] is the single source of truth and that debug
 * overrides are represented unambiguously (null = no override, explicit V1 = force V1).
 */
class RouteResolverMatrixTest {

    @Test
    fun engine1NoOverrideAlwaysV1() {
        val result = RouteResolver.resolveNativeRoute(CorrectionEngine.Engine1, null)
        assertEquals(NativeRenderRoute.V1, result.actualRoute)
        assertFalse(result.usedDebugOverride)
        assertNull(result.fallbackReason)
    }

    @Test
    fun engine2NoOverrideAlwaysV2() {
        val result = RouteResolver.resolveNativeRoute(CorrectionEngine.Engine2, null)
        assertEquals(NativeRenderRoute.V2, result.actualRoute)
        assertFalse(result.usedDebugOverride)
        assertNull(result.fallbackReason)
    }

    @Test
    fun engine2WithV1OverrideForcesV1() {
        val result = RouteResolver.resolveNativeRoute(CorrectionEngine.Engine2, NativeRenderRoute.V1)
        assertEquals(NativeRenderRoute.V1, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertEquals(FallbackReason.DebugForcedV1, result.fallbackReason)
    }

    @Test
    fun engine1WithV2OverrideForcesV2() {
        val result = RouteResolver.resolveNativeRoute(CorrectionEngine.Engine1, NativeRenderRoute.V2)
        assertEquals(NativeRenderRoute.V2, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertEquals(FallbackReason.DebugForcedV2, result.fallbackReason)
    }

    @Test
    fun engine1WithV1OverrideIsNoOp() {
        val result = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine1, NativeRenderRoute.V1
        )
        assertEquals(NativeRenderRoute.V1, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertNull(result.fallbackReason)
    }

    @Test
    fun engine2WithV2OverrideIsNoOp() {
        val result = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine2, NativeRenderRoute.V2
        )
        assertEquals(NativeRenderRoute.V2, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertNull(result.fallbackReason)
    }

    @Test
    fun compareOverrideIsNormalizedToV2() {
        for (engine in CorrectionEngine.entries) {
            val result = RouteResolver.resolveNativeRoute(
                engine, NativeRenderRoute.Compare
            )
            assertEquals(NativeRenderRoute.V2, result.actualRoute, "Compare engine=$engine")
            assertEquals(NativeRenderRoute.V2, result.requestedRoute)
        }
    }

    @Test
    fun flareRouteMatrix() {
        // Engine 1, no override → V1
        val e1_none = RouteResolver.resolveFlareRoute(CorrectionEngine.Engine1, null, false)
        assertEquals(FlareGuardRoute.V1, e1_none.actualRoute)
        assertFalse(e1_none.usedDebugOverride)

        // Engine 2, no override → V2Rule
        val e2_none = RouteResolver.resolveFlareRoute(CorrectionEngine.Engine2, null, false)
        assertEquals(FlareGuardRoute.V2Rule, e2_none.actualRoute)
        assertFalse(e2_none.usedDebugOverride)

        // Engine 2, V1 override → V1
        val e2_v1 = RouteResolver.resolveFlareRoute(CorrectionEngine.Engine2, FlareGuardRoute.V1, false)
        assertEquals(FlareGuardRoute.V1, e2_v1.actualRoute)

        // Engine 2, V2ModelAssisted, model unavailable → V2Rule fallback
        val e2_model_no_model = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, FlareGuardRoute.V2ModelAssisted, false
        )
        assertEquals(FlareGuardRoute.V2Rule, e2_model_no_model.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, e2_model_no_model.fallbackReason)

        // Engine 2, V2ModelAssisted, model available → V2ModelAssisted
        val e2_model_with_model = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, FlareGuardRoute.V2ModelAssisted, true
        )
        assertEquals(FlareGuardRoute.V2ModelAssisted, e2_model_with_model.actualRoute)
        assertNull(e2_model_with_model.fallbackReason)

        // Compare → normalized to V2Rule
        val e2_compare = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, FlareGuardRoute.Compare, false
        )
        assertEquals(FlareGuardRoute.V2Rule, e2_compare.actualRoute)

        // ForcedV1Fallback → V1
        val e2_forced = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, FlareGuardRoute.ForcedV1Fallback, false
        )
        assertEquals(FlareGuardRoute.V1, e2_forced.actualRoute)
        assertEquals(FlareGuardRoute.V1, e2_forced.requestedRoute)
        assertEquals(FallbackReason.DebugForcedV1, e2_forced.fallbackReason)
    }

    @Test
    fun remasterRouteMatrix() {
        val e1_none = RouteResolver.resolveRemasterRoute(CorrectionEngine.Engine1, null, false)
        assertEquals(RemasterRoute.V1, e1_none.actualRoute)

        val e2_none = RouteResolver.resolveRemasterRoute(CorrectionEngine.Engine2, null, false)
        assertEquals(RemasterRoute.V2MaskAware, e2_none.actualRoute)

        val e2_v1 = RouteResolver.resolveRemasterRoute(CorrectionEngine.Engine2, RemasterRoute.V1, false)
        assertEquals(RemasterRoute.V1, e2_v1.actualRoute)

        val e2_model_no_model = RouteResolver.resolveRemasterRoute(
            CorrectionEngine.Engine2, RemasterRoute.V2ModelAssisted, false
        )
        assertEquals(RemasterRoute.V2MaskAware, e2_model_no_model.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, e2_model_no_model.fallbackReason)

        val e2_model_with_model = RouteResolver.resolveRemasterRoute(
            CorrectionEngine.Engine2, RemasterRoute.V2ModelAssisted, true
        )
        assertEquals(RemasterRoute.V2ModelAssisted, e2_model_with_model.actualRoute)
        assertNull(e2_model_with_model.fallbackReason)

        val e2_compare = RouteResolver.resolveRemasterRoute(
            CorrectionEngine.Engine2, RemasterRoute.Compare, false
        )
        assertEquals(RemasterRoute.V2MaskAware, e2_compare.actualRoute)
    }

    @Test
    fun subjectRouteMatrix() {
        val e1_none = RouteResolver.resolveSubjectRoute(CorrectionEngine.Engine1, null, false)
        assertEquals(SubjectSelectionRoute.V1, e1_none.actualRoute)

        val e2_none = RouteResolver.resolveSubjectRoute(CorrectionEngine.Engine2, null, false)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, e2_none.actualRoute)

        val e2_v1 = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, SubjectSelectionRoute.V1, false
        )
        assertEquals(SubjectSelectionRoute.V1, e2_v1.actualRoute)

        val e2_model_no_model = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, SubjectSelectionRoute.V2ModelAssisted, false
        )
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, e2_model_no_model.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, e2_model_no_model.fallbackReason)

        val e2_model_with_model = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, SubjectSelectionRoute.V2ModelAssisted, true
        )
        assertEquals(SubjectSelectionRoute.V2ModelAssisted, e2_model_with_model.actualRoute)
        assertNull(e2_model_with_model.fallbackReason)

        val e2_compare = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, SubjectSelectionRoute.Compare, false
        )
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, e2_compare.actualRoute)
    }

    @Test
    fun defaultRoutesAreStable() {
        for (engine in CorrectionEngine.entries) {
            assertEquals(
                RouteResolver.defaultNativeRoute(engine),
                RouteResolver.resolveNativeRoute(engine, null).actualRoute
            )
            assertEquals(
                RouteResolver.defaultFlareRoute(engine),
                RouteResolver.resolveFlareRoute(engine, null, false).actualRoute
            )
            assertEquals(
                RouteResolver.defaultRemasterRoute(engine),
                RouteResolver.resolveRemasterRoute(engine, null, false).actualRoute
            )
            assertEquals(
                RouteResolver.defaultSubjectRoute(engine),
                RouteResolver.resolveSubjectRoute(engine, null, false).actualRoute
            )
        }
    }

    @Test
    fun legacySelectionFromResolverMatchesRoutingForCorrectionEngine() {
        for (engine in CorrectionEngine.entries) {
            val legacy = RouteResolver.toLegacySelection(engine, DebugFeatureOverrides.None, RouteModelAvailability())
            val direct = routingForCorrectionEngine(engine)
            assertEquals(direct, legacy, "engine=$engine")
        }
    }
}