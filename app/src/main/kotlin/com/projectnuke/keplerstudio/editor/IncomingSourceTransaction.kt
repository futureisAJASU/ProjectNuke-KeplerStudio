package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** A completed incoming source whose file is still owned by the open operation. */
internal class OwnedIncomingSource internal constructor(
    val file: File,
) {
    private var transferred = false

    /** Transfers the file to the adopted document exactly once. */
    fun transferToDocument(): File {
        check(!transferred) { "incoming source ownership already transferred" }
        transferred = true
        return file
    }

    /** Releases the file if this operation still owns it. */
    fun cleanup(): Throwable? {
        if (transferred || !file.exists()) return null
        return runCatching {
            check(file.delete()) { "owned incoming source deletion failed" }
        }.exceptionOrNull()
    }

    internal fun wasTransferredForTest(): Boolean = transferred
}

/**
 * Acquires an external Uri into a collision-safe, app-owned working source.
 * The staging file is never returned or exposed as a document source.
 */
internal class IncomingSourceTransaction(
    context: Context,
    private val inputStreamProvider: suspend (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val cacheDirectory = context.applicationContext.cacheDir

    suspend fun acquire(uri: Uri): OwnedIncomingSource = withContext(Dispatchers.IO) {
        val pair = allocatePair()
        val staging = pair.staging
        val final = pair.final
        try {
            inputStreamProvider(uri).use { input ->
                requireNotNull(input) { "incoming image input stream is null" }
                FileOutputStream(staging).use { output ->
                    copyWithCancellation(input, output)
                    output.flush()
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(final)) { "incoming image source promotion failed" }
            OwnedIncomingSource(final)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            cleanupPaths(staging, final, cancellation)
            throw cancellation
        } catch (failure: Throwable) {
            cleanupPaths(staging, final, failure)
            throw failure
        }
    }

    private suspend fun copyWithCancellation(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var chunks = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            output.write(buffer, 0, count)
            chunks += 1
            if (chunks and CHECKPOINT_MASK == 0) {
                currentCoroutineContext().ensureActive()
                yield()
            }
        }
    }

    private fun allocatePair(): SourcePathPair {
        check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
            "incoming source cache directory unavailable"
        }
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val id = idProvider()
            val staging = File(cacheDirectory, "source_${id}.img.staging")
            val final = File(cacheDirectory, "source_${id}.img")
            if (final.exists()) return@repeat
            if (staging.createNewFile()) return SourcePathPair(staging, final)
        }
        throw IOException("unable to allocate unique incoming source path")
    }

    private fun cleanupPaths(staging: File, final: File, cause: Throwable) {
        listOf(staging, final).forEach { path ->
            if (!path.exists()) return@forEach
            runCatching { check(path.delete()) { "incoming source cleanup failed: ${path.name}" } }
                .exceptionOrNull()
                ?.let(cause::addSuppressed)
        }
    }

    private data class SourcePathPair(
        val staging: File,
        val final: File,
    )

    private companion object {
        const val MAX_ALLOCATION_ATTEMPTS = 32
        const val CHECKPOINT_MASK = 0x0f
    }
}
