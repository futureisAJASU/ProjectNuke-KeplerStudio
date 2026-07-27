package com.projectnuke.keplerstudio.editor

import android.app.Application
import com.projectnuke.keplerstudio.ui.EditorDestination
import com.projectnuke.keplerstudio.ui.EditorSettingsNavigationPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        ExperimentalLabController.selectGlobalEngine(CorrectionEngine.Engine1)
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
        ExperimentalLabController.selectGlobalEngine(CorrectionEngine.Engine2)
        val selection = ExperimentalLabController.snapshot()
        val preview = RenderPipelinePlanner.create(selection, EditParams(), emptyList())
        val export = RenderPipelinePlanner.create(selection, EditParams(), emptyList())

        assertTrue(preview.usesV2)
        assertEquals(preview, export)
        assertEquals(NativeRenderRoute.V2, preview.route)
    }

    @Test
    fun engine1RoutesStableV1() {
        ExperimentalLabController.selectGlobalEngine(CorrectionEngine.Engine1)
        val plan =
            RenderPipelinePlanner.create(
                ExperimentalLabController.snapshot(),
                EditParams(),
                emptyList(),
            )
        assertFalse(plan.usesV2)
        assertEquals(NativeRenderRoute.V1, plan.route)
    }

    @Test
    fun engine2MissingModelUsesRuleAndNativeRoutes() {
        ExperimentalLabController.selectGlobalEngine(CorrectionEngine.Engine2)
        val selection = ExperimentalLabController.snapshot()

        assertEquals(FlareGuardRoute.V2Rule, selection.flareGuard)
        assertEquals(RemasterRoute.V2MaskAware, selection.remaster)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, selection.subjectSelection)
        assertFalse(correctionEngineStatus(CorrectionEngine.Engine2).contains("model-assisted"))
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
    fun settingsNavigationReturnsToPreviousEditorDestination() {
        assertEquals(
            EditorDestination.Editor,
            EditorSettingsNavigationPolicy.returnDestination(EditorDestination.Editor),
        )
        assertEquals(
            EditorDestination.Saved,
            EditorSettingsNavigationPolicy.returnDestination(EditorDestination.Saved),
        )
    }
}
