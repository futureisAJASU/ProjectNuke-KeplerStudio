package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorHistoryStorageProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanHistoryRoot() {
        harness = OwnedEditorViewModelHarness(context)
        deletePath(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deletePath(draftGenerationsRoot(context))
    }

    @After
    fun cleanHistoryRootAfter() {
        harness.close()
        deletePath(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deletePath(draftGenerationsRoot(context))
    }

    private fun deletePath(path: File) {
        runCatching {
            if (path.isDirectory) path.deleteRecursively() else path.delete()
        }
    }

    @Test
    fun coldSnapshotWithZeroSelectionMasksPreflightsBeforeDecode() = runBlocking {
        val vm = editor(false)
        awaitReady(vm)
        val snapshot = checkNotNull(vm.captureCurrentHistorySnapshot(HistorySnapshotStorage.Exact))
        var preflightSeen = false
        var decodeBeforePreflight = false
        var publishFailure: Throwable? = null
        val storage = EditorHistoryStorage(
            context,
            Dispatchers.Unconfined,
            { decodeBeforePreflight = !preflightSeen },
            syncDirectories = false,
            publishFailureObserverForTest = { publishFailure = it },
            enforceDiskSpace = false,
        )
        val generation = "storage-zero-mask"
        val entry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
        storage.registerSession(generation)
        storage.initializeSession(generation)
        val payload = checkNotNull(storage.publish(entry, snapshot)) { publishFailure.toString() }
        entry.hotSnapshot = null
        entry.coldPayload = payload
        entry.payloadState = HistoryPayloadState.Hot

        val loaded = storage.loadWithSelectionMaskPreflight(
            entry,
            generation,
            { plan ->
                preflightSeen = true
                assertEquals(0L, plan.uniqueMaskBytes)
                assertEquals(0, plan.layerCount)
                AutoCloseable { }
            },
            { decodeBeforePreflight = !preflightSeen },
        )

        assertNotNull(loaded)
        assertTrue(preflightSeen)
        assertTrue(!decodeBeforePreflight)
        assertEquals(snapshot.bitmapBytes(), storage.requiredBitmapBytes(entry, generation))
        loaded?.recycleBitmaps()
        snapshot.recycleBitmaps()
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun coldSnapshotWithSelectionMaskUsesActualMaskAdmissionBeforeDecode() = runBlocking {
        val vm = editor(true)
        awaitReady(vm)
        val snapshot = checkNotNull(vm.captureCurrentHistorySnapshot(HistorySnapshotStorage.Exact))
        var preflightSeen = false
        var decoded = 0
        var publishFailure: Throwable? = null
        val storage = EditorHistoryStorage(
            context,
            Dispatchers.Unconfined,
            { decoded++ },
            syncDirectories = false,
            publishFailureObserverForTest = { publishFailure = it },
            enforceDiskSpace = false,
        )
        val generation = "storage-one-mask"
        val entry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
        storage.registerSession(generation)
        storage.initializeSession(generation)
        val payload = checkNotNull(storage.publish(entry, snapshot)) { publishFailure.toString() }
        entry.hotSnapshot = null
        entry.coldPayload = payload
        entry.payloadState = HistoryPayloadState.Hot

        val loaded = storage.loadWithSelectionMaskPreflight(
            entry,
            generation,
            { plan ->
                preflightSeen = true
                assertEquals(1, plan.layerCount)
                assertTrue(plan.uniqueMaskBytes > 0L)
                AutoCloseable { }
            },
            { decoded++ },
        )

        assertNotNull(loaded)
        assertTrue(preflightSeen)
        assertTrue(decoded > 0)
        loaded?.recycleBitmaps()
        snapshot.recycleBitmaps()
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun productionDraftSaveAndValidationAcceptsZeroSelectionMasks() = runBlocking {
        val source = context.cacheDir.resolve("draft-source.png")
        val sourceBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { output ->
                assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            sourceBitmap.recycle()
        }
        val vm = editor(false, source.absolutePath)
        awaitReady(vm)

        assertTrue(vm.persistDraftSnapshotNow())
        val validated = checkNotNull(validateCurrentDraftGeneration(context))
        assertTrue(validated.manifest.selectionLayers.isEmpty())
        assertEquals(0, validated.maskFiles.size)
        assertTrue(validated.sourceFile.isFile)
        assertTrue(validated.thumbnailFile.isFile)
        source.delete()
        Unit
    }

    private fun editor(withMask: Boolean, sourcePath: String = "history-storage-test"): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "history-storage-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
                selectionLayers = if (withMask) listOf(SelectionLayer("mask", "mask", SelectionLayerKind.Brush, mask)) else emptyList(),
                activeSelectionLayerId = if (withMask) "mask" else null,
            )
        }
        return vm
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(400) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        repeat(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.canEnterEditorAction()) return
            yieldToEditorBackgroundForTest()
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun corruptEntry(entry: EditorHistoryEntry, mutate: (org.json.JSONObject) -> Unit) {
        val payload = checkNotNull(entry.coldPayload)
        val manifest = org.json.JSONObject(File(payload.directory, "manifest.json").readText(Charsets.UTF_8))
        mutate(manifest)
        File(payload.directory, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
    }

    private suspend fun setupPublishedEntry(
        suffix: String,
        decodeObserver: ((File) -> Unit)? = null,
        withMask: Boolean = false,
    ): Triple<EditorHistoryStorage, String, EditorHistoryEntry> {
        val vm = editor(withMask, suffix)
        awaitReady(vm)
        val snapshot = checkNotNull(vm.captureCurrentHistorySnapshot(HistorySnapshotStorage.Exact))
        val storage = EditorHistoryStorage(
            context,
            Dispatchers.Unconfined,
            decodeObserver ?: { fail("decode should not happen") },
            syncDirectories = false,
            enforceDiskSpace = false,
        )
        val generation = suffix
        val entry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
        storage.registerSession(generation)
        storage.initializeSession(generation)
        val payload = checkNotNull(storage.publish(entry, snapshot))
        entry.hotSnapshot = null
        entry.coldPayload = payload
        entry.payloadState = HistoryPayloadState.Hot
        return Triple(storage, generation, entry)
    }

    private suspend fun assertColdHistoryRejectedBeforeDecode(
        suffix: String,
        mutateManifest: (org.json.JSONObject) -> Unit,
        mutateComplete: ((File) -> Unit)? = null,
        withMask: Boolean = false,
    ) {
        var decoded = 0
        val (storage, generation, entry) = setupPublishedEntry(
            suffix,
            decodeObserver = { decoded++ },
            withMask = withMask,
        )
        try {
            corruptEntry(entry, mutateManifest)
            mutateComplete?.invoke(File(checkNotNull(entry.coldPayload).directory, "COMPLETE"))
            val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
            assertNull(loaded)
            assertEquals(0, decoded)
        } finally {
            storage.deleteSession(generation)
            storage.unregisterSession(generation)
        }
    }

    @Test
    fun coldHistoryRejectsExtraPayloadBeforeDecode() = runBlocking {
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-extra")
        var decoded = 0
        corruptEntry(entry) { manifest ->
            val specs = manifest.getJSONArray("bitmaps")
            val last = specs.getJSONObject(specs.length() - 1)
            val extra = org.json.JSONObject()
            extra.put("key", "extra_payload")
            extra.put("file", "extra_payload.png")
            extra.put("width", last.getInt("width"))
            extra.put("height", last.getInt("height"))
            extra.put("config", "ARGB_8888")
            specs.put(extra)
        }
        val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
        assertNull(loaded)
        assertEquals(0, decoded)
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun coldHistoryRejectsMissingPayloadBeforeDecode() = runBlocking {
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-missing")
        var decoded = 0
        corruptEntry(entry) { manifest ->
            manifest.getJSONObject("metadata").put("previewKey", "nonexistent_bitmap_key")
        }
        val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
        assertNull(loaded)
        assertEquals(0, decoded)
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun coldHistoryRejectsDuplicateBitmapKeyBeforeDecode() = runBlocking {
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-dupkey")
        var decoded = 0
        corruptEntry(entry) { manifest ->
            val specs = manifest.getJSONArray("bitmaps")
            val first = specs.getJSONObject(0)
            val dup = org.json.JSONObject()
            dup.put("key", first.getString("key"))
            dup.put("file", "dup_file.png")
            dup.put("width", first.getInt("width"))
            dup.put("height", first.getInt("height"))
            dup.put("config", "ARGB_8888")
            specs.put(dup)
        }
        val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
        assertNull(loaded)
        assertEquals(0, decoded)
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun coldHistoryRejectsPathEscapeBeforeDecode() = runBlocking {
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-escape")
        var decoded = 0
        corruptEntry(entry) { manifest ->
            manifest.getJSONArray("bitmaps").getJSONObject(0).put("file", "../escape.png")
        }
        val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
        assertNull(loaded)
        assertEquals(0, decoded)
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }

    @Test
    fun coldHistoryRejectsDuplicateFilenameBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-dupfile", { manifest ->
            val specs = manifest.getJSONArray("bitmaps")
            specs.getJSONObject(1).put("file", specs.getJSONObject(0).getString("file"))
        }, withMask = true)
    }

    @Test
    fun coldHistoryRejectsConfigMismatchBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-config", { manifest ->
            manifest.getJSONArray("bitmaps").getJSONObject(0).put("config", "RGB_565")
        })
    }

    @Test
    fun coldHistoryRejectsManifestDimensionMismatchBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-dimension", { manifest ->
            val spec = manifest.getJSONArray("bitmaps").getJSONObject(0)
            spec.put("width", spec.getInt("width") + 1)
        })
    }

    @Test
    fun coldHistoryRejectsMaskGeometryMismatchBeforeDecode() = runBlocking {
        var decoded = 0
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-mask-geometry", decodeObserver = { decoded++ }, withMask = true)
        try {
            corruptEntry(entry) { manifest ->
                val specs = manifest.getJSONArray("bitmaps")
                val maskKey = manifest.getJSONObject("metadata").getJSONArray("layers").getJSONObject(0).getString("bitmapKey")
                for (index in 0 until specs.length()) {
                    val spec = specs.getJSONObject(index)
                    if (spec.getString("key") == maskKey) spec.put("width", spec.getInt("width") + 1)
                }
            }
            val loaded = storage.loadWithSelectionMaskPreflight(entry, generation, { AutoCloseable { } }, { decoded++ })
            assertNull(loaded)
            assertEquals(0, decoded)
        } finally {
            storage.deleteSession(generation)
            storage.unregisterSession(generation)
        }
    }

    @Test
    fun coldHistoryRejectsMissingCompleteMarkerBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-no-complete", { }, { it.delete() })
    }

    @Test
    fun coldHistoryRejectsInvalidCompleteMarkerBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-bad-complete", { }, { it.writeText("bad", Charsets.US_ASCII) })
    }

    @Test
    fun coldHistoryRejectsInvalidActiveSelectionBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-active-selection", { manifest ->
            manifest.getJSONObject("metadata").put("activeSelectionLayerId", "missing-layer")
        })
    }

    @Test
    fun coldHistoryRejectsExcessiveSelectionLayersBeforeDecode() = runBlocking {
        assertColdHistoryRejectedBeforeDecode("schema-reject-layer-count", { manifest ->
            val layers = manifest.getJSONObject("metadata").getJSONArray("layers")
            repeat(BitmapMemoryBudget.maxSelectionMaskLayers() + 1) { layers.put(org.json.JSONObject()) }
        })
    }

    @Test
    fun requiredBitmapBytesRejectsSchemaMismatch() = runBlocking {
        val (storage, generation, entry) = setupPublishedEntry("schema-reject-requiredbytes")
        corruptEntry(entry) { manifest ->
            val specs = manifest.getJSONArray("bitmaps")
            val last = specs.getJSONObject(specs.length() - 1)
            val extra = org.json.JSONObject()
            extra.put("key", "extra_payload")
            extra.put("file", "extra_extra.png")
            extra.put("width", last.getInt("width"))
            extra.put("height", last.getInt("height"))
            extra.put("config", "ARGB_8888")
            specs.put(extra)
        }
        val bytes = storage.requiredBitmapBytes(entry, generation)
        assertNull(bytes)
        storage.deleteSession(generation)
        storage.unregisterSession(generation)
    }
}
