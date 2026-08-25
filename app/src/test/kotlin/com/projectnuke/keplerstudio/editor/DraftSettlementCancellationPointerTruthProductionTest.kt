package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cancellation landing INSIDE the publication-settlement adoption window —
 * after the pointer durably persisted but before the ViewModel adopted it —
 * must restore the previous authoritative pointer (or clear it). Keeping the
 * unadopted pointer wedges every later save behind a pointer/baseline mismatch
 * until process death. Regression for the Phase-8 closure audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftSettlementCancellationPointerTruthProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        harness = OwnedEditorViewModelHarness(context)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        failures.attempt { deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3")) }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.throwIfAny()
    }

    private fun createTestBitmap(): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff00aa44.toInt())
        }

    private fun initEditorForDraft(sourceName: String): EditorViewModel {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        val source = File(context.cacheDir, "test_sources/source_$sourceName.img")
        source.parentFile?.mkdirs()
        val sourceBitmap = createTestBitmap()
        source.outputStream().use {
            check(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        sourceBitmap.recycle()
        harness.own(source)
        vm.updateUiState {
            it.copy(
                sourcePath = source.absolutePath,
                baseContentToken = "settlement-cancel-base-$sourceName",
                previewBitmap = createTestBitmap(),
                originalPreviewBitmap = createTestBitmap(),
            )
        }
        return vm
    }

    private fun launchSave(vm: EditorViewModel): Pair<CoroutineScope, Deferred<Boolean>> {
        val scope = CoroutineScope(Dispatchers.Default)
        return scope to scope.async { vm.persistDraftSnapshotNow() }
    }

    private fun <T> awaitDeferred(deferred: Deferred<T>, description: String): T {
        awaitEditorCompletionForTest(description, deferred, timeoutMillis = 15_000L)
        return runBlocking { deferred.await() }
    }

    private fun finishSave(vm: EditorViewModel, scope: CoroutineScope, save: Deferred<Boolean>): Boolean {
        return try {
            awaitDeferred(save, "draft save caller must complete").also {
                vm.acknowledgeEditorLeave()
            }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun currentPointer(): String? =
        DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }

    @Test
    fun cancellationInAdoptionWindowRestoresPreviousPointerAndKeepsSavesWorking() = runBlocking {
        val vm = initEditorForDraft("adoption-window-cancel")
        val first = launchSave(vm)
        assertTrue(finishSave(vm, first.first, first.second))
        val baseline = checkNotNull(currentPointer())

        val seam = DraftSaveTestSeam(
            parkAt = DraftSaveStage.BeforeSettlementAdoption,
            pointerPersistedGenerationId = CompletableDeferred(),
        )
        val seamHandle = harness.ownSeam(DraftSaveTestSeam.install(vm, seam))
        try {
            val cancelledScope = CoroutineScope(Dispatchers.Default)
            val cancelledSave = cancelledScope.async { vm.persistDraftSnapshotNow() }
            try {
                awaitSignal(seam.reached, "save must reach the settlement adoption boundary")
                val publishedId = checkNotNull(seam.pointerPersistedGenerationId?.await())
                assertEquals(
                    "pointer must be published but NOT yet adopted into the baseline",
                    publishedId,
                    currentPointer(),
                )
                assertEquals(baseline, vm.draftPointerBaseline)

                seam.cancelParkedOwner(CancellationException("test cancel"))
                runCatching { awaitDeferred(cancelledSave, "cancelled save caller must complete") }
                vm.acknowledgeEditorLeave()

                assertEquals(
                    "unadopted pointer must be rolled back to the authoritative baseline",
                    baseline,
                    currentPointer(),
                )
                assertFalse(
                    "rolled-back generation directory must not survive as an orphan",
                    draftGenerationsRoot(context).resolve(publishedId).exists(),
                )
            } finally {
                cancelledScope.cancel()
            }
        } finally {
            seam.releaseGate.complete(Unit)
            seamHandle.close()
        }

        val third = launchSave(vm)
        assertTrue(
            "a later save must succeed after cancellation rolled back the unadopted pointer",
            finishSave(vm, third.first, third.second),
        )
        val settled = checkNotNull(currentPointer())
        assertTrue(settled != baseline)
        assertNotNull(validatedCurrentGenerationOrNull())
        Unit
    }

    private suspend fun validatedCurrentGenerationOrNull() =
        DraftStorageCoordinator.withReadLock { validateCurrentDraftGeneration(context) }

    private fun awaitSignal(signal: CompletableDeferred<Unit>, description: String) {
        awaitEditorCompletionForTest(description, signal, timeoutMillis = 15_000L)
    }
}
