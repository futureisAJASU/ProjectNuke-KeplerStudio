package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-global serialization boundary for persistent Draft storage.
 *
 * All mutations that touch the single authoritative persistent Draft slot
 * (pointer, generation directories, generation staging, legacy compatibility
 * preferences, cleanup) must run inside [globalStorageLock] to prevent
 * cross-ViewModel races.
 *
 * Lock order contract (MUST be preserved everywhere):
 *   ViewModel-local Draft operation ownership (if held)
 *       -> [globalStorageLock]
 *
 * Never acquire a ViewModel-local draft mutex while already holding
 * [globalStorageLock].
 */
internal object DraftStorageCoordinator {

    @Volatile @JvmField var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val globalStorageLock = Mutex()

    // -----------------------------------------------------------------------
    // Generation write path — staged allocation, write, finalize, publish
    // -----------------------------------------------------------------------

    suspend fun newStagingGeneration(context: Context): DraftGenerationDirectory? =
        withContext(ioDispatcher) {
            runCatching { newDraftGenerationDirectory(context) }.getOrNull()
        }

    suspend fun finalizeGeneration(
        context: Context,
        staging: DraftGenerationDirectory,
        generationId: String,
    ): DraftGenerationDirectory? = globalStorageLock.withLock {
        withContext(ioDispatcher) { finalizeDraftGeneration(context, staging, generationId) }
    }

    suspend fun publishGeneration(context: Context, generationId: String): Boolean =
        globalStorageLock.withLock {
            withContext(ioDispatcher) { publishDraftGeneration(context, generationId) }
        }

    suspend fun findByGenerationId(
        context: Context,
        generationId: String,
    ): DraftGenerationDirectory? = globalStorageLock.withLock {
        withContext(ioDispatcher) { findDraftGenerationDirectory(context, generationId) }
    }

    // -----------------------------------------------------------------------
    // Pointer — read, clear, validate
    // -----------------------------------------------------------------------

    suspend fun readCurrentPointer(context: Context): String? =
        globalStorageLock.withLock {
            withContext(ioDispatcher) { currentDraftGenerationId(context) }
        }

    suspend fun clearPointer(context: Context): Boolean =
        globalStorageLock.withLock {
            withContext(ioDispatcher) { clearCurrentDraftGenerationPointer(context) }
        }

    suspend fun validateCurrentGeneration(context: Context): ValidatedDraftGeneration? =
        globalStorageLock.withLock {
            withContext(ioDispatcher) { validateCurrentDraftGeneration(context) }
        }

    // -----------------------------------------------------------------------
    // Destructive cleanup
    // -----------------------------------------------------------------------

    suspend fun deleteAllExcept(
        context: Context,
        keepDirectory: File?,
    ) {
        globalStorageLock.withLock {
            val actualPointer = readCurrentPointerUnsafe(context)
            withContext(ioDispatcher) {
                deleteAllDraftGenerationsExcept(context, keepDirectory, actualPointer)
            }
        }
    }

    suspend fun deleteById(context: Context, generationId: String) {
        globalStorageLock.withLock {
            withContext(ioDispatcher) { deleteDraftGenerationById(context, generationId) }
        }
    }

    suspend fun rollbackCommittedDraft(context: Context, saved: DraftSaveResult) {
        globalStorageLock.withLock {
            withContext(ioDispatcher) { rollbackCommittedDraft(context, saved) }
        }
    }

    // -----------------------------------------------------------------------
    // Blocking sync wrappers — safe for pre-viewModel init reads
    // -----------------------------------------------------------------------

    fun readCurrentPointerBlocking(context: Context): String? =
        runCatching { currentDraftGenerationId(context) }.getOrNull()

    fun findCurrentGenerationBlocking(context: Context): DraftGenerationDirectory? =
        runCatching { findCurrentDraftGenerationDirectory(context) }.getOrNull()

    // -----------------------------------------------------------------------
    // Internal — locked pointer snapshot without outer reentrancy
    // -----------------------------------------------------------------------

    internal suspend fun <T> withWriteLock(block: suspend () -> T): T =
        globalStorageLock.withLock { block() }

    internal suspend fun <T> withReadLock(block: suspend () -> T): T =
        globalStorageLock.withLock { block() }

    private fun readCurrentPointerUnsafe(context: Context): String? =
        runCatching { currentDraftGenerationId(context) }.getOrNull()

    // -----------------------------------------------------------------------
    // Coordinator lifecycle — App scope owns init/teardown via [resetForTest]
    // -----------------------------------------------------------------------

    @Volatile
    private var initialized = false

    fun install(context: Context) {
        if (initialized) return
        initialized = true
    }

    fun resetForTest() {
        initialized = false
    }
}