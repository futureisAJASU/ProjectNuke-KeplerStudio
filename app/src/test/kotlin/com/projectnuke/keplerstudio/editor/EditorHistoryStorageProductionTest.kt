package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorHistoryStorageProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanHistoryRoot() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanHistoryRootAfter() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
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
        val vm = EditorViewModel(context)
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
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            Thread.sleep(5)
        }
        assertTrue(vm.canEnterEditorAction())
    }
}
