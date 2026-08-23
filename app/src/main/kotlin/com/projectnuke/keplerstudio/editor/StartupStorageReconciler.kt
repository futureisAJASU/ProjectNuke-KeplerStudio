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
    PRESERVED_LIVE_VIEWMODEL,
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
            it.disposition == StartupReconcileDisposition.PRESERVED_LIVE_VIEWMODEL ||
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
    /** Test-only boundary immediately before the reconciler's pointer read. */
    @Volatile internal var pointerReadAttempted: CompletableDeferred<Unit>? = null
    /** Test-only boundary after a generation is queued and before its delete-time check. */
    @Volatile internal var generationDeletionCandidateId: String? = null
    @Volatile internal var generationDeletionQueuedReached: CompletableDeferred<Unit>? = null
    @Volatile internal var generationDeletionQueuedRelease: CompletableDeferred<Unit>? = null
    /** Test-only boundary while the reconciler owns the generation mutation lock. */
    @Volatile internal var generationDeletionAuthorityReached: CompletableDeferred<Unit>? = null
    @Volatile internal var generationDeletionAuthorityRelease: CompletableDeferred<Unit>? = null

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

    seam?.pointerReadAttempted?.complete(Unit)
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
        entries += TransientSourceMaintenance
            .cleanupIncoming(context, TransientMaintenanceMode.STARTUP, inProcessReferenced = referenced)
            .entries
            .map { it.toReconcileEntry() }
        // Reporting parity: files matching no owned pattern are recorded as
        // ignored exactly as before the unified backend took over deletion.
        val handledPaths = entries.map { it.path }.toSet()
        cacheFiles.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != cacheRoot) return@forEach
            if (!IncomingSourceArtifactNames.isStagingName(canonical.name) &&
                !IncomingSourceArtifactNames.isFinalName(canonical.name) &&
                canonical.absolutePath !in handledPaths
            ) {
                entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
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
        entries += TransientSourceMaintenance
            .cleanupRestored(context, TransientMaintenanceMode.STARTUP, inProcessReferenced = referenced)
            .entries
            .map { it.toReconcileEntry() }
        val handledPaths = entries.map { it.path }.toSet()
        workingFiles.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != workingRoot) return@forEach
            if (!RestoredWorkingSourceOwnership.isOwnedName(canonical.name) &&
                canonical.absolutePath !in handledPaths
            ) {
                entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
            }
        }
    }

    val legacyRoot = runCatching { File(context.filesDir, LEGACY_DRAFT_DIR).canonicalFile }.getOrNull()
    if (legacyRoot != null) {
        withContext(Dispatchers.IO) {
            DraftStorageCoordinator.withWriteLock {
                entries += TransientSourceMaintenance
                    .cleanupLegacyTemps(context, TransientMaintenanceMode.STARTUP)
                    .entries
                    .map { it.toReconcileEntry() }
                legacyRoot.listFiles()?.forEach { file ->
                    val canonical = runCatching { file.canonicalFile }.getOrNull()
                    if (canonical == null || canonical.parentFile != legacyRoot) return@forEach
                    when {
                        canonical.name.endsWith(DRAFT_TEMP_SUFFIX) -> Unit // handled by the unified temp pass above
                        canonical.name.startsWith("source_") && canonical.extension == "img" ->
                            // Ownership-aware reclamation: the persistent pointer
                            // was just reread under this write lock and live
                            // ViewModel/operation roots are consulted atomically
                            // at the registry delete boundary.
                            entries += recordLegacySourceReclamation(context, canonical)
                        else ->
                            entries += StartupReconcileEntry(canonical.absolutePath, StartupReconcileDisposition.IGNORED_UNKNOWN)
                    }
                }
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
    val seam = StartupReconcileTestSeam.capture()
    if (seam?.generationDeletionCandidateId == canonical.name) {
        seam.generationDeletionQueuedReached?.complete(Unit)
        seam.generationDeletionQueuedRelease?.await()
    }
    return try {
        withContext(Dispatchers.IO) {
            DraftStorageCoordinator.withWriteLock {
        if (seam?.generationDeletionCandidateId == canonical.name) {
            seam.generationDeletionAuthorityReached?.complete(Unit)
            seam.generationDeletionAuthorityRelease?.await()
        }
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

/**
 * Maps unified backend outcomes onto startup reconcile dispositions so the
 * reconciler's public reporting (and its tests) keep their exact semantics.
 */
private fun TransientMaintenanceEntry.toReconcileEntry(): StartupReconcileEntry {
    val disposition =
        when (disposition) {
            TransientEntryDisposition.DELETED ->
                when (family) {
                    TransientSourceFamily.INCOMING_STAGING -> StartupReconcileDisposition.DELETED_STAGING
                    TransientSourceFamily.LEGACY_TEMP -> StartupReconcileDisposition.DELETED_TEMP
                    else -> StartupReconcileDisposition.DELETED_UNREFERENCED
                }
            TransientEntryDisposition.PRESERVED_LIVE_TRANSACTION -> StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION
            TransientEntryDisposition.PRESERVED_LIVE_DOCUMENT -> StartupReconcileDisposition.PRESERVED_DOCUMENT
            TransientEntryDisposition.PRESERVED_LIVE_RESTORE -> StartupReconcileDisposition.PRESERVED_LIVE_RESTORE
            TransientEntryDisposition.PRESERVED_REFERENCED,
            TransientEntryDisposition.PRESERVED_PROTECTED,
            -> StartupReconcileDisposition.PRESERVED_REFERENCED
            TransientEntryDisposition.ALREADY_ABSENT -> StartupReconcileDisposition.ALREADY_ABSENT
            TransientEntryDisposition.FAILED -> StartupReconcileDisposition.FAILED_DELETION
            TransientEntryDisposition.SKIPPED_BY_MODE -> StartupReconcileDisposition.IGNORED_UNKNOWN
        }
    return StartupReconcileEntry(path, disposition)
}

private fun recordLegacySourceReclamation(
    context: Context,
    file: File,
): StartupReconcileEntry =
    when (val entry = LegacyDraftSourceReclamation.reclaimCandidateUnsafe(context = context, candidate = file)) {
        is LegacySourceReclaimEntry -> when (entry.disposition) {
            LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.PRESERVED_REFERENCED)
            LegacySourceReclaimDisposition.PRESERVED_LIVE_VIEWMODEL ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.PRESERVED_LIVE_VIEWMODEL)
            LegacySourceReclaimDisposition.PRESERVED_MIGRATION_COMPATIBILITY ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.IGNORED_UNKNOWN)
            LegacySourceReclaimDisposition.PRESERVED_LIVE_DOCUMENT ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.PRESERVED_DOCUMENT)
            LegacySourceReclaimDisposition.PRESERVED_OPERATION ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION)
            LegacySourceReclaimDisposition.DELETED ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.DELETED_UNREFERENCED)
            LegacySourceReclaimDisposition.ALREADY_ABSENT ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.ALREADY_ABSENT)
            LegacySourceReclaimDisposition.FAILED ->
                StartupReconcileEntry(entry.path, StartupReconcileDisposition.FAILED_DELETION)
        }
    }

private const val WORKING_SOURCES_DIR = "editor_sources"
private const val DRAFT_TEMP_SUFFIX = ".tmp"
private const val LEGACY_DRAFT_DIR = "drafts/current"
