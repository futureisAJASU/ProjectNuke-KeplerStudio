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
    // Unsafe primitives — ONLY callable while owning [globalStorageLock]
    // -----------------------------------------------------------------------

    internal suspend fun rollbackCommittedDraftUnsafe(context: Context, saved: DraftSaveResult) {
        withContext(ioDispatcher) {
            if (!saved.pointerPublished) {
                deleteDraftDirectory(context, DraftGenerationDirectory(saved.generationDirectory))
                return@withContext
            }
            val pointer = currentDraftGenerationId(context)
            if (pointer == saved.generationId) {
                val previousIsComplete = runCatching {
                    saved.expectedPointerGenerationId != null &&
                        saved.previousGenerationDirectory?.let { directory ->
                            findDraftGenerationDirectory(context, saved.expectedPointerGenerationId)
                                ?.root
                                ?.canonicalFile == directory.canonicalFile
                        } == true
                }.getOrDefault(false)
                val restoredPrevious = previousIsComplete &&
                    publishDraftGeneration(context, checkNotNull(saved.expectedPointerGenerationId))
                val rolledBack = restoredPrevious || clearCurrentDraftGenerationPointer(context)
                if (!rolledBack || currentDraftGenerationId(context) == saved.generationId) return@withContext
            }
            if (currentDraftGenerationId(context) != saved.generationId) {
                deleteDraftDirectory(context, DraftGenerationDirectory(saved.generationDirectory))
                saved.compatibilitySourceFile
                    ?.takeIf { saved.compatibilitySourceChanged && isOwnedDraftSource(context, it) }
                    ?.delete()
            }
        }
    }

    internal fun deleteGenerationUnsafe(context: Context, generationId: String) {
        deleteDraftGenerationById(context, generationId)
    }

    internal suspend fun publishGenerationUnsafe(context: Context, generationId: String): Boolean =
        withContext(ioDispatcher) { publishDraftGeneration(context, generationId) }

    internal suspend fun clearPointerUnsafe(context: Context): Boolean =
        withContext(ioDispatcher) { clearCurrentDraftGenerationPointer(context) }

    internal fun readCurrentPointerUnsafe(context: Context): String? =
        currentDraftGenerationId(context)

    internal suspend fun findByGenerationIdUnsafe(context: Context, generationId: String): DraftGenerationDirectory? =
        withContext(ioDispatcher) { findDraftGenerationDirectory(context, generationId) }

    internal suspend fun validateCurrentGenerationUnsafe(context: Context): ValidatedDraftGeneration? =
        withContext(ioDispatcher) { validateCurrentDraftGeneration(context) }

    internal suspend fun finalizeGenerationUnsafe(context: Context, staging: DraftGenerationDirectory, generationId: String): DraftGenerationDirectory? =
        withContext(ioDispatcher) { finalizeDraftGeneration(context, staging, generationId) }

    internal suspend fun deleteAllExceptUnsafe(context: Context, keepDirectory: File?, extraPreserveGenerationId: String? = null) {
        withContext(ioDispatcher) {
            val actualPointer = currentDraftGenerationId(context)
            val root = runCatching { draftGenerationsRoot(context).canonicalFile }.getOrNull()
            val kept = keepDirectory?.let { runCatching { it.canonicalFile }.getOrNull() }
            val preserveIds = mutableSetOf<String>()
            kept?.let { runCatching { it.name }.getOrNull()?.let { name -> preserveIds.add(name) } }
            actualPointer?.let { preserveIds.add(it) }
            extraPreserveGenerationId?.let { preserveIds.add(it) }
            root?.listFiles()?.forEach { dir ->
                val contained = runCatching { dir.canonicalFile }.getOrNull()
                if (contained == null || contained.parentFile != root || !contained.isDirectory) return@forEach
                if (contained == kept) return@forEach
                val dirName = contained.name
                if (!preserveIds.contains(dirName) &&
                    (dirName.startsWith(DRAFT_GENERATION_DIR_PREFIX) || dirName.startsWith(DRAFT_GENERATION_STAGING_PREFIX))
                ) {
                    deleteDraftDirectory(context, DraftGenerationDirectory(contained))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Generation write path — protected by [globalStorageLock]
    // -----------------------------------------------------------------------

    suspend fun newStagingGeneration(context: Context): DraftGenerationDirectory? =
        globalStorageLock.withLock {
            withContext(ioDispatcher) {
                runCatching { newDraftGenerationDirectory(context) }.getOrNull()
            }
        }

    // -----------------------------------------------------------------------
    // Pointer / validation — ONLY inside withWriteLock / withReadLock
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Transaction helpers — these are the ONLY public entry points
    // -----------------------------------------------------------------------

    suspend fun rollbackCommittedDraft(context: Context, saved: DraftSaveResult) {
        globalStorageLock.withLock {
            rollbackCommittedDraftUnsafe(context, saved)
        }
    }

    /** Exact compare-and-mutate transaction for invalid-generation cleanup. */
    suspend fun clearInvalidGenerationIfCurrent(context: Context, invalidId: String): Boolean =
        withWriteLock {
            val current = currentDraftGenerationId(context)
            if (current != invalidId) return@withWriteLock false
            val cleared = runCatching { clearCurrentDraftGenerationPointer(context) }.getOrDefault(false)
            if (cleared) {
                runCatching { deleteDraftGenerationById(context, invalidId) }
            }
            cleared
        }

    // -----------------------------------------------------------------------
    // Blocking sync wrappers — safe ONLY for pre-viewModel init reads
    // -----------------------------------------------------------------------

    fun readCurrentPointerBlocking(context: Context): String? =
        runCatching { currentDraftGenerationId(context) }.getOrNull()

    fun findCurrentGenerationBlocking(context: Context): DraftGenerationDirectory? =
        runCatching { findCurrentDraftGenerationDirectory(context) }.getOrNull()

    // -----------------------------------------------------------------------
    // Storage transaction helpers
    // -----------------------------------------------------------------------

    internal suspend fun <T> withWriteLock(block: suspend () -> T): T =
        globalStorageLock.withLock { block() }

    internal suspend fun <T> withReadLock(block: suspend () -> T): T =
        globalStorageLock.withLock { block() }

    // -----------------------------------------------------------------------
    // Coordinator lifecycle
    // -----------------------------------------------------------------------

    fun resetForTest() {
        // No-op: the coordinator owns no mutable state beyond the mutex.
    }
}
