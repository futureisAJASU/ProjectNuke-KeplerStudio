package com.projectnuke.keplerstudio.editor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SavedExportHistoryStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var persistence: TestHistoryPersistence
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        persistence = TestHistoryPersistence()
    }

    @Test
    fun firstCommitPersistsOnceAndAdvancesRevision() = blocking {
        val store = store()
        val mutation = store.commit(item("first", now))

        assertEquals(listOf("first"), mutation.items.map { it.displayName })
        assertEquals(1L, mutation.revision)
        assertEquals(1, persistence.updates.get())
    }

    @Test
    fun duplicateUriReplacesOldEntry() = blocking {
        val store = store()
        store.commit(item("old", now - 10, uriName = "same"))
        val mutation = store.commit(item("new", now, uriName = "same"))

        assertEquals(listOf("new"), mutation.items.map { it.displayName })
        assertEquals(emptySet<String>(), mutation.removedUris)
    }

    @Test
    fun commitsRemainNewestFirstDeterministically() = blocking {
        val store = store()
        store.commit(item("old", now - 20))
        store.commit(item("middle", now - 10))
        store.commit(item("new", now))

        assertEquals(listOf("new", "middle", "old"), store.load().map { it.displayName })
    }

    @Test
    fun historyIsCappedAtSixtyEntries() = blocking {
        val store = store()
        repeat(SavedExportHistoryStore.MAX_SAVED_EXPORTS + 5) { index ->
            store.commit(item("$index", now + index))
        }

        assertEquals(SavedExportHistoryStore.MAX_SAVED_EXPORTS, store.load().size)
        assertEquals("64", store.load().first().displayName)
        assertEquals("5", store.load().last().displayName)
    }

    @Test
    fun retentionBoundariesKeepExactCutoffAndRemoveImmediatelyOlder() = blocking {
        val day = 24L * 60L * 60L * 1000L
        for (retention in listOf(
            ExportHistoryRetention.Days7,
            ExportHistoryRetention.Days30,
            ExportHistoryRetention.Days90,
        )) {
            persistence = TestHistoryPersistence()
            val store = store()
            val cutoff = now - retention.days!! * day
            store.commit(item("older", cutoff - 1))
            store.commit(item("cutoff", cutoff))

            val mutation = store.prune(retention)

            assertEquals(listOf("cutoff"), mutation.items.map { it.displayName })
        }
    }

    @Test
    fun neverRetentionKeepsEntries() = blocking {
        val store = store()
        store.commit(item("old", 0L))

        assertEquals(1, store.prune(ExportHistoryRetention.Never).items.size)
    }

    @Test
    fun prunePersistsPrunedListAndReportsRemovedUris() = blocking {
        val store = store()
        store.commit(item("old", now - 8 * 24L * 60L * 60L * 1000L))
        store.commit(item("new", now))
        val updatesBefore = persistence.updates.get()

        val mutation = store.prune(ExportHistoryRetention.Days7)

        assertEquals(listOf("new"), mutation.items.map { it.displayName })
        assertEquals(setOf("content://exports/old"), mutation.removedUris)
        assertEquals(updatesBefore + 1, persistence.updates.get())
    }

    @Test
    fun removedEntriesDoNotReturnWhenRetentionReturnsToNever() = blocking {
        val store = store()
        store.commit(item("old", now - 8 * 24L * 60L * 60L * 1000L))
        store.commit(item("new", now))

        store.prune(ExportHistoryRetention.Days7)
        val restored = store.prune(ExportHistoryRetention.Never)

        assertEquals(listOf("new"), restored.items.map { it.displayName })
    }

    @Test
    fun malformedStoredRowDoesNotDiscardValidNeighbors() = blocking {
        persistence.state =
            persistence.state.copy(
                rawHistory =
                    listOf(
                            encoded("left", "content://exports/left", now - 2),
                            "malformed|row",
                            encoded("right", "content://exports/right", now),
                        )
                        .joinToString("\n"),
            )

        assertEquals(listOf("left", "right"), store().load().map { it.displayName })
    }

    @Test
    fun clearLeavesInitializedHistoryEmptyAndDoesNotDeleteMediaStoreImage() = blocking {
        val store = store()
        store.commit(item("one", now))

        val mutation = store.clear()

        assertTrue(persistence.state.initialized)
        assertTrue(store.load().isEmpty())
        assertEquals(setOf("content://exports/one"), mutation.removedUris)
        assertEquals(2, persistence.updates.get())
    }

    @Test
    fun removePersistsOneTransactionAndReportsRemovedUri() = blocking {
        val store = store()
        store.commit(item("one", now))

        val mutation = store.remove("content://exports/one")

        assertTrue(mutation.items.isEmpty())
        assertEquals(setOf("content://exports/one"), mutation.removedUris)
        assertEquals(2, persistence.updates.get())
    }

    @Test
    fun setRetentionUpdatesHistoryAndRetentionInOneTransaction() = blocking {
        val store = store()
        store.commit(item("old", now - 8 * 24L * 60L * 60L * 1000L))
        store.commit(item("new", now))
        val updatesBefore = persistence.updates.get()

        val mutation = store.setRetention(ExportHistoryRetention.Days7)

        assertEquals(listOf("new"), mutation.items.map { it.displayName })
        assertEquals(ExportHistoryRetention.Days7, persistence.state.retention)
        assertEquals(updatesBefore + 1, persistence.updates.get())
    }

    @Test
    fun failedSetRetentionLeavesHistoryRetentionAndRevisionUnchanged() = blocking {
        val store = store()
        store.commit(item("old", now - 8 * 24L * 60L * 60L * 1000L))
        val previous = persistence.state
        val revision = store.revision
        persistence.failUpdates = true

        try {
            store.setRetention(ExportHistoryRetention.Days7)
            error("setRetention must fail")
        } catch (failure: SavedExportHistoryPersistenceException) {
            assertTrue(failure.cause != null)
        }

        assertEquals(previous, persistence.state)
        assertEquals(revision, store.revision)
    }

    @Test
    fun persistenceFailureLeavesPreviousStateAndRevisionUnchanged() = blocking {
        val store = store()
        store.commit(item("previous", now))
        val previous = persistence.state
        val revision = store.revision
        persistence.failUpdates = true

        try {
            store.commit(item("new", now + 1))
            error("commit must fail")
        } catch (failure: SavedExportHistoryPersistenceException) {
            assertTrue(failure.cause != null)
        }

        assertEquals(revision, store.revision)
        assertEquals(previous, persistence.state)
        assertEquals(listOf("previous"), store.load().map { it.displayName })
    }

    @Test
    fun firstEmptyStartupDurablyInitializesHistory() = blocking {
        persistence = TestHistoryPersistence(initialized = false, raw = null)
        val provider = registerMediaRows(emptyList())
        val mutation = store().loadOrRebuildWithMutation(ExportHistoryRetention.Never)

        assertTrue(mutation.items.isEmpty())
        assertTrue(persistence.state.initialized)
        assertEquals(1, persistence.updates.get())
        assertEquals(1L, mutation.revision)
        assertEquals("${MediaStore.Images.Media.IS_PENDING} = 0", provider.selection)
        assertEquals(0, provider.deleteCalls)
    }

    @Test
    fun explicitClearPreventsLaterStartupRebuild() = blocking {
        persistence = TestHistoryPersistence(initialized = false, raw = null)
        val provider = registerMediaRows(listOf(mediaRow(1, "one.png", exactPath(), 0)))
        val store = store()
        store.loadOrRebuildWithMutation(ExportHistoryRetention.Never)
        store.clear()
        provider.rows += mediaRow(2, "later.png", exactPath(), 0)

        val restarted = SavedExportHistoryStore(context, clock = { now }, persistence = persistence)
        val mutation = restarted.loadOrRebuildWithMutation(ExportHistoryRetention.Never)

        assertTrue(mutation.items.isEmpty())
        assertEquals(0, provider.deleteCalls)
    }

    @Test
    fun firstStartupRebuildsCommittedRowsOnceAndSkipsPendingAndSiblingPath() = blocking {
        persistence = TestHistoryPersistence(initialized = false, raw = null)
        val provider = registerMediaRows(
            listOf(
                mediaRow(1, "pending.png", exactPath(), 1),
                mediaRow(2, "accepted.png", exactPath(), 0),
                mediaRow(3, "backup.png", "Pictures/KeplerStudioBackup", 0),
                mediaRow(4, "slash.png", "Pictures/KeplerStudio/", 0),
            ),
        )

        val mutation = store().loadOrRebuildWithMutation(ExportHistoryRetention.Never)

        assertEquals(listOf("accepted.png", "slash.png"), mutation.items.map { it.displayName })
        assertEquals(1, persistence.updates.get())
        assertEquals(0, provider.deleteCalls)
        assertEquals("${MediaStore.Images.Media.IS_PENDING} = 0", provider.selection)
    }

    @Test
    fun productionPathContractAcceptsOnlyExactDirectoryAndTrailingSlash() {
        assertFalse(SavedExportHistoryStore.isKeplerStudioExportPath("Pictures/KeplerStudioBackup"))
        assertTrue(SavedExportHistoryStore.isKeplerStudioExportPath("Pictures/KeplerStudio"))
        assertTrue(SavedExportHistoryStore.isKeplerStudioExportPath("Pictures/KeplerStudio/"))
    }

    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

    private fun store() =
        SavedExportHistoryStore(context, clock = { now }, persistence = persistence)

    private fun item(name: String, timestamp: Long, uriName: String = name) =
        SavedExport(
            displayName = name,
            uriString = "content://exports/$uriName",
            formatLabel = "PNG",
            resolutionLabel = "2 x 2",
            timestampMillis = timestamp,
        )

    private fun encoded(name: String, uri: String, timestamp: Long) =
        "$name|$uri|PNG|2 x 2|$timestamp"

    private fun exactPath() = "Pictures/KeplerStudio"

    private fun registerMediaRows(rows: List<MediaRow>): TestMediaProvider {
        mediaProvider = TestMediaProvider(rows.toMutableList())
        ShadowContentResolver.registerProviderInternal("media", mediaProvider)
        return mediaProvider
    }

    private lateinit var mediaProvider: TestMediaProvider

    internal data class MediaRow(
        val id: Long,
        val name: String,
        val path: String,
        val pending: Int,
    )

    private fun mediaRow(id: Long, name: String, path: String, pending: Int) =
        MediaRow(id, name, path, pending)
}

private class TestHistoryPersistence(
    initialized: Boolean = true,
    raw: String? = "",
) : SavedExportHistoryPersistence {
    var state =
        SavedExportPersistedState(
            rawHistory = raw,
            initialized = initialized,
            retention = ExportHistoryRetention.Never,
        )
    val updates = AtomicInteger()
    @Volatile var failUpdates = false

    override suspend fun readState(): SavedExportPersistedState = state

    override suspend fun updateState(
        transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState,
    ): SavedExportPersistedState {
        updates.incrementAndGet()
        val next = transform(state)
        if (failUpdates) error("update failure")
        state = next
        return next
    }
}

private class TestMediaProvider(
    val rows: MutableList<SavedExportHistoryStoreTest.MediaRow>,
) : ContentProvider() {
    var selection: String? = null
    var deleteCalls = 0

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        this.selection = selection
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        rows.forEach { row ->
            val values: List<Any?> =
                columns.map { column ->
                    when (column) {
                        MediaStore.Images.Media._ID -> row.id
                        MediaStore.Images.Media.DISPLAY_NAME -> row.name
                        MediaStore.Images.Media.MIME_TYPE -> "image/png"
                        MediaStore.Images.Media.WIDTH -> 2
                        MediaStore.Images.Media.HEIGHT -> 2
                        MediaStore.Images.Media.DATE_ADDED -> 1_700_000_000L
                        MediaStore.Images.Media.RELATIVE_PATH -> row.path
                        MediaStore.Images.Media.IS_PENDING -> row.pending
                        else -> null
                    }
                }
            cursor.addRow(values.toTypedArray())
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/image"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteCalls++
        return 0
    }
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
}
