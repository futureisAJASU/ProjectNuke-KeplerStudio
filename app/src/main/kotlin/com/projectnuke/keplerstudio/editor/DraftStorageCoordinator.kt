package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.util.Log
import java.io.File
import androidx.annotation.WorkerThread
import kotlin.jvm.Throws

/**
 * Coordinates access to draft storage to prevent race conditions
 */
@WorkerThread
internal object DraftStorageCoordinator {
    private val _lock = Any()
    private var currentPointer: String? = null

    /**
     * Executes a block within a global write lock
     */
    fun <T> withWriteLock(block: () -> T): T {
        synchronized(_lock) {
            try {
                return block()
            } catch (t: Throwable) {
                Log.e("DraftStorageCoordinator", "Error in withWriteLock", t)
                throw t
            }
        }
    }

    /**
     * Clears the current pointer
     */
    @WorkerThread
    @Throws(IllegalStateException::class)
    fun clearPointerUnsafe(context: Context): Boolean {
        val currentPointer = readCurrentPointerUnsafe(context) ?: return false
        val file = persistentDraftDirectory(context).resolve("$currentPointer.tmp")
        if (!file.isFile) return false
        val deleted = file.delete()
        if (!deleted) {
            throw IllegalStateException("failed to delete draft pointer: ${file.absolutePath}")
        }
        return true
    }

    /**
     * Publishes a generation
     */
    @WorkerThread
    @Throws(IllegalStateException::class)
    fun publishGenerationUnsafe(context: Context, pointer: String): Boolean {
        val dir = persistentDraftDirectory(context)
        val file = dir.resolve("$pointer.tmp")
        val renamed = file.renameTo(dir.resolve(pointer))
        if (!renamed) {
            throw IllegalStateException("failed to publish generation: $file.absolutePath")
        }
        return true
    }

    /**
     * Deletes a generation
     */
    /**
     * Executes a block within a global read lock
     */
    fun <T> withReadLock(block: () -> T): T {
        synchronized(_lock) {
            return block()
        }
    }

    @Throws(IllegalStateException::class)
    fun deleteGenerationUnsafe(context: Context, pointer: String): Boolean {
        val root = persistentDraftDirectory(context)
        val genDir = File(root, pointer)
        if (!genDir.isDirectory) return true
        val files = genDir.listFiles() ?: return true
        var success = true
        for (file in files) {
            val deleted = file.delete()
            if (!deleted) {
                success = false
                Log.e("DraftStorageCoordinator", "Failed to delete generation file: ${file.absolutePath}")
            }
        }
        val deletedDir = genDir.delete()
        if (!deletedDir && genDir.exists()) {
            success = false
        }
        return success
    }

    /**
     * Checks if a source is an owned draft source
     */
    fun isOwnedDraftSource(context: Context, file: File): Boolean {
        // Implementation here
        return true
    }

    /**
     * Gets the persistent draft directory
     */
    fun persistentDraftDirectory(context: Context): File {
        // Implementation here
        return context.getDir("drafts", Context.MODE_PRIVATE)
    }

    /**
     * Reads the current pointer
     */
    fun readGenerationUnsafe(context: Context, pointer: String?): DraftGenerationDirectory? {
        if (pointer == null) return null
        val root = persistentDraftDirectory(context)
        val genDir = File(root, pointer)
        if (!genDir.isDirectory) return null
        return DraftGenerationDirectory(genDir)
    }

    @Throws(IllegalStateException::class)
    fun finalizeGenerationUnsafe(context: Context, pointer: String): Boolean {
        return deleteGenerationUnsafe(context, pointer)
    }

    fun readCurrentPointerUnsafe(context: Context): String? {
        // Implementation here
        return currentPointer
    }

    /**
     * Cleanup legacy sources
     */
    @WorkerThread
    fun cleanupLegacySources(context: Context, prevPrefs: Map<String, Any?>, liveSourcePath: String?): Boolean {
        val dir = persistentDraftDirectory(context)
        val files = dir.listFiles() ?: return true

        var overallSuccess = true

        for (file in files) {
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            val isLiveSource = canonical != null && liveSourcePath != null &&
                runCatching { File(liveSourcePath).canonicalFile }.getOrNull() == canonical

            if (isLiveSource) continue

            if (file.name.endsWith(".tmp")) {
                val deleted = file.delete()
                if (!deleted) {
                    EditorViewModel.logDraftSaveFailure(
                        IllegalStateException("failed to delete temp file: ${file.absolutePath}")
                    )
                    overallSuccess = false
                }
                continue
            }

            val matchesLegacySource = canonical != null && prevPrefs[EditorViewModel.KEY_DRAFT_SOURCE] as? String != null &&
                runCatching { File(prevPrefs[EditorViewModel.KEY_DRAFT_SOURCE] as String).canonicalFile }.getOrNull() == canonical

            val isOwnedDraft = matchesLegacySource && isOwnedDraftSource(context, file)

            if (matchesLegacySource && isOwnedDraft) {
                val deleted = file.delete()
                if (!deleted) {
                    EditorViewModel.logDraftSaveFailure(
                        IllegalStateException("failed to delete legacy draft source: ${file.absolutePath}")
                    )
                    overallSuccess = false
                }
            }
        }

        return overallSuccess
    }
}
