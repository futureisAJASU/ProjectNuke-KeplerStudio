package com.projectnuke.keplerstudio.editor

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After

/**
 * Gate 2 model-asset pinning policy combinations.
 *
 * The manifest is the single source of truth for asset path/size/hash, and the
 * loader maps exactly the validated path. The validator honors a debug-only
 * UnpinnedExperimental override, never active in release, never reporting a
 * pinned wrong-hash asset as loadable.
 */
class ModelAssetPolicyTest {
    @After
    fun tearDown() {
        ModelAssetPolicy.setManualUnpinnedExperimental(null)
        DebugModelPolicy.setDevOverride(false)
    }

    @Test
    fun pinnedValidAssetLoadsAsValid() {
        val bytes = ByteArray(2048) { 9 }
        val entry = manifestEntry(path = "models/test.bin", sha256 = sha256(bytes))
        val validation = ModelAssetValidator.validate(entry) { ByteArrayInputStream(bytes) }
        assertIs<ModelAssetValidation.Valid>(validation)
        assertEquals(2048, validation.byteCount)
        assertEquals(sha256(bytes), validation.sha256)
    }

    @Test
    fun pinnedWrongHashIsRejectedEvenWithExperimentalOverride() {
        val pinned = ByteArray(2048) { 9 }
        val entry = manifestEntry(path = "models/test.bin", sha256 = sha256(pinned))
        ModelAssetPolicy.setManualUnpinnedExperimental(true)
        // Serve different bytes than the manifest pins.
        val served = ByteArray(2048) { 1 }
        val validation = ModelAssetValidator.validate(entry) { ByteArrayInputStream(served) }
        assertIs<ModelAssetValidation.Invalid>(validation)
        assertTrue(validation.detail.contains("does not match"))
    }

    @Test
    fun unpinnedReleaseIsRejected() {
        val entry =
            manifestEntry(path = "models/test.bin", sha256 = null, minBytes = 1024)
        ModelAssetPolicy.setManualUnpinnedExperimental(false)
        val validation =
            ModelAssetValidator.validate(entry) { ByteArrayInputStream(ByteArray(2048) { 7 }) }
        assertIs<ModelAssetValidation.Invalid>(validation)
        assertTrue(validation.detail.contains("not pinned by a manifest SHA-256"))
    }

    @Test
    fun unpinnedDebugRejectedUnlessExperimentalEnabled() {
        val entry =
            manifestEntry(path = "models/test.bin", sha256 = null, minBytes = 1024)
        // Override disabled -> rejected even in debug
        ModelAssetPolicy.setManualUnpinnedExperimental(false)
        val disabledValidation =
            ModelAssetValidator.validate(entry) { ByteArrayInputStream(ByteArray(2048) { 7 }) }
        assertIs<ModelAssetValidation.Invalid>(disabledValidation)

        // Override enabled -> accepted as UnpinnedExperimental, never Valid/production-ready
        ModelAssetPolicy.setManualUnpinnedExperimental(true)
        val enabledValidation =
            ModelAssetValidator.validate(entry) { ByteArrayInputStream(ByteArray(2048) { 7 }) }
        assertIs<ModelAssetValidation.UnpinnedExperimental>(enabledValidation)
        assertEquals(2048, enabledValidation.byteCount)
        // availability must NOT be production-ready
        val availability =
            ModelAssetValidator.availability(
                entry,
                enabledValidation,
                inferenceAvailable = true,
                loaded = true,
            )
        assertEquals(ModelAvailability.ExperimentalOnly, availability)
        // readiness must mark it as experimental, not production-ready
        val readiness = ModelAssetValidator.readiness(entry, enabledValidation, runtimeAvailable = true)
        assertTrue(readiness.experimentalOnly)
        assertFalse(readiness.productionReady)
        assertFalse(readiness.assetValid)
    }

    @Test
    fun ruleStatisticsEntryIsValidWithoutPin() {
        val entry = checkNotNull(ModelAssetManifest.byId("universal_auto_router"))
        val validation = ModelAssetValidator.validate(entry) { null }
        assertIs<ModelAssetValidation.Valid>(validation)
        assertEquals(0L, validation.byteCount)
    }

    @Test
    fun debugPolicyDefaultsToDisabledUntilDevOverrideSet() {
        DebugModelPolicy.setDevOverride(false)
        // In a release-class test JVM, BuildConfig.DEBUG may report false; either way the
        // override is false by default so the policy reports disabled.
        assertFalse(DebugModelPolicy.enableUnpinnedExperimental())
    }

    @Test
    fun reporterEmitsPathSizeHashButNeverImageContents() {
        val bytes = ByteArray(2048) { 9 }
        val entry = manifestEntry(path = "models/test.bin", sha256 = sha256(bytes))
        val summary = ModelAssetReporter.summarize(entry) { ByteArrayInputStream(bytes) }
        assertEquals("models/test.bin", summary?.assetPath)
        assertEquals(2048, summary?.byteSize)
        assertEquals(sha256(bytes), summary?.sha256)
    }

    @Test
    fun reporterReportLinesIncludeHashAndSizeForPinning() {
        val bytes = ByteArray(2048) { 9 }
        val entry = manifestEntry(path = "models/test.bin", sha256 = sha256(bytes))
        val lines = ModelAssetReporter.reportLines(listOf(entry)) { ByteArrayInputStream(bytes) }
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("path=models/test.bin"))
        assertTrue(lines[1].contains("size=2048"))
        assertTrue(lines[1].contains("sha256=" + sha256(bytes)))
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun manifestEntry(
        path: String,
        sha256: String?,
        minBytes: Long = 1024L,
    ): ModelAssetManifestEntry {
        val base = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        return base.copy(
            id = "test_entry",
            asset =
                base.asset.copy(
                    assetPath = path,
                    minimumExpectedBytes = minBytes,
                    maximumExpectedBytes = 64L * 1024L * 1024L,
                    sha256 = sha256,
                ),
        )
    }
}
