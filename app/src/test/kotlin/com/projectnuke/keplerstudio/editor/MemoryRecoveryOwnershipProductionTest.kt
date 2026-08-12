package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MemoryRecoveryOwnershipProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val application: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(installBitmapCopySeam = true, application = application)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun automaticRecoveryStaleBeforeCleanupLeavesReplacementUntouched() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1001L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token

        openDocument(editor, 0xff203040.toInt(), 1002L)
        editor.updateUiState { it.copy(message = "B message") }
        val releasesBeforeOldCompletion = nativeReleases
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerCloseCountForTest(oldToken) == 1 }

        assertEquals(0, seam.cleanupStarted)
        assertEquals(releasesBeforeOldCompletion, nativeReleases)
        assertEquals("B message", editor.uiState.value.message)
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
    }

    @Test
    fun documentReplacementInvalidatesPendingRecoveryDialog() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1101L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.memoryRecoveryTokenForTest())

        openDocument(editor, 0xff203040.toInt(), 1102L)

        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
    }

    @Test
    fun staleOldRecoveryDoesNotSuppressNewDocumentFailure() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1201L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token

        openDocument(editor, 0xff203040.toInt(), 1202L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val newToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        assertNotEquals(oldToken, newToken)

        seam.automaticRelease.complete(Unit)
        awaitEvent {
            editor.memoryRecoveryOwnerPhaseForTest() == "AwaitingUserDecision" &&
                editor.memoryRecoveryTokenForTest() == newToken
        }
        assertEquals(1, seam.cleanupStarted)
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(oldToken))
    }

    @Test
    fun systemTrimCleanupDoesNotSuppressForegroundRecovery() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1301L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        awaitEvent { seam.trimReached.isCompleted }
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
        assertTrue(editor.trimMemoryCleanupActiveForTest())

        seam.trimRelease.complete(Unit)
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
    }

    @Test
    fun oldCompletionCannotClearNewerRecoveryOwner() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1401L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token
        openDocument(editor, 0xff203040.toInt(), 1402L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val newToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryTokenForTest() == newToken && editor.uiState.value.memoryRecoveryRequest != null }

        assertEquals(newToken, editor.memoryRecoveryTokenForTest())
        assertEquals("AwaitingUserDecision", editor.memoryRecoveryOwnerPhaseForTest())
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(oldToken))
    }

    @Test
    fun reopenSameSourceAndTokenDoesNotMatchOldNavigationAttempt() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val current = navigationDescriptor("generation-B", "target-B")
            .copy(sourcePath = previous.sourcePath, baseContentToken = previous.baseContentToken)
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, current))
    }

    @Test
    fun navigationRetryIdentityRequiresExactTarget() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val sameTarget = previous.copy(token = 2L, requiredBytes = 99L)
        val otherTarget = previous.copy(token = 3L, targetEntryId = "target-B")
        assertTrue(memoryRetryAttemptMatchesFailureForTest(previous, sameTarget))
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, otherTarget))
    }

    @Test
    fun navigationRetryIdentityRequiresExactCoordinatorGeneration() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val otherGeneration = previous.copy(token = 2L, coordinatorGeneration = "generation-B")
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, otherGeneration))
    }

    @Test
    fun strongRetryStaleValidationStopsBeforeCleanup() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1501L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.memoryRecoveryTokenForTest())
        editor.updateUiState { it.copy(revision = it.revision + 1) }
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.retryPendingMemoryRecovery(token)
        assertFalse(seam.strongReached.isCompleted)
    }

    @Test
    fun shutdownClosesUserAndSystemRecoveryOwnership() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1601L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        awaitEvent { seam.trimReached.isCompleted }
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val token = checkNotNull(editor.memoryRecoveryTokenForTest())
        harness.clearViewModels()

        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
        assertNull(editor.memoryRecoveryTokenForTest())
        assertFalse(editor.trimMemoryCleanupActiveForTest())
    }

    private var nativeReleases: Int = 0

    private fun openDocument(editor: EditorViewModel, color: Int, session: Long) {
        awaitEvent { editor.startupInitCompletion.isCompleted && editor.canEnterEditorActionPure() }
        val seam =
            OpenImageTestSeam(
                sourceTransactionFactory = { app, _ ->
                    IncomingSourceTransaction(
                        app,
                        inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    )
                },
                decode = { bitmap(color) },
                nativeSessionFactory = { session },
                nativeSessionReleaser = { nativeReleases += 1 },
            )
        val handle = harness.ownSeam(OpenImageTestSeam.install(seam))
        editor.openImage(Uri.parse("content://memory-recovery/$session"))
        awaitEvent { !editor.uiState.value.isBusy && editor.uiState.value.previewBitmap != null }
        handle.close()
    }

    private fun navigationDescriptor(generation: String, target: String) =
        MemoryRetryDescriptor(
            token = 1L,
            action = MemoryRetryAction.HistoryUndo,
            requiredBytes = 1L,
            sourcePath = "/same/source.jpg",
            baseContentToken = "same-token",
            revision = 4,
            payload = "navigation",
            navigationDirection = true,
            targetEntryId = target,
            coordinatorGeneration = generation,
        )

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun awaitEvent(predicate: () -> Boolean) {
        repeat(20_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
