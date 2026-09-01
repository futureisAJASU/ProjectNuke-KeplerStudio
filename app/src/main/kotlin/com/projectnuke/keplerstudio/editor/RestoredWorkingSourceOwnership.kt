package com.projectnuke.keplerstudio.editor

import java.io.File

/**
 * Current-process ownership for generation-restore working copies.
 *
 * A restore copy is a temporary reachability root until the document commit
 * hands it to the live document.  The monitor is also the deletion
 * linearization boundary used by startup reconciliation, so a stale listing
 * cannot delete a path acquired by a restore after the listing was captured.
 */
internal object RestoredWorkingSourceOwnership {
    private const val PREFIX = "restored_"
    private const val SUFFIX = ".img"
    private val lock = Any()
    private val restorePaths = mutableSetOf<String>()
    private val documentPaths = mutableSetOf<String>()
    private val operationRefs = mutableMapOf<String, Int>()

    internal enum class DeleteResult {
        PRESERVED_LIVE_RESTORE,
        PRESERVED_DOCUMENT,
        DELETED,
        ALREADY_ABSENT,
        FAILED,
    }

    fun isOwnedName(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(SUFFIX) && name.length > PREFIX.length + SUFFIX.length

    /** Acquires ownership before the destination file is created. */
    fun acquire(path: File) = synchronized(lock) {
        require(isOwnedName(path.name)) { "invalid restored working source name: ${path.name}" }
        restorePaths += canonical(path)
    }

    fun releaseRestore(path: File) = synchronized(lock) {
        restorePaths -= canonical(path)
    }

    /** Atomically changes restore ownership into document ownership. */
    fun transferToDocument(path: File, previousDocumentPath: String?) = synchronized(lock) {
        val canonicalPath = canonical(path)
        check(canonicalPath in restorePaths) { "restored source is not live-owned" }
        previousDocumentPath?.let { documentPaths -= canonical(File(it)) }
        restorePaths -= canonicalPath
        documentPaths += canonicalPath
    }

    /** Replaces any restored document root during a document transition. */
    fun replaceDocument(previousPath: String?, nextPath: String?) = synchronized(lock) {
        previousPath?.let { documentPaths -= canonical(File(it)) }
        nextPath?.let { path ->
            val file = File(path)
            if (isOwnedName(file.name)) documentPaths += canonical(file)
        }
    }

    fun registerDocument(path: File) = synchronized(lock) {
        if (isOwnedName(path.name)) documentPaths += canonical(path)
    }

    fun releaseDocument(path: File) = synchronized(lock) {
        documentPaths -= canonical(path)
    }

    /** Claims one exact app-owned source for a long-running export operation. */
    fun acquireOperation(path: File): AutoCloseable = synchronized(lock) {
        require(isOwnedName(path.name)) { "invalid restored working source name: ${path.name}" }
        val key = canonical(path)
        operationRefs[key] = (operationRefs[key] ?: 0) + 1
        var released = false
        AutoCloseable {
            synchronized(lock) {
                if (released) return@AutoCloseable
                released = true
                val remaining = (operationRefs[key] ?: 1) - 1
                if (remaining <= 0) operationRefs.remove(key) else operationRefs[key] = remaining
            }
        }
    }

    /** Ownership check and physical deletion share one linearization boundary. */
    fun deleteIfUnowned(path: File): DeleteResult = synchronized(lock) {
        val canonicalPath = canonical(path)
        when {
            canonicalPath in restorePaths -> DeleteResult.PRESERVED_LIVE_RESTORE
            canonicalPath in documentPaths -> DeleteResult.PRESERVED_DOCUMENT
            (operationRefs[canonicalPath] ?: 0) > 0 -> DeleteResult.PRESERVED_LIVE_RESTORE
            !path.exists() -> DeleteResult.ALREADY_ABSENT
            path.delete() -> DeleteResult.DELETED
            else -> DeleteResult.FAILED
        }
    }

    /**
     * Read-only ownership probe for truthful statistics; same monitor as the
     * delete boundary, never deletes and never mutates state.
     */
    fun isOwned(path: File): Boolean = synchronized(lock) {
        val canonicalPath = canonical(path)
        canonicalPath in restorePaths || canonicalPath in documentPaths ||
            (operationRefs[canonicalPath] ?: 0) > 0
    }

    internal fun isRestoreOwnedForTest(path: File): Boolean = synchronized(lock) {
        canonical(path) in restorePaths
    }

    internal fun isDocumentOwnedForTest(path: File): Boolean = synchronized(lock) {
        canonical(path) in documentPaths
    }

    internal fun restoreOwnedCountForTest(): Int = synchronized(lock) { restorePaths.size }

    internal fun documentOwnedCountForTest(): Int = synchronized(lock) { documentPaths.size }

    internal fun snapshotForTest(): Set<String> = synchronized(lock) {
        (restorePaths + documentPaths).toSet()
    }

    internal fun clearForTest() = synchronized(lock) {
        restorePaths.clear()
        documentPaths.clear()
        operationRefs.clear()
    }

    private fun canonical(path: File): String =
        runCatching { path.canonicalPath }.getOrElse { path.absolutePath }
}
