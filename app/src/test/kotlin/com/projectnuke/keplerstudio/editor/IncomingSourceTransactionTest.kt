package com.projectnuke.keplerstudio.editor

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IncomingSourceTransactionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearOwnedSources() {
        IncomingSourceLiveOwnership.clearForTest()
        context.cacheDir.listFiles { file ->
            file.name.startsWith("source_") &&
                (file.name.endsWith(".img") || file.name.endsWith(".img.staging"))
        }.orEmpty().forEach { it.delete() }
    }

    @Test
    fun successfulCopyPromotesAndTransferKeepsFinalSource() {
        runBlocking {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val owned = transaction { ByteArrayInputStream(bytes) }.acquire(Uri.EMPTY)

            assertTrue(owned.file.isFile)
            assertArrayEquals(bytes, owned.file.readBytes())
            assertTrue(owned.file.name.startsWith("source_"))
            assertTrue(owned.file.name.endsWith(".img"))
            assertFalse(owned.file.name.endsWith(".staging"))

            val adopted = owned.transferToDocument()
            assertEquals(owned.file, adopted)
            assertTrue(owned.wasTransferredForTest())
            assertEquals(null, owned.cleanup())
            assertTrue(adopted.isFile)
            assertTrue(IncomingSourceLiveOwnership.isDocumentOwnedForTest(adopted))
            IncomingSourceLiveOwnership.releaseDocumentForTest(adopted)
            adopted.delete()
        }
    }

    @Test
    fun inputOpenFailureLeavesNoSourceOrStagingFile() {
        runBlocking {
            val before = ownedSourceNames()
            try {
                transaction { throw IOException("provider read failed") }.acquire(Uri.EMPTY)
                error("acquisition must fail")
            } catch (failure: IOException) {
                assertEquals("provider read failed", failure.message)
            }
            assertEquals(before, ownedSourceNames())
        }
    }

    @Test
    fun midCopyFailureDeletesPartialStagingFile() {
        runBlocking {
            val stream = ThrowingInputStream()
            val before = ownedSourceNames()

            try {
                transaction { stream }.acquire(Uri.EMPTY)
                error("acquisition must fail")
            } catch (failure: IOException) {
                assertEquals("mid-copy failure", failure.message)
            }

            assertTrue(stream.closed)
            assertEquals(before, ownedSourceNames())
        }
    }

    @Test
    fun cancellationDuringCopyDeletesPartialSourceAndClosesStream() {
        runBlocking {
            val stream = GateInputStream()
            val before = ownedSourceNames()
            val acquisition = async { transaction { stream }.acquire(Uri.EMPTY) }

            assertTrue(stream.entered.await())
            acquisition.cancel()
            stream.release()
            try {
                acquisition.await()
                error("acquisition must be cancelled")
            } catch (_: kotlinx.coroutines.CancellationException) {
            }

            assertTrue(stream.closed)
            assertEquals(before, ownedSourceNames())
        }
    }

    @Test
    fun repeatedAcquisitionsUseDistinctCollisionSafePaths() {
        runBlocking {
            val first = transaction { ByteArrayInputStream(byteArrayOf(1)) }.acquire(Uri.EMPTY)
            val second = transaction { ByteArrayInputStream(byteArrayOf(2)) }.acquire(Uri.EMPTY)

            assertNotEquals(first.file.absolutePath, second.file.absolutePath)
            first.cleanup()
            second.cleanup()
        }
    }

    @Test
    fun reconcilePreservesLiveStagingDuringCopyThenRollbackReleasesOwnership() {
        runBlocking {
            val stream = GateInputStream()
            val acquisition = async { transaction { stream }.acquire(Uri.EMPTY) }
            awaitEditorCompletionForTest(
                description = "incoming copy must enter its gate",
                completion = stream.entered,
            )
            val staging = context.cacheDir.listFiles().orEmpty().single { IncomingSourceArtifactNames.isStagingName(it.name) }
            reconcileStartupArtifacts(context, null)
            assertTrue("live staging must survive reconciliation", staging.exists())
            assertTrue(IncomingSourceLiveOwnership.isLiveForTest(staging))

            acquisition.cancel()
            stream.release()
            runCatching { acquisition.await() }
            assertFalse("rollback removes staging", staging.exists())
            assertFalse("rollback releases ownership", IncomingSourceLiveOwnership.isLiveForTest(staging))
            reconcileStartupArtifacts(context, null)
        }
    }

    @Test
    fun reconcilePreservesPromotedFinalUntilDocumentTransfer() {
        runBlocking {
            val owned = transaction { ByteArrayInputStream(byteArrayOf(1)) }.acquire(Uri.EMPTY)
            val final = owned.file
            assertTrue("promotion creates final before adoption", final.exists())
            reconcileStartupArtifacts(context, null)
            assertTrue("live final before adoption must survive", final.exists())
            assertTrue(IncomingSourceLiveOwnership.isLiveForTest(final))

            owned.transferToDocument()
            assertFalse("transfer releases transaction ownership", IncomingSourceLiveOwnership.isLiveForTest(final))
            assertTrue("transfer establishes document ownership", IncomingSourceLiveOwnership.isDocumentOwnedForTest(final))
            IncomingSourceLiveOwnership.releaseDocumentForTest(final)
            final.delete()
        }
    }

    @Test
    fun replacedDocumentSourceIsReclaimedAfterOwnershipRelease() {
        runBlocking {
            val owned = transaction { ByteArrayInputStream(byteArrayOf(9)) }.acquire(Uri.EMPTY)
            val final = owned.file
            owned.transferToDocument()
            assertTrue(IncomingSourceLiveOwnership.isDocumentOwnedForTest(final))

            IncomingSourceLiveOwnership.releaseDocumentForTest(final)
            val outcome = reconcileStartupArtifacts(context, null)

            assertFalse("released document source becomes reclaimable", final.exists())
            assertTrue(outcome.entries.any { it.path == final.absolutePath && it.disposition == StartupReconcileDisposition.DELETED_UNREFERENCED })
        }
    }

    private fun transaction(provider: suspend (Uri) -> InputStream?): IncomingSourceTransaction =
        IncomingSourceTransaction(context, inputStreamProvider = provider)

    private fun ownedSourceNames(): Set<String> =
        context.cacheDir
            .listFiles { file ->
                file.name.startsWith("source_") &&
                    (file.name.endsWith(".img") || file.name.endsWith(".img.staging"))
            }
            .orEmpty()
            .map { it.name }
            .toSet()
}

private class ThrowingInputStream : InputStream() {
    var closed = false
    private var reads = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (reads++ == 0) {
            buffer[offset] = 7
            return 1
        }
        throw IOException("mid-copy failure")
    }

    override fun close() {
        closed = true
    }

    override fun read(): Int = throw IOException("single-byte read unsupported")
}

private class GateInputStream : InputStream() {
    val entered = CompletableDeferred<Boolean>()
    var closed = false
    private val releaseLatch = CountDownLatch(1)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        entered.complete(true)
        check(releaseLatch.await(5, TimeUnit.SECONDS)) { "copy gate was not released" }
        return -1
    }

    fun release() {
        releaseLatch.countDown()
    }

    override fun close() {
        closed = true
    }

    override fun read(): Int = throw IOException("single-byte read unsupported")
}
