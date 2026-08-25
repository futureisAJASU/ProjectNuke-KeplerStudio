package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.io.File

/** Transient source families owned by the unified maintenance backend. */
internal enum class TransientSourceFamily {
    /** cacheDir/source_*.img.staging — live IncomingSource transactions only. */
    INCOMING_STAGING,

    /** cacheDir/source_*.img — live transactions, adopted documents, orphans. */
    INCOMING_FINAL,

    /** filesDir/editor_sources/restored_*.img — live restores, documents, orphans. */
    RESTORED_WORKING,

    /** drafts/current dot-tmp files — never owned; safe unconditional reclamation. */
    LEGACY_TEMP,
}

/** Caller intent for one maintenance pass. Modes are NOT interchangeable. */
internal enum class TransientMaintenanceMode {
    /**
     * Reclaim process-death orphans conservatively: unowned staging, finals
     * not referenced by the persistent pointer / in-process document, and
     * unreferenced restored working sources.
     */
    STARTUP,

    /** Age-based editor hygiene over incoming cache finals only. */
    AGE_BASED,

    /**
     * User-initiated "delete temporary original cache": string-unprotected,
     * unowned finals plus unowned restored working sources. Protection is
     * still authoritative at DELETE TIME via the ownership registries; the
     * captured strings are advisory pre-filtering only.
     */
    MANUAL,

    /** Reserved for disk-pressure recovery: unowned transient artifacts only. */
    PRESSURE,
}

internal enum class TransientEntryDisposition {
    DELETED,
    PRESERVED_LIVE_TRANSACTION,
    PRESERVED_LIVE_DOCUMENT,
    PRESERVED_LIVE_RESTORE,
    PRESERVED_REFERENCED,
    PRESERVED_PROTECTED,
    SKIPPED_BY_MODE,
    ALREADY_ABSENT,
    FAILED,
}

internal data class TransientMaintenanceEntry(
    val path: String,
    val family: TransientSourceFamily,
    val disposition: TransientEntryDisposition,
    /** File size recorded at delete time; nonzero only for DELETED entries. */
    val bytes: Long = 0L,
)

internal data class TransientMaintenanceReport(
    val entries: List<TransientMaintenanceEntry>,
) {
    val candidateCount: Int get() = entries.count { it.disposition != TransientEntryDisposition.SKIPPED_BY_MODE }
    val deletedCount: Int get() = entries.count { it.disposition == TransientEntryDisposition.DELETED }
    val preservedLiveCount: Int get() = entries.count {
        it.disposition == TransientEntryDisposition.PRESERVED_LIVE_TRANSACTION ||
            it.disposition == TransientEntryDisposition.PRESERVED_LIVE_DOCUMENT ||
            it.disposition == TransientEntryDisposition.PRESERVED_LIVE_RESTORE
    }
    val preservedPersistentCount: Int get() = entries.count {
        it.disposition == TransientEntryDisposition.PRESERVED_REFERENCED ||
            it.disposition == TransientEntryDisposition.PRESERVED_PROTECTED
    }
    val alreadyAbsentCount: Int get() = entries.count { it.disposition == TransientEntryDisposition.ALREADY_ABSENT }
    val failureCount: Int get() = entries.count { it.disposition == TransientEntryDisposition.FAILED }

    /** Bytes physically reclaimed; failed deletions are never counted. */
    val reclaimedBytes: Long get() = entries.sumOf { it.bytes }

    companion object {
        val EMPTY = TransientMaintenanceReport(emptyList())
    }
}

/**
 * Read-only snapshot of what a MANUAL or AGE_BASED cleanup can reclaim right
 * now. Classification shares the EXACT same rules as [cleanup] under the same
 * mode — family gates, name gates, prescreen rules, age contract — then
 * consults the ownership registries WITHOUT deleting anything. Live/protected
 * files are never counted as reclaimable. Registry state is sampled at
 * inspection time; a later action always defers to delete-time authority over
 * this estimate. Reclaimability is an eligibility estimate: physical delete
 * success cannot be known non-destructively.
 */
internal data class TransientCleanupStats(
    /** Physically present mode-family candidates, including protected/live ones. */
    val candidateCount: Int,
    /** Currently eligible + unowned + unprotected candidates. */
    val reclaimableCount: Int,
    /** Bytes of the reclaimable candidates only; never includes live/protected files. */
    val reclaimableBytes: Long,
)

/**
 * Single ownership-aware backend for transient source maintenance.
 *
 * Every physical deletion goes through the owning registry's linearized
 * delete boundary (ownership check + delete in one critical section), so two
 * concurrent passes can never both delete the same path, and a racing
 * acquisition can never lose an adopted artifact to a stale snapshot. A
 * failed deletion is reported truthfully and never counted as reclaimed.
 *
 * The backend performs NO lock acquisition of its own: the ownership
 * registries are leaves under the global lock-order contract, and the draft
 * generation tree stays exclusively inside StartupStorageReconciler because
 * its cleanup requires DraftStorageCoordinator pointer authority. Legacy
 * obsolete source_*.img reclamation stays inside LegacyDraftSourceReclamation
 * because it requires the same Coordinator write-lock authority.
 */
internal object TransientSourceMaintenance {

    /** Unified entry point over every supported family. Never throws. */
    fun cleanup(
        context: Context,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String> = emptySet(),
        olderThanMillis: Long? = null,
        inProcessReferenced: Set<File> = emptySet(),
    ): TransientMaintenanceReport =
        merge(
            cleanupIncoming(context, mode, protectedPaths, olderThanMillis, inProcessReferenced),
            cleanupRestored(context, mode, protectedPaths, olderThanMillis, inProcessReferenced),
            cleanupLegacyTemps(context, mode),
        )

    /** Incoming cache family (staging + final candidates). Never throws. */
    fun cleanupIncoming(
        context: Context,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String> = emptySet(),
        olderThanMillis: Long? = null,
        inProcessReferenced: Set<File> = emptySet(),
    ): TransientMaintenanceReport {
        val now = System.currentTimeMillis()
        val entries = mutableListOf<TransientMaintenanceEntry>()
        incomingStagingCandidates(context).forEach { canonical ->
            entries += processStaging(canonical, mode)
        }
        incomingFinalCandidates(context).forEach { canonical ->
            entries += processFinal(canonical, mode, protectedPaths, olderThanMillis, inProcessReferenced, now)
        }
        return TransientMaintenanceReport(entries)
    }

    /** Restored working-source family. Never throws. */
    fun cleanupRestored(
        context: Context,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String> = emptySet(),
        olderThanMillis: Long? = null,
        inProcessReferenced: Set<File> = emptySet(),
    ): TransientMaintenanceReport {
        if (!familyApplies(TransientSourceFamily.RESTORED_WORKING, mode)) {
            return TransientMaintenanceReport.EMPTY
        }
        val entries = mutableListOf<TransientMaintenanceEntry>()
        restoredWorkingCandidates(context).forEach { canonical ->
            val prescreen = prescreen(canonical, mode, protectedPaths, olderThanMillis, inProcessReferenced)
            entries +=
                when {
                    prescreen != null ->
                        TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, prescreen)
                    else -> {
                        // Size captured before the boundary call (post-delete
                        // length would be 0).
                        val size = fileSizeOf(canonical)
                        when (RestoredWorkingSourceOwnership.deleteIfUnowned(canonical)) {
                            RestoredWorkingSourceOwnership.DeleteResult.PRESERVED_LIVE_RESTORE ->
                                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, TransientEntryDisposition.PRESERVED_LIVE_RESTORE)
                            RestoredWorkingSourceOwnership.DeleteResult.PRESERVED_DOCUMENT ->
                                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, TransientEntryDisposition.PRESERVED_LIVE_DOCUMENT)
                            RestoredWorkingSourceOwnership.DeleteResult.DELETED ->
                                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, TransientEntryDisposition.DELETED, size)
                            RestoredWorkingSourceOwnership.DeleteResult.ALREADY_ABSENT ->
                                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, TransientEntryDisposition.ALREADY_ABSENT)
                            RestoredWorkingSourceOwnership.DeleteResult.FAILED ->
                                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.RESTORED_WORKING, TransientEntryDisposition.FAILED)
                        }
                    }
                }
        }
        return TransientMaintenanceReport(entries)
    }

    /**
     * Legacy temps under drafts/current. The generation tree is NOT touched
     * here (pointer authority lives in StartupStorageReconciler). Never throws.
     */
    fun cleanupLegacyTemps(
        context: Context,
        mode: TransientMaintenanceMode,
    ): TransientMaintenanceReport {
        if (!familyApplies(TransientSourceFamily.LEGACY_TEMP, mode)) {
            return TransientMaintenanceReport.EMPTY
        }
        val legacyRoot = runCatching { persistentDraftDirectory(context).canonicalFile }.getOrNull()
            ?: return TransientMaintenanceReport.EMPTY
        val listed = runCatching { legacyRoot.listFiles()?.toList() }.getOrNull().orEmpty()
        val entries = mutableListOf<TransientMaintenanceEntry>()
        listed.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != legacyRoot || !canonical.isFile) return@forEach
            if (!canonical.name.endsWith(LEGACY_TEMP_SUFFIX)) return@forEach
            val size = fileSizeOf(canonical)
            val deleted = runCatching { canonical.delete() }.getOrDefault(false)
            entries += TransientMaintenanceEntry(
                canonical.absolutePath,
                TransientSourceFamily.LEGACY_TEMP,
                if (deleted) TransientEntryDisposition.DELETED else TransientEntryDisposition.FAILED,
                if (deleted) size else 0L,
            )
        }
        return TransientMaintenanceReport(entries)
    }

    /**
     * Non-destructive statistics for a user-facing cleanup estimate. Mirrors
     * [cleanup] under [mode] exactly — same enumerators, family gates, name
     * gates, prescreen rules, age contract — then probes ownership read-only
     * through each registry's own monitor. Supported modes: MANUAL (incoming
     * finals + restored working sources) and AGE_BASED (incoming finals older
     * than [olderThanMillis] only). Never deletes and never mutates registry
     * state.
     */
    fun inspectTransientSources(
        context: Context,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String> = emptySet(),
        olderThanMillis: Long? = null,
    ): TransientCleanupStats {
        require(
            mode == TransientMaintenanceMode.MANUAL || mode == TransientMaintenanceMode.AGE_BASED,
        ) { "read-only statistics support MANUAL and AGE_BASED only: $mode" }
        val now = System.currentTimeMillis()
        var candidateCount = 0
        var reclaimableCount = 0
        var reclaimableBytes = 0L

        // Incoming finals: identical enumeration/classification to cleanupIncoming.
        incomingFinalCandidates(context).forEach { canonical ->
            candidateCount++
            if (
                prescreenFinal(canonical, mode, protectedPaths, olderThanMillis, emptySet(), now) == null &&
                !IncomingSourceLiveOwnership.isOwned(canonical)
            ) {
                reclaimableCount++
                reclaimableBytes = BitmapMemoryBudget.saturatingAdd(reclaimableBytes, fileSizeOf(canonical))
            }
        }

        // Restored working sources: identical rules to cleanupRestored (MANUAL only;
        // AGE_BASED never expands to this family).
        if (mode == TransientMaintenanceMode.MANUAL &&
            familyApplies(TransientSourceFamily.RESTORED_WORKING, mode)
        ) {
            restoredWorkingCandidates(context).forEach { canonical ->
                candidateCount++
                if (
                    prescreen(canonical, mode, protectedPaths, null, emptySet()) == null &&
                    !RestoredWorkingSourceOwnership.isOwned(canonical)
                ) {
                    reclaimableCount++
                    reclaimableBytes = BitmapMemoryBudget.saturatingAdd(reclaimableBytes, fileSizeOf(canonical))
                }
            }
        }

        return TransientCleanupStats(candidateCount, reclaimableCount, reclaimableBytes)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Single source of truth for candidate enumeration. Every destructive
     * cleanup and every read-only inspection classifies over these exact
     * gates (canonical root containment, regular file, family name), so the
     * two can never silently drift apart.
     */

    private fun incomingStagingCandidates(context: Context): List<File> {
        val cacheRoot = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return emptyList()
        val listed = runCatching { cacheRoot.listFiles()?.toList() }.getOrNull().orEmpty()
        return listed.mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            when {
                canonical.parentFile != cacheRoot || !canonical.isFile -> null
                IncomingSourceArtifactNames.isStagingName(canonical.name) -> canonical
                else -> null
            }
        }
    }

    private fun incomingFinalCandidates(context: Context): List<File> {
        val cacheRoot = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return emptyList()
        val listed = runCatching { cacheRoot.listFiles()?.toList() }.getOrNull().orEmpty()
        return listed.mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            when {
                canonical.parentFile != cacheRoot || !canonical.isFile -> null
                IncomingSourceArtifactNames.isFinalName(canonical.name) -> canonical
                else -> null
            }
        }
    }

    private fun restoredWorkingCandidates(context: Context): List<File> {
        val workingRoot = runCatching { File(context.filesDir, WORKING_SOURCES_DIR).canonicalFile }.getOrNull()
            ?: return emptyList()
        val listed = runCatching { workingRoot.listFiles()?.toList() }.getOrNull().orEmpty()
        return listed.mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            when {
                canonical.parentFile != workingRoot || !canonical.isFile -> null
                RestoredWorkingSourceOwnership.isOwnedName(canonical.name) -> canonical
                else -> null
            }
        }
    }

    private fun processStaging(canonical: File, mode: TransientMaintenanceMode): TransientMaintenanceEntry =
        when {
            !familyApplies(TransientSourceFamily.INCOMING_STAGING, mode) ->
                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.INCOMING_STAGING, TransientEntryDisposition.SKIPPED_BY_MODE)
            else ->
                deleteViaIncomingRegistry(canonical, TransientSourceFamily.INCOMING_STAGING)
        }

    private fun processFinal(
        canonical: File,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String>,
        olderThanMillis: Long?,
        inProcessReferenced: Set<File>,
        now: Long,
    ): TransientMaintenanceEntry {
        val prescreenDisposition =
            prescreenFinal(canonical, mode, protectedPaths, olderThanMillis, inProcessReferenced, now)
        return when {
            prescreenDisposition != null ->
                TransientMaintenanceEntry(canonical.absolutePath, TransientSourceFamily.INCOMING_FINAL, prescreenDisposition)
            else -> deleteViaIncomingRegistry(canonical, TransientSourceFamily.INCOMING_FINAL)
        }
    }

    private fun deleteViaIncomingRegistry(
        canonical: File,
        family: TransientSourceFamily,
    ): TransientMaintenanceEntry {
        // Size must be captured BEFORE the boundary call: after a successful
        // delete the path no longer exists.
        val size = fileSizeOf(canonical)
        return when (val result = IncomingSourceLiveOwnership.deleteIfUnowned(canonical)) {
            IncomingSourceLiveOwnership.DeleteResult.PRESERVED_LIVE_TRANSACTION ->
                TransientMaintenanceEntry(canonical.absolutePath, family, TransientEntryDisposition.PRESERVED_LIVE_TRANSACTION)
            IncomingSourceLiveOwnership.DeleteResult.PRESERVED_DOCUMENT ->
                TransientMaintenanceEntry(canonical.absolutePath, family, TransientEntryDisposition.PRESERVED_LIVE_DOCUMENT)
            IncomingSourceLiveOwnership.DeleteResult.DELETED ->
                TransientMaintenanceEntry(canonical.absolutePath, family, TransientEntryDisposition.DELETED, size)
            IncomingSourceLiveOwnership.DeleteResult.FAILED ->
                TransientMaintenanceEntry(canonical.absolutePath, family, TransientEntryDisposition.FAILED)
        }
    }

    /**
     * Pre-registry screening shared by every family. Returns the final
     * disposition when the candidate must not reach a registry boundary at
     * all, or null when the authoritative registry decides.
     */
    private fun prescreen(
        canonical: File,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String>,
        olderThanMillis: Long?,
        inProcessReferenced: Set<File>,
    ): TransientEntryDisposition? =
        when (mode) {
            TransientMaintenanceMode.STARTUP ->
                if (canonical in inProcessReferenced) TransientEntryDisposition.PRESERVED_REFERENCED else null
            TransientMaintenanceMode.MANUAL ->
                if (canonical.absolutePath in protectedPaths) TransientEntryDisposition.PRESERVED_PROTECTED else null
            else -> null
        }

    private fun prescreenFinal(
        canonical: File,
        mode: TransientMaintenanceMode,
        protectedPaths: Set<String>,
        olderThanMillis: Long?,
        inProcessReferenced: Set<File>,
        now: Long,
    ): TransientEntryDisposition? {
        prescreen(canonical, mode, protectedPaths, olderThanMillis, inProcessReferenced)?.let { return it }
        if (mode == TransientMaintenanceMode.AGE_BASED) {
            val expired = olderThanMillis?.let { now - canonical.lastModified() > it } ?: false
            return when {
                canonical.absolutePath in protectedPaths -> TransientEntryDisposition.PRESERVED_PROTECTED
                !expired -> TransientEntryDisposition.SKIPPED_BY_MODE
                else -> null
            }
        }
        return null
    }

    private fun familyApplies(family: TransientSourceFamily, mode: TransientMaintenanceMode): Boolean =
        when (family) {
            TransientSourceFamily.INCOMING_STAGING -> mode == TransientMaintenanceMode.STARTUP || mode == TransientMaintenanceMode.PRESSURE
            TransientSourceFamily.INCOMING_FINAL -> true
            // Restored working sources stay out of the age-based editor
            // hygiene pass; the manual "temporary original cache" card owns
            // them alongside incoming finals (Phase 6 made its copy truthful).
            TransientSourceFamily.RESTORED_WORKING ->
                mode == TransientMaintenanceMode.STARTUP ||
                    mode == TransientMaintenanceMode.PRESSURE ||
                    mode == TransientMaintenanceMode.MANUAL
            TransientSourceFamily.LEGACY_TEMP -> mode == TransientMaintenanceMode.STARTUP
        }

    /** Size captured before deletion; failures yield 0 and are not reclaimed. */
    private fun fileSizeOf(file: File): Long = runCatching { file.length() }.getOrDefault(0L)

    private fun merge(vararg reports: TransientMaintenanceReport): TransientMaintenanceReport =
        TransientMaintenanceReport(reports.flatMap { it.entries })

    private const val WORKING_SOURCES_DIR = "editor_sources"
    private const val LEGACY_TEMP_SUFFIX = ".tmp"
}
