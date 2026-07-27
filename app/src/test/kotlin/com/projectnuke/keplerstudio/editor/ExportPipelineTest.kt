package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExportPipelineTest {
    @Test
    fun cancellationBeforeInsertionCreatesNoRow() = runTest {
        val rows = FakeRows()
        val job =
            launch {
                executeExportPipeline(
                    request(),
                    rows,
                    isCurrent = { true },
                    render = { bitmap() },
                    persistMetadata = { _, _, _ -> Unit },
                )
            }

        job.cancelAndJoin()

        assertEquals(0, rows.inserted.get())
        assertEquals(0, rows.deleted.get())
    }

    @Test
    fun cancellationAfterPendingInsertionDeletesRowAndRecyclesRender() = runTest {
        val rows = FakeRows().also { it.encodeGate = CompletableDeferred() }
        val rendered = bitmap()
        val job =
            launch {
                executeExportPipeline(
                    request(),
                    rows,
                    isCurrent = { true },
                    render = { rendered },
                    persistMetadata = { _, _, _ -> Unit },
                )
            }
        rows.encodeStarted.await()

        job.cancelAndJoin()

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertTrue(rendered.isRecycled)
    }

    @Test
    fun cancellationDuringInsertionStillRecoversUriAndDeletesPendingRow() = runTest {
        val rows = FakeRows().also { it.insertGate = CompletableDeferred() }
        val rendered = bitmap()
        val job =
            launch {
                executeExportPipeline(
                    request("insert-race"),
                    rows,
                    isCurrent = { true },
                    render = { rendered },
                    persistMetadata = { _, _, _ -> Unit },
                )
            }
        rows.insertStarted.await()

        job.cancel()
        rows.insertGate?.complete(Unit)
        job.join()

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertTrue(rendered.isRecycled)
    }

    @Test
    fun renderFailureNeverCreatesPendingRow() = runTest {
        val rows = FakeRows()

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { true },
                render = { error("render") },
                persistMetadata = { _, _, _ -> Unit },
            )

        assertTrue(result is ExportPipelineResult.Failed)
        assertEquals(0, rows.inserted.get())
    }

    @Test
    fun encodeFailureDeletesPendingRow() = runTest {
        val rows = FakeRows().also { it.failEncode = true }
        val rendered = bitmap()

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { true },
                render = { rendered },
                persistMetadata = { _, _, _ -> Unit },
            )

        assertTrue(result is ExportPipelineResult.Failed)
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertTrue(rendered.isRecycled)
    }

    @Test
    fun publishFailureDeletesPendingRow() = runTest {
        val rows = FakeRows().also { it.failPublish = true }

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { true },
                render = { bitmap() },
                persistMetadata = { _, _, _ -> Unit },
            )

        assertTrue(result is ExportPipelineResult.Failed)
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
    }

    @Test
    fun successfulPublishCommitsRowAndMetadata() = runTest {
        val rows = FakeRows()
        val metadataCalls = AtomicInteger()

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { true },
                render = { bitmap() },
                persistMetadata = { _, width, height ->
                    metadataCalls.incrementAndGet()
                    "$width:$height"
                },
            )

        assertTrue(result is ExportPipelineResult.Published)
        assertEquals("2:2", (result as ExportPipelineResult.Published).metadata)
        assertEquals(1, rows.published.get())
        assertEquals(0, rows.deleted.get())
        assertEquals(1, metadataCalls.get())
    }

    @Test
    fun metadataFailureAfterPublishDoesNotDeleteVisibleRow() = runTest {
        val rows = FakeRows()

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { true },
                render = { bitmap() },
                persistMetadata = { _, _, _ -> error("metadata") },
            )

        assertTrue(result is ExportPipelineResult.PublishedWithMetadataFailure)
        assertEquals(1, rows.published.get())
        assertEquals(0, rows.deleted.get())
    }

    @Test
    fun staleBeforeCommitDeletesPendingRow() = runTest {
        val rows = FakeRows()
        var current = true
        rows.afterEncode = { current = false }

        val result =
            executeExportPipeline(
                request(),
                rows,
                isCurrent = { current },
                render = { bitmap() },
                persistMetadata = { _, _, _ -> Unit },
            )

        assertEquals(ExportPipelineResult.Stale, result)
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
    }

    @Test
    fun oldCompletionCannotOverwriteNewerExportState() = runTest {
        var epoch = 1
        var visibleState = "busy-old"
        val oldRows = FakeRows().also { it.encodeGate = CompletableDeferred() }
        val old =
            launch {
                val result =
                    executeExportPipeline(
                        request("old"),
                        oldRows,
                        isCurrent = { epoch == 1 },
                        render = { bitmap() },
                        persistMetadata = { _, _, _ -> "old" },
                    )
                if (epoch == 1 && result is ExportPipelineResult.Published) {
                    visibleState = result.metadata
                }
            }
        oldRows.encodeStarted.await()
        epoch = 2
        val newRows = FakeRows()
        val newer =
            executeExportPipeline(
                request("new"),
                newRows,
                isCurrent = { epoch == 2 },
                render = { bitmap() },
                persistMetadata = { _, _, _ -> "new" },
            )
        visibleState = (newer as ExportPipelineResult.Published).metadata

        oldRows.encodeGate?.complete(Unit)
        runCurrent()
        old.join()

        assertEquals("new", visibleState)
        assertEquals(1, oldRows.deleted.get())
        assertEquals(0, oldRows.published.get())
        assertEquals(1, newRows.published.get())
    }

    @Test
    fun productionRowOwnerRetriesDeletionFailureAndRepeatedSettlementIsExactOnce() = runTest {
        val rows = FakeRows().also { it.failDelete = true }
        val transaction = ExportRowTransaction(rows)
        transaction.insert(request("retry"))

        assertTrue(transaction.rollbackNoThrow() is IllegalStateException)
        assertEquals(1, rows.deleteAttempts.get())
        assertEquals(0, rows.deleted.get())
        rows.failDelete = false

        assertEquals(null, transaction.rollbackNoThrow())
        assertEquals(2, rows.deleteAttempts.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(null, transaction.rollbackNoThrow())
        assertEquals(2, rows.deleteAttempts.get())
    }

    private fun request(name: String = "export") =
        ExportRowRequest("$name.png", ExportFormat.Png)

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private class FakeRows : ExportRowStore {
        val inserted = AtomicInteger()
        val encoded = AtomicInteger()
        val published = AtomicInteger()
        val deleted = AtomicInteger()
        val deleteAttempts = AtomicInteger()
        val encodeStarted = CompletableDeferred<Unit>()
        val insertStarted = CompletableDeferred<Unit>()
        var insertGate: CompletableDeferred<Unit>? = null
        var encodeGate: CompletableDeferred<Unit>? = null
        var failEncode = false
        var failPublish = false
        var failDelete = false
        var afterEncode: () -> Unit = {}

        override suspend fun insertPending(request: ExportRowRequest): Uri {
            inserted.incrementAndGet()
            insertStarted.complete(Unit)
            insertGate?.await()
            return Uri.parse("content://exports/${request.fileName}")
        }

        override suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat) {
            encodeStarted.complete(Unit)
            encodeGate?.await()
            if (failEncode) error("encode")
            encoded.incrementAndGet()
            afterEncode()
        }

        override suspend fun publish(uri: Uri) {
            if (failPublish) error("publish")
            published.incrementAndGet()
        }

        override suspend fun delete(uri: Uri) {
            deleteAttempts.incrementAndGet()
            if (failDelete) error("delete")
            deleted.incrementAndGet()
        }
    }
}
