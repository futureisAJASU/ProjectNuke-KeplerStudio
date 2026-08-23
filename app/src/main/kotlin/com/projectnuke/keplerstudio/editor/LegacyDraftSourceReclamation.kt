package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.io.File

/**
 * Truthful dispositions for one legacy Draft compatibility source candidate.
 * Every outcome states WHY the candidate was kept or what happened to it;
 * a failed physical delete is never reported as reclaimed.
 */
internal enum class LegacySourceReclaimDisposition {
    PRESERVED_PERSISTENT_POINTER,
    PRESERVED_LIVE_VIEWMODEL,
    PRESERVED_LIVE_DOCUMENT,
    PRESERVED_OPERATION,
    /** Fixed-name migration compatibility protection (source.img stays conservative). */
    PRESERVED_MIGRATION_COMPATIBILITY,
    DELETED,
    ALREADY_ABSENT,
    FAILED,
}

internal data class LegacySourceReclaimEntry(
    val path: String,
    val disposition: LegacySourceReclaimDisposition,
)

/**
 * Ownership-aware reclamation for obsolete legacy Draft compatibility sources
 * under `filesDir/drafts/current`.
 *
 * Deletion rule — ALL conditions are evaluated at delete time:
 *  1. canonical parent is exactly drafts/current
 *  2. name matches the owned legacy source naming contract; the fixed
 *     historical `source.img` stays conservative unless the caller proves it
 *     unreachable under all migration/recovery contracts
 *  3. it is NOT the current KEY_DRAFT_SOURCE target (re-read inside the
 *     caller's Draft/global deletion authority immediately before deletion)
 *  4-7. it is NOT claimed by any live ViewModel visible/document root or
 *     in-flight save/restore operation root ([LegacyDraftSourceOwnership])
 *
 * LOCK/AUTHORITY CONTRACT:
 *  [reclaimCandidateUnsafe] and [sweepObsoleteSourcesUnsafe] REQUIRE the
 *  caller to already hold the DraftStorageCoordinator global WRITE lock. The
 *  persistent pointer can only be published while that lock is held, so
 *  holding it across the pointer reread + registry classification + physical
 *  delete closes every publish race without nested lock acquisition. The
 *  registry monitor remains a leaf acquired strictly after the Coordinator
 *  lock.
 */
internal object LegacyDraftSourceReclamation {

    /**
     * Reclaims one candidate. Caller MUST hold the DraftStorageCoordinator
     * global write lock. Never throws.
     */
    fun reclaimCandidateUnsafe(
        context: Context,
        candidate: File,
        allowFixedLegacyName: Boolean = false,
    ): LegacySourceReclaimEntry {
        val canonical =
            runCatching {
                val canonicalFile = candidate.canonicalFile
                val currentRoot = persistentDraftDirectory(context).canonicalFile
                canonicalFile.takeIf { file ->
                    file.parentFile == currentRoot &&
                        LegacyDraftSourceOwnership.isOwnedSourceName(file.name)
                }
            }.getOrNull()
            ?: return LegacySourceReclaimEntry(candidate.absolutePath, LegacySourceReclaimDisposition.FAILED)

        if (canonical.name == LEGACY_FIXED_SOURCE_NAME && !allowFixedLegacyName) {
            return LegacySourceReclaimEntry(
                canonical.absolutePath,
                LegacySourceReclaimDisposition.PRESERVED_MIGRATION_COMPATIBILITY,
            )
        }

        // Authoritative persistent-root reread under the caller's write lock.
        val pointerPath =
            runCatching {
                context
                    .getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
                    .let { safeDraftPreferenceString(it, KEY_DRAFT_SOURCE) }
                    ?.let(::File)
                    ?.let { runCatching { it.canonicalPath }.getOrNull() }
            }.getOrNull()
        if (pointerPath != null && pointerPath == canonical.path) {
            return LegacySourceReclaimEntry(
                canonical.absolutePath,
                LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER,
            )
        }

        return when (LegacyDraftSourceOwnership.deleteIfUnowned(canonical)) {
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_OPERATION ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.PRESERVED_OPERATION)
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_LIVE_DOCUMENT ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.PRESERVED_LIVE_DOCUMENT)
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_LIVE_VIEWMODEL ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.PRESERVED_LIVE_VIEWMODEL)
            LegacyDraftSourceOwnership.DeleteResult.DELETED ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.DELETED)
            LegacyDraftSourceOwnership.DeleteResult.ALREADY_ABSENT ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.ALREADY_ABSENT)
            LegacyDraftSourceOwnership.DeleteResult.FAILED ->
                LegacySourceReclaimEntry(canonical.absolutePath, LegacySourceReclaimDisposition.FAILED)
        }
    }

    /**
     * Classifies every owned legacy source candidate in drafts/current.
     * Caller MUST hold the DraftStorageCoordinator global write lock.
     * Never throws; per-candidate failures are reported truthfully.
     */
    fun sweepObsoleteSourcesUnsafe(
        context: Context,
        allowFixedLegacyName: Boolean = false,
    ): List<LegacySourceReclaimEntry> {
        val root = runCatching { persistentDraftDirectory(context).canonicalFile }.getOrNull()
            ?: return emptyList()
        val listed = runCatching { root.listFiles()?.toList() }.getOrNull().orEmpty()
        return listed.mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (canonical.parentFile != root || !canonical.isFile) return@mapNotNull null
            if (!LegacyDraftSourceOwnership.isOwnedSourceName(canonical.name)) return@mapNotNull null
            reclaimCandidateUnsafe(context, canonical, allowFixedLegacyName)
        }
    }

    private const val LEGACY_FIXED_SOURCE_NAME = "source.img"
}
