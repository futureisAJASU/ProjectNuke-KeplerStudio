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
    private val handoffToDocumentOwnership: (String?) -> Unit,
    private val releaseLiveOwnership: () -> Unit,
) {
    private var transferred = false

    /** Transfers the file to the adopted document exactly once. */
    fun transferToDocument(previousDocumentPath: String? = null): File {
        check(!transferred) { "incoming source ownership already transferred" }
        handoffToDocumentOwnership(previousDocumentPath)
        transferred = true
        return file
    }

    /** Releases the file if this operation still owns it. */
    fun cleanup(): Throwable? {
        if (transferred) return null
        return try {
            if (file.exists()) check(file.delete()) { "owned incoming source deletion failed" }
            null
        } catch (failure: Throwable) {
            failure
        } finally {
            releaseLiveOwnership()
        }
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
    /**
     * Optional declared source size used for storage-pressure admission.
     * Null/unknown sizes skip admission entirely - no invented estimates;
     * mid-copy failures still clean up truthfully through the existing path.
     */
    private val declaredSizeProvider: suspend (Uri) -> Long? = { uri ->
        queryDeclaredSourceSize(context.applicationContext, uri)
    },
) {
    private val appContext: Context = context.applicationContext
    private val cacheDirectory = context.applicationContext.cacheDir

    suspend fun acquire(uri: Uri): OwnedIncomingSource = withContext(Dispatchers.IO) {
        // Storage-pressure admission BEFORE allocation: with a declared size,
        // insufficient headroom throws before any staging file exists, so
        // there is no staging leak, no final leak, no live ownership entry,
        // and the previous document is untouched.
        declaredSizeProvider(uri)?.let { declared ->
            val admitted = StoragePressure.controller.ensureWriteHeadroom(
                context = appContext,
                targetVolumeFile = cacheDirectory,
                requiredBytes = declared,
                onInsufficient = { false },
            ) { true }
            if (!admitted) {
                throw IllegalStateException(
                    "insufficient storage for incoming source acquisition: $uri",
                )
            }
        }
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
            OwnedIncomingSource(
                file = final,
                handoffToDocumentOwnership = { previousDocumentPath ->
                    IncomingSourceLiveOwnership.transferToDocument(
                        staging = staging,
                        final = final,
                        previousDocumentPath = previousDocumentPath,
                    )
                },
                releaseLiveOwnership = { IncomingSourceLiveOwnership.release(staging, final) },
            )
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
            val staging = File(cacheDirectory, IncomingSourceArtifactNames.stagingName(id))
            val final = File(cacheDirectory, IncomingSourceArtifactNames.finalName(id))
            if (final.exists()) return@repeat
            if (staging.createNewFile()) {
                // The staging allocation and live-owner registration are one
                // transition. Reconciliation can therefore never observe an
                // allocated staging path before its current-process owner.
                IncomingSourceLiveOwnership.register(staging, final)
                return SourcePathPair(staging, final)
            }
        }
        throw IOException("unable to allocate unique incoming source path")
    }

    private fun cleanupPaths(staging: File, final: File, cause: Throwable) {
        try {
            listOf(staging, final).forEach { path ->
                if (!path.exists()) return@forEach
                runCatching { check(path.delete()) { "incoming source cleanup failed: ${path.name}" } }
                    .exceptionOrNull()
                    ?.let(cause::addSuppressed)
            }
        } finally {
            IncomingSourceLiveOwnership.release(staging, final)
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

/** Shared naming contract and current-process ownership boundary for incoming sources. */
internal object IncomingSourceArtifactNames {
    fun finalName(id: String): String = "source_${id}.img"
    fun stagingName(id: String): String = "${finalName(id)}.staging"

    fun isFinalName(name: String): Boolean =
        name.startsWith("source_") && name.endsWith(".img") && name.length > "source_.img".length

    fun isStagingName(name: String): Boolean =
        name.startsWith("source_") && name.endsWith(".img.staging") && name.length > "source_.img.staging".length
}

/**
 * In-memory roots disappear on process death, letting startup reclaim dead artifacts.
 * Registration and reconciler deletion checks share one monitor so an older reconcile
 * snapshot cannot delete a path allocated by a newer transaction.
 */
internal object IncomingSourceLiveOwnership {
    private val lock = Any()
    private val livePaths = mutableSetOf<String>()
    private val documentPaths = mutableSetOf<String>()

    fun register(staging: File, final: File) = synchronized(lock) {
        livePaths += canonical(staging)
        livePaths += canonical(final)
    }

    fun release(staging: File, final: File) = synchronized(lock) {
        livePaths -= canonical(staging)
        livePaths -= canonical(final)
    }

    /**
     * Atomically changes the owner from an incoming transaction to the current
     * document. The previous document root is removed in the same critical
     * section, so there is no unowned interval after adoption.
     */
    fun transferToDocument(staging: File, final: File, previousDocumentPath: String?) = synchronized(lock) {
        previousDocumentPath?.let { documentPaths -= canonical(File(it)) }
        documentPaths += canonical(final)
        livePaths -= canonical(staging)
        livePaths -= canonical(final)
    }

    /** Replaces a document root for non-incoming document restoration paths. */
    fun replaceDocument(previousPath: String?, nextPath: String?) = synchronized(lock) {
        previousPath?.let { documentPaths -= canonical(File(it)) }
        nextPath?.let { path ->
            val file = File(path)
            if (IncomingSourceArtifactNames.isFinalName(file.name)) {
                documentPaths += canonical(file)
            }
        }
    }

    fun registerDocument(path: File) = synchronized(lock) {
        documentPaths += canonical(path)
    }

    fun releaseDocument(path: File) = synchronized(lock) {
        documentPaths -= canonical(path)
    }

    internal fun isDocumentOwnedForTest(file: File): Boolean = synchronized(lock) {
        canonical(file) in documentPaths
    }

    internal fun releaseDocumentForTest(file: File) = releaseDocument(file)

    internal fun clearForTest() = synchronized(lock) {
        livePaths.clear()
        documentPaths.clear()
    }

    internal fun liveOwnedCountForTest(): Int = synchronized(lock) { livePaths.size }

    internal fun documentOwnedCountForTest(): Int = synchronized(lock) { documentPaths.size }

    internal fun snapshotForTest(): Set<String> = synchronized(lock) {
        (livePaths + documentPaths).toSet()
    }

    internal enum class DeleteResult {
        PRESERVED_LIVE_TRANSACTION,
        PRESERVED_DOCUMENT,
        DELETED,
        FAILED,
    }

    /** Ownership check and physical deletion share one linearization boundary. */
    fun deleteIfUnowned(file: File): DeleteResult = synchronized(lock) {
        val path = canonical(file)
        when {
            path in livePaths -> DeleteResult.PRESERVED_LIVE_TRANSACTION
            path in documentPaths -> DeleteResult.PRESERVED_DOCUMENT
            file.delete() -> DeleteResult.DELETED
            else -> DeleteResult.FAILED
        }
    }

    /** Compatibility bridge for existing callers; null means any live owner. */
    fun deleteIfNotLive(file: File): Boolean? = when (deleteIfUnowned(file)) {
        DeleteResult.PRESERVED_LIVE_TRANSACTION,
        DeleteResult.PRESERVED_DOCUMENT -> null
        DeleteResult.DELETED -> true
        DeleteResult.FAILED -> false
    }

    internal fun isLiveForTest(file: File): Boolean = synchronized(lock) { canonical(file) in livePaths }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
