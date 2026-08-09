package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicLong

/**
 * Exception thrown when saved-export history persistence fails after a
 * published image. Carrying the original cause lets the UI report a truthful
 * partial-success message without leaking internal stack text.
 */
internal class SavedExportHistoryPersistenceException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * Result of a saved-export history mutation. The ViewModel consumes
 * [items] only when [revision] still matches the global revision counter,
 * which preserves the existing global-revision arbitration: a late completion
 * from an older operation cannot overwrite a newer saved-export list.
 */
internal data class SavedExportHistoryMutation(
    val revision: Long,
    val items: List<SavedExport>,
    val removedUris: Set<String>,
    val retention: ExportHistoryRetention? = null,
)

/**
 * Production owner of the saved-export history SharedPreferences and the
 * global history-revision counter.
 *
 * Every history mutation routes through this store so the merge algorithm is
 * directly testable without reproducing ViewModel coroutine/state plumbing.
 * The store preserves the existing 60-entry maximum, the existing retention
 * semantics, the existing initialized-history guard against unintentional
 * repopulation, and the existing thumbnail-invalidation provenance.
 *
 * The store deliberately does NOT delete MediaStore content. Gallery rows are
 * the publication commit point owned by the export pipeline.
 *
 * Public members are `open` strictly so the production export test seam can
 * inject a thin test fake that observes/fails a single mutation; the merge
 * algorithm itself is never reimplemented in tests.
 */
internal open class SavedExportHistoryStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val mutationLock = Any()
    private val revisionCounter = AtomicLong(0L)

    /** Current global history revision (monotonic across all mutations). */
    open val revision: Long get() = revisionCounter.get()

    /**
     * Records [item] and applies retention, returning the committed list and
     * the URIs that fell out of history (for thumbnail invalidation).
     *
     * A duplicate URI replaces the stale entry, preserving the existing
     * "newest-first, one per URI" contract. The 60-entry maximum is enforced
     * before retention pruning so retention never expands the list.
     */
    open fun commit(
        item: SavedExport,
        retention: ExportHistoryRetention,
    ): SavedExportHistoryMutation = synchronized(mutationLock) {
        val previous = load()
        val previousUris = previous.mapTo(mutableSetOf()) { it.uriString }
        val next =
            (listOf(item) + previous.filter { it.uriString != item.uriString })
                .take(MAX_SAVED_EXPORTS)
        val pruned = pruneByRetention(next, retention)
        requireCommit(pruned)
        val nextRevision = revisionCounter.incrementAndGet()
        val nextUris = pruned.mapTo(mutableSetOf()) { it.uriString }
        SavedExportHistoryMutation(
            revision = nextRevision,
            items = pruned,
            removedUris = previousUris - nextUris,
        )
    }

    /** Clears the saved-export preference; preserves the initialized flag. */
    open fun clear(): SavedExportHistoryMutation = synchronized(mutationLock) {
        val current = load()
        requireClear()
        val nextRevision = revisionCounter.incrementAndGet()
        SavedExportHistoryMutation(
            revision = nextRevision,
            items = emptyList(),
            removedUris = current.mapTo(mutableSetOf()) { it.uriString },
        )
    }

    /** Removes a single URI from the saved-export preference. */
    open fun remove(uriString: String): SavedExportHistoryMutation = synchronized(mutationLock) {
        val current = load()
        val next = current.filterNot { it.uriString == uriString }
        requireCommit(next)
        val nextRevision = revisionCounter.incrementAndGet()
        val removed = if (next.size != current.size) setOf(uriString) else emptySet()
        SavedExportHistoryMutation(
            revision = nextRevision,
            items = next,
            removedUris = removed,
        )
    }

    /**
     * Re-prunes the current committed history for [retention]. Used by the
     * retention setter so a tightened window drops stale entries
     * atomically with a single revision bump.
     */
    open fun prune(retention: ExportHistoryRetention): SavedExportHistoryMutation =
        synchronized(mutationLock) {
            val previous = load()
            val previousUris = previous.mapTo(mutableSetOf()) { it.uriString }
            val pruned = pruneByRetention(previous, retention)
            val nextRevision = revisionCounter.incrementAndGet()
            val nextUris = pruned.mapTo(mutableSetOf()) { it.uriString }
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = pruned,
                removedUris = previousUris - nextUris,
                retention = retention,
            )
        }

    /** Loads the current committed history from preferences. */
    open fun load(): List<SavedExport> {
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SAVED_EXPORTS, null) ?: return emptyList()
        return raw.lines().mapNotNull { decodeSavedExport(it) }
    }

    /**
     * Loads the seeded history, rebuilding from MediaStore exactly once when the
     * initialized guard is absent. The rebuild accepts only committed
     * KeplerStudio rows (see [rebuildFromMediaStore]) and never deletes gallery
     * content. Subsequent startups honor the initialized flag so an
     * intentionally cleared history is not repopulated.
     */
    open fun loadOrRebuild(retention: ExportHistoryRetention): List<SavedExport> {
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val initialized =
            prefs.getBoolean(KEY_SAVED_EXPORTS_INITIALIZED, false) ||
                prefs.contains(KEY_SAVED_EXPORTS)
        val seed = if (initialized) load() else rebuildFromMediaStore()
        return pruneByRetention(seed, retention)
    }

    /**
     * Version of [loadOrRebuild] that bumps the global revision and returns the
     * removed-URI set, so startup initialization can settle thumbnails and UI
     * atomically with the same global-revision arbitration as ordinary
     * mutations.
     */
    open fun loadOrRebuildWithMutation(
        retention: ExportHistoryRetention,
    ): SavedExportHistoryMutation = synchronized(mutationLock) {
        val previous = load()
        val previousUris = previous.mapTo(mutableSetOf()) { it.uriString }
        val next = loadOrRebuild(retention)
        val nextRevision = revisionCounter.incrementAndGet()
        val nextUris = next.mapTo(mutableSetOf()) { it.uriString }
        SavedExportHistoryMutation(
            revision = nextRevision,
            items = next,
            removedUris = previousUris - nextUris,
            retention = retention,
        )
    }

    /** Persists the retention choice. */
    open fun saveRetention(retention: ExportHistoryRetention) {
        if (!appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EXPORT_HISTORY_RETENTION, retention.name)
                .commit()
        ) {
            error("failed to persist export history retention")
        }
    }

    /** Loads the persisted retention choice. */
    open fun loadRetention(): ExportHistoryRetention =
        enumValueOrDefault(
            appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_EXPORT_HISTORY_RETENTION, null),
            ExportHistoryRetention.Never,
        )

    /**
     * Rebuilds saved-export history from the MediaStore.
     *
     * Contract: the rebuilt history represents published/committed exports
     * only — never an in-progress pending row. On API 29+ we explicitly
     * project and filter `IS_PENDING == 0`, because an app-owned pending row
     * may otherwise be returned by the provider query. The KeplerStudio
     * relative-path match is tightened to the exact directory so a sibling
     * such as `Pictures/KeplerStudioBackup` is never accepted merely because
     * its name shares the `KeplerStudio` prefix.
     *
     * The rebuild never deletes gallery content and caps the result at
     * [MAX_SAVED_EXPORTS] entries, newest first.
     */
    open fun rebuildFromMediaStore(): List<SavedExport> {
        val projection =
            buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                add(MediaStore.Images.Media.MIME_TYPE)
                add(MediaStore.Images.Media.WIDTH)
                add(MediaStore.Images.Media.HEIGHT)
                add(MediaStore.Images.Media.DATE_ADDED)
                add(MediaStore.Images.Media.RELATIVE_PATH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.IS_PENDING)
                }
            }.toTypedArray()
        val selection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            } else {
                null
            }
        val items = mutableListOf<SavedExport>()
        appContext.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val relativePathIndex =
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val isPendingIndex = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || dateAddedIndex < 0) return@use
                while (cursor.moveToNext() && items.size < MAX_SAVED_EXPORTS) {
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    val relativePath =
                        if (relativePathIndex >= 0) cursor.getString(relativePathIndex).orEmpty()
                        else ""
                    val inKeplerStudio = isKeplerStudioExportPath(relativePath)
                    if (!inKeplerStudio) continue
                    if (isPendingIndex >= 0) {
                        val pending = cursor.getInt(isPendingIndex)
                        if (pending != 0) continue
                    }
                    val id = cursor.getLong(idIndex)
                    val safeDisplayName = displayName.ifBlank { "KeplerStudio_$id" }
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    val width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0
                    val height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
                    val dateAddedSeconds = cursor.getLong(dateAddedIndex)
                    items +=
                        SavedExport(
                            displayName = safeDisplayName,
                            uriString =
                                Uri.withAppendedPath(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        id.toString(),
                                    )
                                    .toString(),
                            formatLabel = mimeTypeToExportLabel(mimeType, safeDisplayName),
                            resolutionLabel =
                                if (width > 0 && height > 0) "${width}x${height}" else "원본",
                            timestampMillis =
                                if (dateAddedSeconds > 0L) dateAddedSeconds * 1000L
                                else clock(),
                        )
                }
            }
        if (items.isNotEmpty()) requireCommit(items)
        return items
    }

    private fun pruneByRetention(
        items: List<SavedExport>,
        retention: ExportHistoryRetention,
    ): List<SavedExport> {
        val days = retention.days
        val pruned =
            if (days == null) {
                items
            } else {
                val cutoff = clock() - days * 24L * 60L * 60L * 1000L
                items.filter { it.timestampMillis >= cutoff }
            }
        requireCommit(pruned)
        return pruned
    }

    private fun requireCommit(items: List<SavedExport>) {
        check(
            appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_EXPORTS, items.joinToString("\n") { encodeSavedExport(it) })
                .putBoolean(KEY_SAVED_EXPORTS_INITIALIZED, true)
                .commit()
        ) {
            "failed to persist saved export history"
        }
    }

    private fun requireClear() {
        check(
            appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_EXPORTS, "")
                .putBoolean(KEY_SAVED_EXPORTS_INITIALIZED, true)
                .commit()
        ) {
            "failed to clear saved export history"
        }
    }

    private fun encodeSavedExport(item: SavedExport): String =
        listOf(
                item.displayName,
                item.uriString,
                item.formatLabel,
                item.resolutionLabel,
                item.timestampMillis.toString(),
            )
            .joinToString("|") { it.replace("|", " ").replace("\n", " ") }

    private fun decodeSavedExport(raw: String): SavedExport? {
        val parts = raw.split("|")
        if (parts.size != 5) return null
        return SavedExport(
            displayName = parts[0],
            uriString = parts[1],
            formatLabel = parts[2],
            resolutionLabel = parts[3],
            timestampMillis = parts[4].toLongOrNull() ?: return null,
        )
    }

    private fun mimeTypeToExportLabel(mimeType: String, displayName: String): String =
        when {
            mimeType.equals("image/jpeg", ignoreCase = true) -> "JPEG"
            mimeType.equals("image/png", ignoreCase = true) -> "PNG"
            mimeType.equals("image/webp", ignoreCase = true) -> "WebP"
            mimeType.equals("image/heic", ignoreCase = true) ||
                mimeType.equals("image/heif", ignoreCase = true) -> "HEIF"
            displayName.endsWith(".jpg", ignoreCase = true) ||
                displayName.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
            displayName.endsWith(".png", ignoreCase = true) -> "PNG"
            displayName.endsWith(".webp", ignoreCase = true) -> "WebP"
            displayName.endsWith(".heic", ignoreCase = true) ||
                displayName.endsWith(".heif", ignoreCase = true) -> "HEIF"
            else -> "사진"
        }

    private fun enumValueOrDefault(name: String?, default: ExportHistoryRetention) =
        runCatching { enumValueOf<ExportHistoryRetention>(name ?: return default) }
            .getOrDefault(default)

    companion object {
        /** Path the exported images live under, relative to thePictures root. */
        internal val EXPORT_RELATIVE_PATH = "${android.os.Environment.DIRECTORY_PICTURES}/KeplerStudio"

        internal const val MAX_SAVED_EXPORTS = 60

        internal const val PREF_NAME = "kepler_studio_editor"
        internal const val KEY_SAVED_EXPORTS = "saved_exports"
        internal const val KEY_SAVED_EXPORTS_INITIALIZED = "saved_exports_initialized"
        internal const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"

        /**
         * True only for the exact `Pictures/KeplerStudio` directory (case-sensitive
         * match followed by either the end of the path or a path separator). A
         * sibling such as `Pictures/KeplerStudioBackup` is rejected.
         */
        internal fun isKeplerStudioExportPath(relativePath: String): Boolean {
            if (!relativePath.startsWith(EXPORT_RELATIVE_PATH)) return false
            if (relativePath.length == EXPORT_RELATIVE_PATH.length) return true
            return relativePath[EXPORT_RELATIVE_PATH.length] == '/'
        }
    }
}
