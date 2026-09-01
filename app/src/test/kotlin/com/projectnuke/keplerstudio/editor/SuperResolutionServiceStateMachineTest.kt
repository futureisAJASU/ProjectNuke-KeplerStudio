package com.projectnuke.keplerstudio.editor

import android.content.Intent
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SuperResolutionServiceStateMachineTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        SuperResolutionForegroundPromotionSeam.resetForTest()
        SuperResolutionServiceLaunchSeam.resetForTest()
        SuperResolutionServiceExecutionSeam.resetForTest()
        SuperResolutionServiceStartSeam.resetForTest()
        SuperResolutionOperationRegistry.resetForTest()
        context.getSharedPreferences(SuperResolutionMediaProcessingService.JOURNAL_PREFS, 0).edit().clear().commit()
    }

    @Test
    fun launchBindsCancelOwnerBeforeTheExecutionCoroutineCanStart() {
        val request = request(91L)
        assertEquals(SuperResolutionStartResult.Started(91L), SuperResolutionOperationRegistry.admitForTest(request))
        val service = Robolectric.buildService(SuperResolutionMediaProcessingService::class.java).create().get()
        var observedBoundOwner = false
        SuperResolutionForegroundPromotionSeam.start = { _, _, _, _ -> }
        SuperResolutionServiceLaunchSeam.beforeJobStart = { operationId ->
            observedBoundOwner = SuperResolutionOperationRegistry.isOwner(operationId)
            assertTrue(SuperResolutionOperationRegistry.cancel(operationId))
        }
        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 91L),
            0,
            91,
        )
        assertTrue(observedBoundOwner)
        service.onDestroy()
    }

    @Test
    fun foregroundPromotionFailureReleasesTheClaim() {
        val request = request(92L)
        assertEquals(SuperResolutionStartResult.Started(92L), SuperResolutionOperationRegistry.admitForTest(request))
        SuperResolutionForegroundPromotionSeam.start = { _, _, _, _ -> throw SecurityException("denied") }
        val service = Robolectric.buildService(SuperResolutionMediaProcessingService::class.java).create().get()
        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 92L),
            0,
            92,
        )
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertEquals(SuperResolutionExportPhase.Failed, SuperResolutionOperationRegistry.state.value.status.phase)
        service.onDestroy()
    }

    @Test
    fun staleStartDoesNotStopAServiceWithAnotherActiveOwner() {
        val request = request(93L)
        assertEquals(SuperResolutionStartResult.Started(93L), SuperResolutionOperationRegistry.admitForTest(request))
        assertTrue(SuperResolutionOperationRegistry.claim(93L) != null)
        assertTrue(SuperResolutionOperationRegistry.bindOwner(93L) {})
        val service = Robolectric.buildService(SuperResolutionMediaProcessingService::class.java).create().get()
        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 999L),
            0,
            93,
        )
        assertTrue(SuperResolutionOperationRegistry.hasActiveOperation())
        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 93L),
            0,
            94,
        )
        assertTrue(SuperResolutionOperationRegistry.hasActiveOperation())
        service.onDestroy()
    }

    @Test
    fun staleCancelWithNoOperationIsNotAccepted() {
        assertFalse(SuperResolutionOperationRegistry.cancel(999L))
    }

    @Test
    fun timeoutReleasesTheServiceOwnerWithoutClearingRecoveryJournal() {
        val request = request(94L)
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 94L,
                phase = SuperResolutionJournalPhase.ADMITTED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        assertEquals(SuperResolutionStartResult.Started(94L), SuperResolutionOperationRegistry.admitForTest(request))
        val service = Robolectric.buildService(SuperResolutionMediaProcessingService::class.java).create().get()
        SuperResolutionForegroundPromotionSeam.start = { _, _, _, _ -> }
        SuperResolutionServiceLaunchSeam.beforeJobStart = { service.onTimeout(94, 0) }
        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 94L),
            0,
            94,
        )
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertEquals(SuperResolutionExportPhase.Cancelled, SuperResolutionOperationRegistry.state.value.status.phase)
        assertNotNull(journal.read())
        journal.clear(94L)
        service.onDestroy()
    }

    @Test
    fun timeoutKeepsCancelledServiceOwnerSettlingUntilTheExactJobCompletes() = runBlocking {
        val request = request(95L)
        assertEquals(SuperResolutionStartResult.Started(95L), SuperResolutionOperationRegistry.admitForTest(request))
        SuperResolutionForegroundPromotionSeam.start = { _, _, _, _ -> }
        val service = Robolectric.buildService(SuperResolutionMediaProcessingService::class.java).create().get()
        val executionEntered = CountDownLatch(1)
        val releasePhysicalWork = CompletableDeferred<Unit>()
        val capturedJob = CompletableDeferred<kotlinx.coroutines.Job>()
        SuperResolutionServiceExecutionSeam.beforePreparation = {
            executionEntered.countDown()
            withContext(NonCancellable) { releasePhysicalWork.await() }
        }
        SuperResolutionServiceLaunchSeam.afterOwnerBound = { _, job -> capturedJob.complete(job) }

        service.onStartCommand(
            Intent(context, SuperResolutionMediaProcessingService::class.java)
                .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, 95L),
            0,
            95,
        )
        assertTrue(executionEntered.await(5, TimeUnit.SECONDS))
        val job = capturedJob.await()

        service.onTimeout(95, 0)
        assertTrue(SuperResolutionOperationRegistry.hasActiveOperation())
        assertTrue(
            SuperResolutionOperationRegistry.admitForTest(request(96L)) is SuperResolutionStartResult.Failed,
        )
        service.onDestroy()
        assertTrue(SuperResolutionOperationRegistry.hasActiveOperation())

        releasePhysicalWork.complete(Unit)
        job.join()
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertEquals(SuperResolutionStartResult.Started(96L), SuperResolutionOperationRegistry.admitForTest(request(96L)))
    }

    private fun request(id: Long): SuperResolutionServiceRequest {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val state = EditorUiState(baseBitmapDirty = true, previewBitmap = source)
        val snapshot = FullExportSourceRequest.capture(state, "generation")
        source.recycle()
        return SuperResolutionServiceRequest(
            operationId = id,
            documentGeneration = "generation",
            documentIdentity = "document",
            preflight = SuperResolutionPreflight(1, 1, 4, 4, 48, 256, 1024, 1024, false),
            sourceRequest = snapshot,
        )
    }
}
