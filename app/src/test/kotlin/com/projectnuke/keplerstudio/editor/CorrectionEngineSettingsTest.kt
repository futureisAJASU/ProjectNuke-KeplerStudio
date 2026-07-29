package com.projectnuke.keplerstudio.editor

import android.app.Application
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
        ExperimentalLabController.resetForTest()
    }

    @Test
    fun unknownOrMissingPreferenceUsesEngine1() {
        assertEquals(CorrectionEngine.Engine1, CorrectionEngineSettings.decode(null))
        assertEquals(CorrectionEngine.Engine1, CorrectionEngineSettings.decode("future-value"))
    }

    @Test
    fun defaultEnginePersistsWithoutChangingDocumentState() {
        val document =
            CorrectionEngineState(
                defaultEngine = CorrectionEngine.Engine1,
                documentEngine = CorrectionEngine.Engine1,
                visiblePreview = VisiblePreviewState.Original,
            )

        settings.write(CorrectionEngine.Engine2)

        assertEquals(CorrectionEngine.Engine2, CorrectionEngineSettings(app).read())
        assertEquals(CorrectionEngine.Engine1, document.documentEngine)
        assertEquals(PreviewResultClass.Original, document.previewResultClass)
    }

    @Test
    fun operationIdentityRejectsEveryStaleDimension() {
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
    fun rawOverridesRemainIndependentAndNullable() {
        assertEquals(DebugFeatureOverrides.None, ExperimentalLabController.debugOverrides())

        ExperimentalLabController.updateDebugOverrides {
            it.copy(flareGuard = FlareGuardRoute.V1)
        }
        val first = ExperimentalLabController.debugOverrides()
        assertEquals(FlareGuardRoute.V1, first.flareGuard)
        assertEquals(null, first.nativeRender)
        assertEquals(null, first.remaster)
        assertEquals(null, first.subjectSelection)

        ExperimentalLabController.updateDebugOverrides {
            it.copy(subjectSelection = SubjectSelectionRoute.V2ManualOrSynthetic)
        }
        val second = ExperimentalLabController.debugOverrides()
        assertEquals(FlareGuardRoute.V1, second.flareGuard)
        assertEquals(SubjectSelectionRoute.V2ManualOrSynthetic, second.subjectSelection)
        assertEquals(null, second.nativeRender)
        assertEquals(null, second.remaster)
    }
}
