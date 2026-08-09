package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The only persistence boundary used by [SavedExportHistoryStore]. */
internal data class SavedExportPersistedState(
    val rawHistory: String?,
    val initialized: Boolean,
    val retention: ExportHistoryRetention,
)

internal interface SavedExportHistoryPersistence {
    suspend fun readState(): SavedExportPersistedState
    suspend fun updateState(
        transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState,
    ): SavedExportPersistedState
}

internal class PreferencesDataStoreSavedExportHistoryPersistence(
    private val dataStore: DataStore<Preferences>,
) : SavedExportHistoryPersistence {
    constructor(context: Context) : this(context.applicationContext.savedExportHistoryDataStore)


    override suspend fun readState(): SavedExportPersistedState =
        dataStore.data.first().toState()

    override suspend fun updateState(
        transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState,
    ): SavedExportPersistedState {
        var nextState: SavedExportPersistedState? = null
        dataStore.updateData { current ->
            val next = transform(current.toState())
            nextState = next
            next.toPreferences()
        }
        return checkNotNull(nextState)
    }
}

private val Context.savedExportHistoryDataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "kepler_studio_saved_export_history",
        produceMigrations = ::savedExportHistoryMigrations,
    )

private fun savedExportHistoryMigrations(context: Context): List<DataMigration<Preferences>> =
    listOf(
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = SavedExportHistoryStore.PREF_NAME,
            keysToMigrate = SavedExportHistoryStore.LEGACY_KEYS,
        ),
    )

/** Real migration/factory seam used by production-faithful DataStore tests. */
internal fun createSavedExportHistoryDataStoreForTest(
    context: Context,
    file: File,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { file },
        migrations = savedExportHistoryMigrations(context),
    )

private fun Preferences.toState(): SavedExportPersistedState =
    SavedExportPersistedState(
        rawHistory = this[SavedExportHistoryStore.SAVED_EXPORTS_KEY],
        initialized = this[SavedExportHistoryStore.SAVED_EXPORTS_INITIALIZED_KEY] ?: false,
        retention =
            runCatching {
                enumValueOf<ExportHistoryRetention>(
                    this[SavedExportHistoryStore.EXPORT_HISTORY_RETENTION_KEY]
                        ?: ExportHistoryRetention.Never.name,
                )
            }.getOrDefault(ExportHistoryRetention.Never),
    )

private fun SavedExportPersistedState.toPreferences(): Preferences =
    emptyPreferences().toMutablePreferences().also { preferences ->
        rawHistory?.let { preferences[SavedExportHistoryStore.SAVED_EXPORTS_KEY] = it }
        preferences[SavedExportHistoryStore.SAVED_EXPORTS_INITIALIZED_KEY] = initialized
        preferences[SavedExportHistoryStore.EXPORT_HISTORY_RETENTION_KEY] = retention.name
    }

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
 * Production owner of the saved-export history DataStore and the
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
 * The persistence adapter is injectable so tests can observe or fail one
 * exact write without replacing this store's merge algorithm.
 */
internal class SavedExportHistoryStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistence: SavedExportHistoryPersistence =
        PreferencesDataStoreSavedExportHistoryPersistence(context),
) {
    private val appContext = context.applicationContext
    private val mutationLock = Mutex()
    private val revisionCounter = AtomicLong(0L)

    /** Current global history revision (monotonic across all mutations). */
    val revision: Long get() = revisionCounter.get()

    private suspend fun readPersistedState(): SavedExportPersistedState =
        try {
            persistence.readState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw SavedExportHistoryPersistenceException(
                "failed to read saved export history",
                failure,
            )
        }

    private suspend fun updatePersistedState(
        transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState,
    ): SavedExportPersistedState =
        try {
            persistence.updateState(transform)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw SavedExportHistoryPersistenceException(
                "failed to update saved export history",
                failure,
            )
        }

    /**
     * Records [item] and applies retention, returning the committed list and
     * the URIs that fell out of history (for thumbnail invalidation).
     *
     * A duplicate URI replaces the stale entry, preserving the existing
     * "newest-first, one per URI" contract. The 60-entry maximum is enforced
     * before retention pruning so retention never expands the list.
     */
    suspend fun commit(item: SavedExport): SavedExportHistoryMutation =
        mutationLock.withLock {
            var previous = emptyList<SavedExport>()
            var pruned = emptyList<SavedExport>()
            updatePersistedState { state ->
                previous = decodeHistory(state.rawHistory)
                val next =
                    (listOf(item) + previous.filter { it.uriString != item.uriString })
                        .take(MAX_SAVED_EXPORTS)
                pruned = pruneByRetention(next, state.retention)
                state.copy(rawHistory = encodeHistory(pruned), initialized = true)
            }
            val previousUris = previous.mapTo(mutableSetOf()) { it.uriString }
            val nextUris = pruned.mapTo(mutableSetOf()) { it.uriString }
            val nextRevision = revisionCounter.incrementAndGet()
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = pruned,
                removedUris = previousUris - nextUris,
            )
        }

    /** Clears the saved-export preference; preserves the initialized flag. */
    suspend fun clear(): SavedExportHistoryMutation =
        mutationLock.withLock {
            var current = emptyList<SavedExport>()
            updatePersistedState { state ->
                current = decodeHistory(state.rawHistory)
                state.copy(rawHistory = "", initialized = true)
            }
            val nextRevision = revisionCounter.incrementAndGet()
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = emptyList(),
                removedUris = current.mapTo(mutableSetOf()) { it.uriString },
            )
        }

    /** Removes a single URI from the saved-export preference. */
    suspend fun remove(uriString: String): SavedExportHistoryMutation =
        mutationLock.withLock {
            var current = emptyList<SavedExport>()
            var next = emptyList<SavedExport>()
            updatePersistedState { state ->
                current = decodeHistory(state.rawHistory)
                next = current.filterNot { it.uriString == uriString }
                state.copy(rawHistory = encodeHistory(next), initialized = true)
            }
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
    suspend fun prune(retention: ExportHistoryRetention): SavedExportHistoryMutation =
        mutationLock.withLock {
            var previous = emptyList<SavedExport>()
            var pruned = emptyList<SavedExport>()
            updatePersistedState { state ->
                previous = decodeHistory(state.rawHistory)
                pruned = pruneByRetention(previous, retention)
                state.copy(rawHistory = encodeHistory(pruned), initialized = state.initialized)
            }
            val previousUris = previous.mapTo(mutableSetOf()) { it.uriString }
            val nextRevision = revisionCounter.incrementAndGet()
            val nextUris = pruned.mapTo(mutableSetOf()) { it.uriString }
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = pruned,
                removedUris = previousUris - nextUris,
                retention = retention,
            )
        }

    /** Loads the current committed history from the DataStore. */
    suspend fun load(): List<SavedExport> = decodeHistory(readPersistedState().rawHistory)

    /**
     * Loads the seeded history, rebuilding from MediaStore exactly once when the
     * initialized guard is absent. The rebuild accepts only committed
     * KeplerStudio rows (see [rebuildFromMediaStore]) and never deletes gallery
     * content. Subsequent startups honor the initialized flag so an
     * intentionally cleared history is not repopulated.
     */
    private suspend fun loadOrRebuildItems(
        state: SavedExportPersistedState,
        retention: ExportHistoryRetention,
    ): List<SavedExport> {
        val initialized = state.initialized || state.rawHistory != null
        val seed =
            if (initialized) decodeHistory(state.rawHistory)
            else withContext(Dispatchers.IO) { rebuildFromMediaStore() }
        return pruneByRetention(seed, retention)
    }

    /**
     * Startup version that bumps the global revision and returns the
     * removed-URI set, so startup initialization can settle thumbnails and UI
     * atomically with the same global-revision arbitration as ordinary
     * mutations.
     */
    suspend fun loadOrRebuildWithMutation(
        retention: ExportHistoryRetention? = null,
    ): SavedExportHistoryMutation =
        mutationLock.withLock {
            var previous = emptyList<SavedExport>()
            var next = emptyList<SavedExport>()
            var settledRetention = ExportHistoryRetention.Never
            updatePersistedState { state ->
                previous = decodeHistory(state.rawHistory)
                settledRetention = retention ?: state.retention
                next = loadOrRebuildItems(state, settledRetention)
                state.copy(
                    rawHistory = encodeHistory(next),
                    initialized = true,
                    retention = settledRetention,
                )
            }
            val nextRevision = revisionCounter.incrementAndGet()
            val nextUris = next.mapTo(mutableSetOf()) { it.uriString }
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = next,
                removedUris = previous.mapTo(mutableSetOf()) { it.uriString } - nextUris,
                retention = settledRetention,
            )
        }

    /** Atomically persists the retention choice and its pruned history. */
    suspend fun setRetention(retention: ExportHistoryRetention): SavedExportHistoryMutation =
        mutationLock.withLock {
            var previous = emptyList<SavedExport>()
            var pruned = emptyList<SavedExport>()
            updatePersistedState { state ->
                previous = decodeHistory(state.rawHistory)
                pruned = pruneByRetention(previous, retention)
                state.copy(rawHistory = encodeHistory(pruned), retention = retention)
            }
            val nextRevision = revisionCounter.incrementAndGet()
            SavedExportHistoryMutation(
                revision = nextRevision,
                items = pruned,
                removedUris =
                    previous.mapTo(mutableSetOf()) { it.uriString } -
                        pruned.mapTo(mutableSetOf()) { it.uriString },
                retention = retention,
            )
        }

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
    fun rebuildFromMediaStore(): List<SavedExport> {
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
        return pruned
    }

    private fun encodeHistory(items: List<SavedExport>): String =
        items.joinToString("\n") { encodeSavedExport(it) }

    private fun decodeHistory(raw: String?): List<SavedExport> =
        raw?.lines()?.mapNotNull { decodeSavedExport(it) }.orEmpty()

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

    companion object {
        /** Path the exported images live under, relative to thePictures root. */
        internal val EXPORT_RELATIVE_PATH = "${android.os.Environment.DIRECTORY_PICTURES}/KeplerStudio"

        internal const val MAX_SAVED_EXPORTS = 60

        internal const val PREF_NAME = "kepler_studio_editor"
        internal const val KEY_SAVED_EXPORTS = "saved_exports"
        internal const val KEY_SAVED_EXPORTS_INITIALIZED = "saved_exports_initialized"
        internal const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"

        internal val LEGACY_KEYS =
            setOf(KEY_SAVED_EXPORTS, KEY_SAVED_EXPORTS_INITIALIZED, KEY_EXPORT_HISTORY_RETENTION)

        internal val SAVED_EXPORTS_KEY = stringPreferencesKey(KEY_SAVED_EXPORTS)
        internal val SAVED_EXPORTS_INITIALIZED_KEY =
            booleanPreferencesKey(KEY_SAVED_EXPORTS_INITIALIZED)
        internal val EXPORT_HISTORY_RETENTION_KEY =
            stringPreferencesKey(KEY_EXPORT_HISTORY_RETENTION)

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
