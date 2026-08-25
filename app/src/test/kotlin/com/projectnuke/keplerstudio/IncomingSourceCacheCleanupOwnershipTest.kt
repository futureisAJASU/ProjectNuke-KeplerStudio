package com.projectnuke.keplerstudio

import android.net.Uri
import com.projectnuke.keplerstudio.editor.IncomingSourceArtifactNames
import com.projectnuke.keplerstudio.editor.IncomingSourceLiveOwnership
import com.projectnuke.keplerstudio.editor.IncomingSourceTransaction
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IncomingSourceCacheCleanupOwnershipTest {
    private val context = RuntimeEnvironment.getApplication()
    private var seamHandle: AutoCloseable? = null

    @Before
    fun setUp() {
        IncomingSourceLiveOwnership.clearForTest()
        clearSources()
    }

    @After
    fun tearDown() {
        seamHandle?.close()
        seamHandle = null
        IncomingSourceLiveOwnership.clearForTest()
        clearSources()
    }

    @Test
    fun staleManualCleanupSnapshotCannotDeleteNewlyAdoptedDocument() = runBlocking {
        val id = "manual-cleanup-race"
        val path = File(context.cacheDir, IncomingSourceArtifactNames.finalName(id)).apply { writeText("old") }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        seamHandle = ManualCacheCleanupTestSeam.install(
            ManualCacheCleanupTestSeam().also {
                it.snapshotReached = reached
                it.snapshotRelease = release
            },
        )

        val cleanup = async(Dispatchers.Default) {
            cleanupTemporarySourceFilesForTest(
                context = context,
                activeSourcePath = null,
                draftSourcePath = null,
                olderThan7DaysOnly = false,
            )
        }
        withTimeout(15_000L) { reached.await() }

        assertTrue(path.delete())
        val acquisition = async(Dispatchers.Default) {
            IncomingSourceTransaction(
                context,
                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                idProvider = { id },
            ).acquire(Uri.EMPTY)
        }
        val owned = withTimeout(15_000L) { acquisition.await() }
        owned.transferToDocument()
        release.complete(Unit)
        withTimeout(15_000L) { cleanup.await() }

        assertTrue("manual cleanup must honor the adopted document root", owned.file.exists())
        IncomingSourceLiveOwnership.releaseDocumentForTest(owned.file)
        owned.file.delete()
        Unit
    }

    private fun clearSources() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (IncomingSourceArtifactNames.isFinalName(file.name) || IncomingSourceArtifactNames.isStagingName(file.name)) {
                file.delete()
            }
        }
    }
}
