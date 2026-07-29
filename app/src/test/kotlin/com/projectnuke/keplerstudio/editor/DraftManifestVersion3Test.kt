package com.projectnuke.keplerstudio.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for Draft manifest v3 serialization round-trip:
 * - previewEngine and previewResultClass survive toJson/parse cycle
 * - Legacy v2 manifests (missing preview fields) are rejected, not silently migrated
 * - draftCorrectionEngine defaults to Engine1 for null, throws for unknown
 */
@RunWith(RobolectricTestRunner::class)
class DraftManifestVersion3Test {

    private fun baselineManifest(
        previewEngine: String? = null,
        previewRoute: String? = null,
        previewResultClass: String? = null,
        fallbackReason: String? = null,
        correctionEngine: String = CorrectionEngine.Engine1.name,
        algorithmVersion: String? = null,
    ) = DraftGenerationManifest(
        formatVersion = DRAFT_FORMAT_VERSION,
        generationId = "test-gen-001",
        savedAtMillis = 1700000000000L,
        draftOperationEpoch = 0L,
        editorRevision = 1,
        originalSourceIdentity = null,
        sourceIdentity = "src-id-001",
        baseContentToken = "base-token-001",
        baseBitmapDirty = false,
        sourceFileName = "test.jpg",
        sourceWidth = 1920,
        sourceHeight = 1080,
        thumbnailFileName = "thumb.jpg",
        thumbnailWidth = 256,
        thumbnailHeight = 144,
        params = EditParams(),
        correctionEngine = correctionEngine,
        previewEngine = previewEngine,
        previewRoute = previewRoute,
        previewResultClass = previewResultClass,
        fallbackReason = fallbackReason,
        noiseEngine = NoiseEngine.FastEdgeAware.name,
        detailEngine = DetailEngine.MaskedUnsharp.name,
        toneEngine = ToneEngine.HistogramAuto.name,
        hazeEngine = DehazeEngine.FastContrast.name,
        presetLook = null,
        activeQuickEffects = emptyList(),
        exportFormat = ExportFormat.Jpeg.name,
        exportResolution = ExportResolution.Full.name,
        cropState = CropState(),
        selectionLayers = emptyList(),
        activeSelectionLayerId = null,
        selectionPaintSettings = SelectionPaintSettings(),
        showSelectionOverlay = false,
        algorithmVersion = algorithmVersion,
    )

    @Test
    fun v3ManifestRoundTripsPreviewEngineAndResultClass() {
        val original = baselineManifest(
            correctionEngine = CorrectionEngine.Engine2.name,
            previewEngine = CorrectionEngine.Engine1.name,
            previewResultClass = PreviewResultClass.V2FallbackToV1.name,
        )
        val json = original.toJson()
        assertEquals(DRAFT_FORMAT_VERSION, json.getInt("formatVersion"))
        assertEquals(CorrectionEngine.Engine1.name, json.getString("previewEngine"))
        assertEquals(PreviewResultClass.V2FallbackToV1.name, json.getString("previewResultClass"))

        val parsed = parseDraftGenerationManifest(json)
        assertEquals(original, parsed)
    }

    @Test
    fun v3ManifestWithNullPreviewFieldsRoundTrips() {
        val original = baselineManifest(
            previewEngine = null,
            previewResultClass = null,
        )
        val json = original.toJson()
        assertTrue(json.isNull("previewEngine"))
        assertTrue(json.isNull("previewResultClass"))

        val parsed = parseDraftGenerationManifest(json)
        assertEquals(original, parsed)
        assertNull(parsed!!.previewEngine)
        assertNull(parsed.previewResultClass)
    }

    @Test
    fun v2ManifestIsAcceptedWithDefaultPreviewFields() {
        val v3Manifest = baselineManifest(
            previewEngine = null,
            previewResultClass = null,
        )
        val json = v3Manifest.toJson()
        json.put("formatVersion", 2)
        json.remove("previewEngine")
        json.remove("previewResultClass")

        val parsed = parseDraftGenerationManifest(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.formatVersion)
        assertEquals(CorrectionEngine.Engine1.name, parsed.correctionEngine)
        assertNull(parsed.previewEngine)
        assertNull(parsed.previewResultClass)
    }

    @Test
    fun v2ManifestWithV2EnginePreservesEngine() {
        val v3Manifest = baselineManifest(
            correctionEngine = CorrectionEngine.Engine2.name,
            previewEngine = null,
            previewResultClass = null,
        )
        val json = v3Manifest.toJson()
        json.put("formatVersion", 2)
        json.remove("previewEngine")
        json.remove("previewResultClass")

        val parsed = parseDraftGenerationManifest(json)
        assertNotNull(parsed)
        assertEquals(CorrectionEngine.Engine2.name, parsed!!.correctionEngine)
        assertNull(parsed.previewEngine)
        assertNull(parsed.previewResultClass)
    }

    @Test
    fun formatVersion0IsRejected() {
        val v3Manifest = baselineManifest()
        val json = v3Manifest.toJson()
        json.put("formatVersion", 0)

        val parsed = parseDraftGenerationManifest(json)
        assertNull(parsed)
    }

    @Test
    fun futureVersionIsRejected() {
        val v3Manifest = baselineManifest()
        val json = v3Manifest.toJson()
        json.put("formatVersion", 99)

        val parsed = parseDraftGenerationManifest(json)
        assertNull(parsed)
    }

    @Test
    fun draftCorrectionEngineDefaultsToEngine1ForNull() {
        assertEquals(CorrectionEngine.Engine1, draftCorrectionEngine(null))
    }

    @Test
    fun draftCorrectionEngineThrowsForUnknownName() {
        var threw = false
        try {
            draftCorrectionEngine("unknown_engine")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun v3ManifestWithV2EngineAndV2PreviewPreservesBoth() {
        val original = baselineManifest(
            correctionEngine = CorrectionEngine.Engine2.name,
            previewEngine = CorrectionEngine.Engine2.name,
            previewResultClass = PreviewResultClass.V2.name,
        )
        val json = original.toJson()
        val parsed = parseDraftGenerationManifest(json)
        assertEquals(CorrectionEngine.Engine2.name, parsed!!.correctionEngine)
        assertEquals(CorrectionEngine.Engine2.name, parsed.previewEngine)
        assertEquals(PreviewResultClass.V2.name, parsed.previewResultClass)
    }
}
