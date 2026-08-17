package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a startup reconcile pass classified one artifact. */
internal enum class StartupReconcileDisposition {
    PRESERVED_POINTER,
    PRESERVED_REFERENCED,
    PRESERVED_LIVE_TRANSACTION,
    PRESERVED_LIVE_RESTORE,
    PRESERVED_DOCUMENT,
    ALREADY_ABSENT,
    DELETED_STAGING,
    DELETED_UNREFERENCED,
    DELETED_TEMP,
    FAILED_DELETION,
    IGNORED_UNKNOWN,
}

internal data class StartupReconcileEntry(
    val path: String,
    val disposition: StartupReconcileDisposition,
)

/** Result of one startup storage reconcile pass. */
internal data class StartupReconcileOutcome(
    val entries: List<StartupReconcileEntry>,
) {
    val deletedCount: Int get() = entries.count { it.disposition == StartupReconcileDisposition.DELETED_STAGING || it.disposition == StartupReconcileDisposition.DELETED_UNREFERENCED || it.disposition == StartupReconcileDisposition.DELETED_TEMP }
    val failedCount: Int get() = entries.count { it.disposition == StartupReconcileDisposition.FAILED_DELETION }
    val preservedCount: Int get() = entries.count {
        it.disposition == StartupReconcileDisposition.PRESERVED_POINTER ||
            it.disposition == StartupReconcileDisposition.PRESERVED_REFERENCED ||
            it.disposition == StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION ||
            it.disposition == StartupReconcileDisposition.PRESERVED_LIVE_RESTORE ||
            it.disposition == StartupReconcileDisposition.PRESERVED_DOCUMENT
    }
    val ignoredCount: Int get() = entries.count { it.disposition == StartupReconcileDisposition.IGNORED_UNKNOWN }
}

/**
 * Records the last startup reconcile pass so production tests can observe it.
 * Registry pattern mirrors [HistoryAdmissionTestSeam].
 */
internal class StartupReconcileTestSeam {
    @Volatile internal var outcome: StartupReconcileOutcome? = null
    /** Test-only split point after the cache candidate snapshot and before deletion. */
    @Volatile internal var cacheSnapshotReached: CompletableDeferred<Unit>? = null
    @Volatile internal var cacheSnapshotRelease: CompletableDeferred<Unit>? = null
    /** Test-only split point after the working-source snapshot and before deletion. */
    @Volatile internal var workingSnapshotReached: CompletableDeferred<Unit>? = null
    @Volatile internal var workingSnapshotRelease: CompletableDeferred<Unit>? = null

    internal companion object Registry {
        private val lock = Any()
        private var installed: StartupReconcileTestSeam? = null

        internal fun install(seam: StartupReconcileTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "startup reconcile test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): StartupReconcileTestSeam? = synchronized(lock) { installed }
    }
}

/**
 * Startup-only reconciliation of storage artifacts orphaned by prior process death.
 *
 * Deletes ONLY artifacts provably unreferenced by an authoritative root:
 * the draft generation pointer, `KEY_DRAFT_SOURCE`, or the live in-process
 * document source path. Never throws; per-item failures are recorded as
 * FAILED_DELETION and logged by the caller.
 *
 * Document ownership is established by actual openImage/restore/document
 * transitions in [commitUiState], not by reconciler registration.
 * The in-process source path is tracked only in this pass's `referenced`
 * set for deletion-safety decisions.
 */
internal suspend fun reconcileStartupArtifacts(
    context: Context,
    inProcessSourcePath: String?,
): StartupReconcileOutcome {
    val entries = mutableListOf<StartupReconcileEntry>()
    val seam = StartupReconcileTestSeam.capture()
    val referenced =
        buildSet {
            context
                .getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
                .let { safeDraftPreferenceString(it, KEY_DRAFT_SOURCE) }
                ?.let { add(File(it)) }
            inProcessSourcePath?.let { add(File(it)) }
        }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .toSet()

    val pointer = withContext(Dispatchers.IO) {
        DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
    }

    val generationsRoot = runCatching { draftGenerationsRoot(context).canonicalFile }.getOrNull()
    if (generationsRoot != null) {
        val listedDirs = runCatching { generationsRoot.listFiles()?.toList() }.getOrNull().orEmpty()
        val deleteQueue = mutableListOf<Pair<File, StartupReconcileDisposition>>()
        listedDirs.forEach { dir ->
            val canonical = runCatching { dir.canonicalFile }.getOrNull()
            if (canonical == null || canonical.parentFile != generationsRoot || !canonical.isDirectory)
                return@forEach
            when {
                canonical.name == pointer -> {
                    entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_POINTER)
                    canonical.listFiles()?.forEach { file ->
                        if (file.name.endsWith(DRAFT_TEMP_SUFFIX)) {
                            entries += recordDeletion(file, StartupReconcileDisposition.DELETED_TEMP)
                        }
                    }
                }
                canonical.name.startsWith(DRAFT_GENERATION_STAGING_PREFIX) ->
                    deleteQueue.add(canonical to StartupReconcileDisposition.DELETED_STAGING)
                canonical.name.startsWith(DRAFT_GENERATION_DIR_PREFIX) -> {
                    // Check pointer freshness under coordinator read lock before
                    // deciding; a concurrent save may have made this the current pointer.
                    val isCurrentUnderLock =
                        withContext(Dispatchers.IO) {
                            DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
                        } == canonical.name
                    if (isCurrentUnderLock) {
                        entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_POINTER)
                        canonical.listFiles()?.forEach { file ->
                            if (file.name.endsWith(DRAFT_TEMP_SUFFIX)) {
                                entries += recordDeletion(file, StartupReconcileDisposition.DELETED_TEMP)
                            }
                        }
                    } else {
                        deleteQueue.add(canonical to StartupReconcileDisposition.DELETED_UNREFERENCED)
                    }
                }
                else -> entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
            }
        }
        // Pass the snapshot pointer so each individual deletion can revalidate
        // under the write lock before mutating the generation tree.
        deleteQueue.forEach { (dir, disp) ->
            entries += recordDirectoryDeletion(context, dir, generationsRoot, disp, pointer)
        }
    }

    val cacheRoot = runCatching { context.cacheDir.canonicalFile }.getOrNull()
    if (cacheRoot != null) {
        val cacheFiles = cacheRoot.listFiles()?.toList().orEmpty()
        seam?.cacheSnapshotReached?.complete(Unit)
        seam?.cacheSnapshotRelease?.let { release ->
            withContext(Dispatchers.IO) { runCatching { release.await() } }
        }
        cacheFiles.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || canonical.parentFile != cacheRoot) return@forEach
            when {
                IncomingSourceArtifactNames.isStagingName(canonical.name) ->
                    entries += recordIncomingSourceDeletion(canonical, StartupReconcileDisposition.DELETED_STAGING)
                IncomingSourceArtifactNames.isFinalName(canonical.name) ->
                    entries += recordIncomingSourceReferencedDeletion(canonical, referenced)
                else -> entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
            }
        }
    }

    val workingRoot = runCatching { File(context.filesDir, WORKING_SOURCES_DIR).canonicalFile }.getOrNull()
    if (workingRoot != null) {
        val workingFiles = workingRoot.listFiles()?.toList().orEmpty()
        seam?.workingSnapshotReached?.complete(Unit)
        seam?.workingSnapshotRelease?.let { release ->
            withContext(Dispatchers.IO) { runCatching { release.await() } }
        }
        workingFiles.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || canonical.parentFile != workingRoot) return@forEach
            if (RestoredWorkingSourceOwnership.isOwnedName(canonical.name)) {
                entries += recordRestoredWorkingSourceDeletion(canonical, referenced)
            } else {
                entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
            }
        }
    }

    val legacyRoot = runCatching { File(context.filesDir, LEGACY_DRAFT_DIR).canonicalFile }.getOrNull()
    if (legacyRoot != null) {
        legacyRoot.listFiles()?.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || canonical.parentFile != legacyRoot) return@forEach
            if (canonical.name.endsWith(DRAFT_TEMP_SUFFIX)) {
                entries += recordDeletion(canonical, StartupReconcileDisposition.DELETED_TEMP)
            } else {
                entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
            }
        }
    }

    val outcome = StartupReconcileOutcome(entries)
    seam?.outcome = outcome
    return outcome
}

private suspend fun recordDirectoryDeletion(
    context: Context,
    dir: File,
    ownerRoot: File,
    disposition: StartupReconcileDisposition,
    snapshotPointer: String? = null,
): StartupReconcileEntry {
    val canonical = runCatching { dir.canonicalFile }.getOrNull()
    if (canonical == null || canonical.parentFile != ownerRoot) {
        return StartupReconcileEntry(dir.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
    }
    return try {
        withContext(Dispatchers.IO) {
            DraftStorageCoordinator.withWriteLock {
val dirName = canonical.name
        if (!canonical.isDirectory) {
            return@withWriteLock StartupReconcileEntry(
                canonical.absolutePath,
                StartupReconcileDisposition.FAILED_DELETION,
            )
        }
        val actualCurrent = currentDraftGenerationId(context)
        val shouldSkip =
            dirName.startsWith(DRAFT_GENERATION_DIR_PREFIX) &&
                (
                    dirName == snapshotPointer ||
                        dirName == actualCurrent
                    )
        if (!shouldSkip) {
            canonical.listFiles()?.forEach { file ->
                check(file.delete()) { "failed to delete draft generation file: ${file.absolutePath}" }
            }
            check(canonical.delete()) { "failed to delete draft generation directory: ${canonical.absolutePath}" }
        }
        StartupReconcileEntry(canonical.absolutePath, if (shouldSkip) StartupReconcileDisposition.PRESERVED_POINTER else disposition)
            }
        }
    } catch (t: Throwable) {
        StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.FAILED_DELETION)
    }
}

private fun recordDeletion(file: File, disposition: StartupReconcileDisposition): StartupReconcileEntry {
    val deleted = runCatching { file.delete() }.getOrDefault(false)
    return StartupReconcileEntry(
        file.absolutePath,
        if (deleted) disposition else StartupReconcileDisposition.FAILED_DELETION,
    )
}

private fun recordReferencedDeletion(
    file: File,
    referenced: Set<File>,
    deletionDisposition: StartupReconcileDisposition,
): StartupReconcileEntry {
    val canonical = runCatching { file.canonicalFile }.getOrNull()
        ?: return StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
    return if (canonical in referenced) {
        StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_REFERENCED)
    } else {
        recordDeletion(canonical, deletionDisposition)
    }
}

private fun recordIncomingSourceDeletion(file: File, disposition: StartupReconcileDisposition): StartupReconcileEntry =
    when (IncomingSourceLiveOwnership.deleteIfUnowned(file)) {
        IncomingSourceLiveOwnership.DeleteResult.PRESERVED_LIVE_TRANSACTION ->
            StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION)
        IncomingSourceLiveOwnership.DeleteResult.PRESERVED_DOCUMENT ->
            StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.PRESERVED_DOCUMENT)
        IncomingSourceLiveOwnership.DeleteResult.DELETED -> StartupReconcileEntry(file.absolutePath, disposition)
        IncomingSourceLiveOwnership.DeleteResult.FAILED ->
            StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.FAILED_DELETION)
    }

private fun recordIncomingSourceReferencedDeletion(file: File, referenced: Set<File>): StartupReconcileEntry {
    val canonical = runCatching { file.canonicalFile }.getOrNull()
        ?: return StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
    if (canonical in referenced) return StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_REFERENCED)
    return recordIncomingSourceDeletion(canonical, StartupReconcileDisposition.DELETED_UNREFERENCED)
}

private fun recordRestoredWorkingSourceDeletion(
    file: File,
    referenced: Set<File>,
): StartupReconcileEntry {
    val canonical = runCatching { file.canonicalFile }.getOrNull()
        ?: return StartupReconcileEntry(file.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
    if (canonical in referenced) {
        return StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_REFERENCED)
    }
    return when (RestoredWorkingSourceOwnership.deleteIfUnowned(canonical)) {
        RestoredWorkingSourceOwnership.DeleteResult.PRESERVED_LIVE_RESTORE ->
            StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_LIVE_RESTORE)
        RestoredWorkingSourceOwnership.DeleteResult.PRESERVED_DOCUMENT ->
            StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.PRESERVED_DOCUMENT)
        RestoredWorkingSourceOwnership.DeleteResult.DELETED ->
            StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.DELETED_UNREFERENCED)
        RestoredWorkingSourceOwnership.DeleteResult.ALREADY_ABSENT ->
            StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.ALREADY_ABSENT)
        RestoredWorkingSourceOwnership.DeleteResult.FAILED ->
            StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.FAILED_DELETION)
    }
}

private const val WORKING_SOURCES_DIR = "editor_sources"
private const val DRAFT_TEMP_SUFFIX = ".tmp"
private const val LEGACY_DRAFT_DIR = "drafts/current"