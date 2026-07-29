package com.projectnuke.keplerstudio.editor

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CorrectionEngineSettingsTest {
    private val app = RuntimeEnvironment.getApplication() as Application
    private val settings = CorrectionEngineSettings(app)

    @After
    fun reset() {
        settings.write(CorrectionEngine.Engine1)
        ExperimentalLabController.resetForTest()
    }

    @Test
    fun defaultIsEngine1() {
        assertEquals(CorrectionEngine.Engine1, CorrectionEngineSettings.decode(null))
        assertEquals(CorrectionEngine.Engine1, CorrectionEngineSettings.decode("future-value"))
        assertEquals(CorrectionEngine.Engine1, draftCorrectionEngine(null))
    }

    @Test
    fun engineSelectionPersistsAcrossStoreInstances() {
        settings.write(CorrectionEngine.Engine2)
        assertEquals(CorrectionEngine.Engine2, CorrectionEngineSettings(app).read())
        settings.write(CorrectionEngine.Engine1)
        assertEquals(CorrectionEngine.Engine1, CorrectionEngineSettings(app).read())
    }

    @Test
    fun engine2RoutesPreviewAndExportThroughTheSameV2Plan() {
        val selection = routingForCorrectionEngine(CorrectionEngine.Engine2)
        val preview = RenderPipelinePlanner.create(selection, EditParams(), emptyList())
        val export = RenderPipelinePlanner.create(selection, EditParams(), emptyList())

        assertTrue(preview.usesV2)
        assertEquals(preview, export)
        assertEquals(NativeRenderRoute.V2, preview.route)
    }

    @Test
    fun engine1RoutesStableV1() {
        val plan =
            RenderPipelinePlanner.create(
                routingForCorrectionEngine(CorrectionEngine.Engine1),
                EditParams(),
                emptyList(),
            )
        assertFalse(plan.usesV2)
        assertEquals(NativeRenderRoute.V1, plan.route)
    }

    @Test
    fun engine2MissingModelUsesRuleAndNativeRoutes() {
        val selection = routingForCorrectionEngine(CorrectionEngine.Engine2)

        assertEquals(FlareGuardRoute.V2Rule, selection.flareGuard)
        assertEquals(RemasterRoute.V2MaskAware, selection.remaster)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, selection.subjectSelection)
        assertFalse(routingForCorrectionEngine(CorrectionEngine.Engine2).flareGuard == FlareGuardRoute.V2ModelAssisted)
    }

    @Test
    fun staleEngineOrDocumentCannotMatchSwitchIdentity() {
        val identity =
            CorrectionEngineOperationIdentity(
                engineEpoch = 7,
                documentGeneration = "document-a",
                baseContentToken = "base-a",
                revision = 12,
            )
        assertTrue(identity.matches(7, "document-a", "base-a", 12))
        assertFalse(identity.matches(8, "document-a", "base-a", 12))
        assertFalse(identity.matches(7, "document-b", "base-a", 12))
        assertFalse(identity.matches(7, "document-a", "base-b", 12))
        assertFalse(identity.matches(7, "document-a", "base-a", 13))
    }

    @Test
    fun routeResolverNoOverrideFollowsDocumentEngine() {
        val e1 = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine1, null
        )
        assertEquals(NativeRenderRoute.V1, e1.actualRoute)
        assertFalse(e1.usedDebugOverride)
        assertNull(e1.fallbackReason)

        val e2 = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine2, null
        )
        assertEquals(NativeRenderRoute.V2, e2.actualRoute)
        assertFalse(e2.usedDebugOverride)
        assertNull(e2.fallbackReason)
    }

    @Test
    fun routeResolverExplicitV1OverrideForcesV1OnEngine2() {
        val result = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine2, NativeRenderRoute.V1
        )
        assertEquals(NativeRenderRoute.V1, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertEquals(FallbackReason.DebugForcedV1, result.fallbackReason)
    }

    @Test
    fun routeResolverExplicitV2OverrideForcesV2OnEngine1() {
        val result = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine1, NativeRenderRoute.V2
        )
        assertEquals(NativeRenderRoute.V2, result.actualRoute)
        assertTrue(result.usedDebugOverride)
        assertEquals(FallbackReason.DebugForcedV2, result.fallbackReason)
    }

    @Test
    fun routeResolverCompareIsNotAProductionRoute() {
        val result = RouteResolver.resolveNativeRoute(
            CorrectionEngine.Engine2, NativeRenderRoute.Compare
        )
        assertEquals(NativeRenderRoute.V2, result.actualRoute)
    }

    @Test
    fun routeResolverFlareModelUnavailableDowngrades() {
        val result = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, FlareGuardRoute.V2ModelAssisted, modelAvailable = false
        )
        assertEquals(FlareGuardRoute.V2Rule, result.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, result.fallbackReason)
    }

    @Test
    fun routeResolverFlareNoOverrideFollowsDocumentEngine() {
        val e1 = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine1, null, modelAvailable = false
        )
        assertEquals(FlareGuardRoute.V1, e1.actualRoute)
        assertFalse(e1.usedDebugOverride)

        val e2 = RouteResolver.resolveFlareRoute(
            CorrectionEngine.Engine2, null, modelAvailable = false
        )
        assertEquals(FlareGuardRoute.V2Rule, e2.actualRoute)
        assertFalse(e2.usedDebugOverride)
    }

    @Test
    fun routeResolverRemasterModelUnavailableDowngradesToManual() {
        val result = RouteResolver.resolveRemasterRoute(
            CorrectionEngine.Engine2, RemasterRoute.V2ModelAssisted, modelAvailable = false
        )
        assertEquals(RemasterRoute.V2MaskAware, result.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, result.fallbackReason)
    }

    @Test
    fun routeResolverSubjectNoOverrideFollowsDocumentEngine() {
        val e1 = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine1, null, modelAvailable = false
        )
        assertEquals(SubjectSelectionRoute.V1, e1.actualRoute)

        val e2 = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, null, modelAvailable = false
        )
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, e2.actualRoute)
    }

    @Test
    fun routeResolverSubjectModelUnavailableDowngradesToManual() {
        val result = RouteResolver.resolveSubjectRoute(
            CorrectionEngine.Engine2, SubjectSelectionRoute.V2ModelAssisted, modelAvailable = false
        )
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, result.actualRoute)
        assertEquals(FallbackReason.ModelUnavailable, result.fallbackReason)
    }

    @Test
    fun debugOverrideNoOverrideMeansFollowEngine() {
        ExperimentalLabController.resetForTest()
        val overrides = ExperimentalLabController.debugOverrides()
        assertEquals(DebugFeatureOverrides.None, overrides)

        val resolved = ExperimentalLabController.resolvedSelection(CorrectionEngine.Engine2)
        assertEquals(NativeRenderRoute.V2, resolved.nativeRender)
        assertEquals(FlareGuardRoute.V2Rule, resolved.flareGuard)
    }

    @Test
    fun debugOverrideSingleFeatureDoesNotModifyOthers() {
        ExperimentalLabController.resetForTest()
        ExperimentalLabController.updateDebugOverrides {
            it.copy(flareGuard = FlareGuardRoute.V1)
        }
        val resolved = ExperimentalLabController.resolvedSelection(CorrectionEngine.Engine2)
        assertEquals(FlareGuardRoute.V1, resolved.flareGuard)
        assertEquals(NativeRenderRoute.V2, resolved.nativeRender)
        assertEquals(RemasterRoute.V2MaskAware, resolved.remaster)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, resolved.subjectSelection)
    }
}
