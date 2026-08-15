package com.projectnuke.keplerstudio.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DraftManifestVersion3Test {
    @Test
    fun realV1FixtureMigratesToEngine1VisibleTruth() {
        val parsed = parseFixture("drafts/draft_v1.json")

        assertEquals(1, parsed.formatVersion)
        assertEquals(CorrectionEngine.Engine1.name, parsed.correctionEngine)
        assertEquals(CorrectionEngine.Engine1.name, parsed.previewEngine)
        assertEquals(NativeRenderRoute.V1.name, parsed.previewRoute)
        assertEquals(NativeRenderRoute.V1.name, parsed.requestedRoute)
        assertEquals(PreviewResultClass.V1.name, parsed.previewResultClass)
        assertEquals(RenderRouteDecision.FollowDocument.name, parsed.renderDecision)
        assertEquals("native-v1", parsed.algorithmVersion)
        assertEquals(
            AlgorithmContracts.LEGACY_NATIVE_V1,
            parsed.algorithmContracts.nativeRenderContract,
        )
        assertEquals(0L, parsed.draftOperationEpoch)
        assertEquals(0, parsed.editorRevision)
    }

    @Test
    fun realV2PreEngineFixtureMigratesWithoutInventingV2Success() {
        val parsed = parseFixture("drafts/draft_v2.json")

        assertEquals(2, parsed.formatVersion)
        assertEquals(CorrectionEngine.Engine1.name, parsed.correctionEngine)
        assertNull(parsed.previewEngine)
        assertNull(parsed.previewRoute)
        assertNull(parsed.previewResultClass)
        assertNull(parsed.fallbackReason)
        assertNull(parsed.algorithmContracts.nativeRenderContract)
    }

    @Test
    fun realV3FallbackFixturePreservesRequestedAndActualTruth() {
        val parsed = parseFixture("drafts/draft_v3_fallback.json")

        assertEquals(CorrectionEngine.Engine2.name, parsed.correctionEngine)
        assertEquals(CorrectionEngine.Engine1.name, parsed.previewEngine)
        assertEquals(NativeRenderRoute.V2.name, parsed.requestedRoute)
        assertEquals(NativeRenderRoute.V1.name, parsed.previewRoute)
        assertEquals(PreviewResultClass.V2FallbackToV1.name, parsed.previewResultClass)
        assertEquals(RenderFallbackReason.V2RenderFailed.name, parsed.fallbackReason)
        assertEquals(RenderRouteDecision.RuntimeFallbackToV1.name, parsed.renderDecision)
        assertEquals(RenderParticipation(rule = true), parsed.renderParticipation)
        assertEquals(
            AlgorithmContracts.LEGACY_NATIVE_V1,
            parsed.algorithmContracts.nativeRenderContract,
        )
    }

    @Test
    fun currentManifestRoundTripsCompleteVisibleTruth() {
        val provenance =
            BakedFeatureProvenance(
                feature = BakedFeatureType.FlareGuard,
                operationId = "flare-1",
                sequence = 1L,
                requestedRoute = FlareGuardRoute.V2ModelAssisted.name,
                actualRoute = FlareGuardRoute.V2Rule.name,
                participation = RenderParticipation(rule = true),
                capabilityPhase = ModelCapabilityPhase.AssetMissing,
                outcome = FeatureExecutionOutcome.Fallback,
                fallbackReason = "asset missing",
                stageContract = AlgorithmContracts.FLARE_V2,
                timestampMillis = 1700000000000L,
            )
        val original =
            baselineManifest(
                correctionEngine = CorrectionEngine.Engine2.name,
                previewEngine = CorrectionEngine.Engine1.name,
                previewRoute = NativeRenderRoute.V1.name,
                requestedRoute = NativeRenderRoute.V2.name,
                previewResultClass = PreviewResultClass.V2FallbackToV1.name,
                fallbackReason = RenderFallbackReason.V2RenderFailed.name,
                renderDecision = RenderRouteDecision.RuntimeFallbackToV1.name,
                algorithmVersion = "native-v1",
                renderParticipation = RenderParticipation(rule = true),
            ).copy(
                algorithmContracts =
                    AlgorithmContractSet(
                        nativeRenderContract = AlgorithmContracts.NATIVE_V1,
                        flareGuardContract = AlgorithmContracts.FLARE_V2,
                    ),
                baseProvenance = BaseProvenanceChain(listOf(provenance)),
            )

        val restored = checkNotNull(parseDraftGenerationManifest(original.toJson()))
        assertEquals(original.toJson().toString(), restored.toJson().toString())
        assertEquals(original.algorithmContracts, restored.algorithmContracts)
        assertEquals(original.baseProvenance, restored.baseProvenance)
    }

    @Test
    fun unknownEngineAndPreviewEnumsRejectTheGeneration() {
        val json = baselineManifest().toJson()
        json.put("correctionEngine", "future-engine")
        json.put("previewEngine", "future-engine")
        json.put("previewRoute", "future-route")
        json.put("requestedRoute", "future-route")
        json.put("previewResultClass", "future-result")
        json.put("fallbackReason", "future-fallback")
        json.put("renderDecision", "future-decision")

        val parsed = parseDraftGenerationManifest(json)
        assertNull(parsed)
    }

    @Test
    fun malformedAndFutureManifestsAreRejected() {
        val malformed = baselineManifest().toJson().put("sourceWidth", 0)
        val future = baselineManifest().toJson().put("formatVersion", 99)

        assertNull(parseDraftGenerationManifest(malformed))
        assertNull(parseDraftGenerationManifest(future))
    }

    @Test
    fun futureStructuredContractSchemaCannotMasqueradeAsCurrent() {
        val json =
            JSONObject()
                .put("schemaVersion", AlgorithmContractSet.SCHEMA_VERSION + 1)
                .put("nativeRenderContract", AlgorithmContracts.NATIVE_V2)
        val parsed = parseAlgorithmContractSet(json, legacyVersion = null)
        val result =
            resolveExecutedAlgorithmVersion(
                NativeRenderRoute.V2,
                parsed.nativeRenderContract,
            )

        assertEquals(
            "unsupported-contract-schema-2:${AlgorithmContracts.NATIVE_V2}",
            parsed.nativeRenderContract,
        )
        assertEquals(AlgorithmContracts.NATIVE_V2, result.executedVersion)
        assertEquals(parsed.nativeRenderContract, result.migratedFromVersion)
    }

    @Test
    fun draftCorrectionEngineAlwaysFallsBackSafely() {
        assertEquals(CorrectionEngine.Engine1, draftCorrectionEngine(null))
        assertEquals(CorrectionEngine.Engine1, draftCorrectionEngine("unknown"))
        assertEquals(CorrectionEngine.Engine2, draftCorrectionEngine("Engine2"))
    }

    private fun parseFixture(path: String): DraftGenerationManifest {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(path))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        return checkNotNull(parseDraftGenerationManifest(JSONObject(text)))
    }

    private fun baselineManifest(
        correctionEngine: String = CorrectionEngine.Engine1.name,
        previewEngine: String? = null,
        previewRoute: String? = null,
        requestedRoute: String? = null,
        previewResultClass: String? = null,
        fallbackReason: String? = null,
        renderDecision: String? = null,
        algorithmVersion: String? = null,
        renderParticipation: RenderParticipation? = null,
    ) =
        DraftGenerationManifest(
            formatVersion = DRAFT_FORMAT_VERSION,
            generationId = "test-generation",
            savedAtMillis = 1700000000000L,
            draftOperationEpoch = 1L,
            editorRevision = 2,
            originalSourceIdentity = null,
            sourceIdentity = "source-id",
            baseContentToken = "base-token",
            baseBitmapDirty = false,
            sourceFileName = "source.img",
            sourceWidth = 1920,
            sourceHeight = 1080,
            thumbnailFileName = "thumbnail.jpg",
            thumbnailWidth = 256,
            thumbnailHeight = 144,
            params = EditParams(),
            correctionEngine = correctionEngine,
            previewEngine = previewEngine,
            previewRoute = previewRoute,
            requestedRoute = requestedRoute,
            previewResultClass = previewResultClass,
            fallbackReason = fallbackReason,
            renderDecision = renderDecision,
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
            showSelectionOverlay = true,
            algorithmVersion = algorithmVersion,
            renderParticipation = renderParticipation,
        )
}
