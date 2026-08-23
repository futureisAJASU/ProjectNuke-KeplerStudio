package com.projectnuke.keplerstudio.editor

import java.io.File

/**
 * Current-process ownership for legacy Draft compatibility sources under
 * `filesDir/drafts/current`.
 *
 * One legacy source file can be claimed by MULTIPLE independent live owners at
 * the same time: two ViewModels may legitimately reference the same physical
 * file, and an in-flight save or legacy restore adds another transient claim.
 * The registry therefore stores one explicit reference per owner instead of a
 * plain presence set: releasing one owner's reference never drops another
 * owner's claim on the same canonical path.
 *
 * Lock-order contract (MUST be preserved everywhere):
 *   ViewModel-local Draft operation ownership
 *     -> DraftStorageCoordinator globalStorageLock
 *       -> LegacyDraftSourceOwnership monitor
 *
 * This monitor is a leaf: it must never call back into DraftStorageCoordinator
 * or any other component while held, and no code may acquire this monitor and
 * then request the Coordinator lock. Canonicalization performs bounded path
 * IO inside the monitor, mirroring IncomingSourceLiveOwnership and
 * RestoredWorkingSourceOwnership.
 *
 * In-memory roots intentionally disappear on process death; the startup
 * reconciler remains the process-death authority for this family and keeps the
 * conservative IGNORED_UNKNOWN disposition until ownership-aware reclamation
 * is introduced separately.
 */
internal object LegacyDraftSourceOwnership {

    /** Live root classes represented by this registry. */
    internal enum class RootKind {
        /** Visible Draft root ([EditorUiState.draftSourcePath]) of one ViewModel. */
        VISIBLE_DRAFT,

        /** Document root ([EditorUiState.sourcePath]) of one ViewModel. */
        DOCUMENT,

        /** One in-flight operation root (Draft save payload / legacy restore decode). */
        OPERATION,
    }

    /** Unique identity of one owner: a ViewModel instance or one in-flight operation. */
    internal class OwnerKey private constructor() {
        internal val debugId: Int = nextId.incrementAndGet()

        override fun toString(): String = "Owner#${debugId}"

        internal companion object {
            private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
            fun create(): OwnerKey = OwnerKey()
        }
    }

    private data class RootRef(val owner: OwnerKey, val kind: RootKind)

    internal enum class DeleteResult {
        PRESERVED_LIVE_VIEWMODEL,
        PRESERVED_LIVE_DOCUMENT,
        PRESERVED_OPERATION,
        DELETED,
        ALREADY_ABSENT,
        FAILED,
    }

    private val lock = Any()

    // Canonical path -> distinct live references currently claiming it.
    private val refsByPath = HashMap<String, MutableSet<RootRef>>()

    // Owner -> canonical paths that owner currently claims (scoped teardown).
    private val pathsByOwner = HashMap<OwnerKey, MutableSet<String>>()

    /**
     * Naming contract for legacy compatibility sources. The fixed historical
     * name is included because `KEY_DRAFT_SOURCE` can still point at it until
     * migration rewrites the pointer to an owned `source_*.img`.
     */
    fun isOwnedSourceName(name: String): Boolean =
        name == "source.img" ||
            (name.startsWith("source_") && name.endsWith(".img") && name.length > "source_.img".length)

    /** Registers one live reference for [owner]. No-op for null paths. */
    fun acquire(owner: OwnerKey, kind: RootKind, path: String?) {
        path ?: return
        synchronized(lock) {
            val canonicalPath = canonical(path)
            if (!contains(owner, kind, canonicalPath)) {
                refsByPath.getOrPut(canonicalPath) { HashSet() }.add(RootRef(owner, kind))
                pathsByOwner.getOrPut(owner) { HashSet() }.add(canonicalPath)
            }
        }
    }

    /**
     * Removes exactly the [owner]/[kind] reference for [path]. Other owners'
     * references to the same canonical path are untouched. No-op for null
     * paths or a reference that was never registered.
     */
    fun release(owner: OwnerKey, kind: RootKind, path: String?) {
        path ?: return
        synchronized(lock) {
            val canonicalPath = canonical(path)
            val refs = refsByPath[canonicalPath] ?: return
            refs.remove(RootRef(owner, kind))
            if (refs.isEmpty()) refsByPath.remove(canonicalPath)
            if (!holdsAnyRef(owner, canonicalPath)) {
                pathsByOwner[owner]?.remove(canonicalPath)
                pathsByOwner[owner]?.takeIf { it.isEmpty() }?.let { pathsByOwner.remove(owner) }
            }
        }
    }

    /**
     * Atomically moves one [owner]/[kind] claim from [previousPath] to
     * [nextPath] inside a single critical section so no observer can observe
     * the unowned interval. Only this owner's claim moves; other owners keep
     * their own claims. Null sides are skipped; identical canonical sides are
     * treated as acquire-only.
     */
    fun replace(owner: OwnerKey, kind: RootKind, previousPath: String?, nextPath: String?) {
        if (previousPath == null && nextPath == null) return
        if (previousPath != null && nextPath != null && canonical(previousPath) == canonical(nextPath)) {
            acquire(owner, kind, nextPath)
            return
        }
        synchronized(lock) {
            previousPath?.let { previous ->
                val previousCanonical = canonical(previous)
                refsByPath[previousCanonical]?.let { refs ->
                    refs.remove(RootRef(owner, kind))
                    if (refs.isEmpty()) refsByPath.remove(previousCanonical)
                }
                // The owner may still hold other kinds on the same canonical
                // path (a legacy document is also the visible Draft root);
                // drop the index entry only when no claim survives.
                if (!holdsAnyRef(owner, previousCanonical)) {
                    pathsByOwner[owner]?.remove(previousCanonical)
                }
            }
            nextPath?.let { next ->
                val nextCanonical = canonical(next)
                if (!contains(owner, kind, nextCanonical)) {
                    refsByPath.getOrPut(nextCanonical) { HashSet() }.add(RootRef(owner, kind))
                    pathsByOwner.getOrPut(owner) { HashSet() }.add(nextCanonical)
                }
            }
        }
    }

    /**
     * Drops EVERY reference held by [owner] in one critical section. Used by
     * ViewModel teardown and terminal operation cleanup; other owners'
     * references always survive.
     */
    fun releaseOwner(owner: OwnerKey) {
        synchronized(lock) {
            val owned = pathsByOwner.remove(owner) ?: return
            owned.forEach { canonicalPath ->
                refsByPath[canonicalPath]?.let { refs ->
                    refs.removeAll { it.owner === owner }
                    if (refs.isEmpty()) refsByPath.remove(canonicalPath)
                }
            }
        }
    }

    /**
     * Atomically converts an operation claim into the adopting ViewModel's
     * DOCUMENT + VISIBLE_DRAFT claims in one critical section: the restore's
     * transient root ends exactly where the document/visible roots begin, with
     * no unowned interval.
     */
    fun transferOperationToViewModel(operationOwner: OwnerKey, viewModelOwner: OwnerKey, path: String) {
        synchronized(lock) {
            val canonicalPath = canonical(path)
            refsByPath[canonicalPath]?.let { refs ->
                refs.removeAll { it.owner === operationOwner }
                if (refs.isEmpty()) refsByPath.remove(canonicalPath)
            }
            pathsByOwner[operationOwner]?.remove(canonicalPath)
            pathsByOwner[operationOwner]?.takeIf { it.isEmpty() }?.let { pathsByOwner.remove(operationOwner) }
            // The adopting ViewModel takes over exactly the live document and
            // visible Draft roots; never an operation root.
            listOf(RootKind.DOCUMENT, RootKind.VISIBLE_DRAFT).forEach { kind ->
                if (!contains(viewModelOwner, kind, canonicalPath)) {
                    refsByPath.getOrPut(canonicalPath) { HashSet() }.add(RootRef(viewModelOwner, kind))
                    pathsByOwner.getOrPut(viewModelOwner) { HashSet() }.add(canonicalPath)
                }
            }
        }
    }

    /**
     * Ownership check and physical deletion share one critical section, so a
     * stale listing can never delete a path acquired after the listing was
     * captured. Never throws. Callers remain responsible for enforcing the
     * family naming/containment contract before nominating candidates.
     */
    fun deleteIfUnowned(file: File): DeleteResult = synchronized(lock) {
        val canonicalPath = canonical(file.absolutePath)
        when {
            refsByPath[canonicalPath].isNullOrEmpty() -> Unit
            holdsKind(canonicalPath, RootKind.OPERATION) -> return DeleteResult.PRESERVED_OPERATION
            holdsKind(canonicalPath, RootKind.DOCUMENT) -> return DeleteResult.PRESERVED_LIVE_DOCUMENT
            else -> return DeleteResult.PRESERVED_LIVE_VIEWMODEL
        }
        if (!file.exists()) return DeleteResult.ALREADY_ABSENT
        if (file.delete()) DeleteResult.DELETED else DeleteResult.FAILED
    }

    // ------------------------------------------------------------------
    // Test observation
    // ------------------------------------------------------------------

    internal fun isProtectedForTest(file: File): Boolean = synchronized(lock) {
        !refsByPath[canonical(file.absolutePath)].isNullOrEmpty()
    }

    internal fun kindsForTest(file: File): Set<RootKind> = synchronized(lock) {
        refsByPath[canonical(file.absolutePath)].orEmpty().map(RootRef::kind).toSet()
    }

    internal fun protectedPathCountForTest(): Int = synchronized(lock) { refsByPath.size }

    internal fun protectedPathsForTest(): Set<String> = synchronized(lock) { refsByPath.keys.toSet() }

    internal fun refsForTest(): Map<String, Set<String>> = synchronized(lock) {
        refsByPath.mapValues { (_, refs) ->
            refs.map { "${it.kind}@${it.owner.debugId}" }.toSet()
        }
    }

    internal fun clearForTest() = synchronized(lock) {
        refsByPath.clear()
        pathsByOwner.clear()
    }

    private fun contains(owner: OwnerKey, kind: RootKind, canonicalPath: String): Boolean =
        refsByPath[canonicalPath]?.any { it.owner === owner && it.kind == kind } == true

    private fun holdsAnyRef(owner: OwnerKey, canonicalPath: String): Boolean =
        refsByPath[canonicalPath]?.any { it.owner === owner } == true

    private fun holdsKind(canonicalPath: String, kind: RootKind): Boolean =
        refsByPath[canonicalPath]?.any { it.kind == kind } == true

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { path }
}
