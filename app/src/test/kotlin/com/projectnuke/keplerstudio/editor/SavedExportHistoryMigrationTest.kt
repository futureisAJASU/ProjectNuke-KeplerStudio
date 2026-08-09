package com.projectnuke.keplerstudio.editor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
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
class SavedExportHistoryMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L
    private val legacy =
        context.getSharedPreferences(SavedExportHistoryStore.PREF_NAME, android.content.Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        legacy
            .edit()
            .remove(SavedExportHistoryStore.KEY_SAVED_EXPORTS)
            .remove(SavedExportHistoryStore.KEY_SAVED_EXPORTS_INITIALIZED)
            .remove(SavedExportHistoryStore.KEY_EXPORT_HISTORY_RETENTION)
            .remove("unrelated_editor_preference")
            .commit()
    }

    @Test
    fun existingHistoryInitializedFlagAndRetentionSurviveMigration() = blocking {
        seedLegacy(
            raw = "edited|content://exports/edited|PNG|2 x 2|1700000000000",
            initialized = true,
            retention = ExportHistoryRetention.Days30,
        )

        val dataStore = dataStore()
        val state = dataStore.data.first().toStateForTest()
        val mutation = productionStore(dataStore).loadOrRebuildWithMutation()

        assertEquals("edited|content://exports/edited|PNG|2 x 2|1700000000000", state.rawHistory)
        assertTrue(state.initialized)
        assertEquals(ExportHistoryRetention.Days30, state.retention)
        assertEquals(listOf("edited"), mutation.items.map { it.displayName })
        assertEquals(ExportHistoryRetention.Days30, mutation.retention)
    }

    @Test
    fun migratedExplicitClearRemainsEmptyAndDoesNotRebuildFromMediaStore() = blocking {
        seedLegacy(raw = "", initialized = true, retention = ExportHistoryRetention.Never)
        val provider = registerProvider()
        val store = productionStore(dataStore())

        val mutation = store.loadOrRebuildWithMutation()

        assertTrue(mutation.items.isEmpty())
        assertEquals(0, provider.queryCalls)
    }

    @Test
    fun migratedHistoryWithoutInitializedFlagSurvivesMigration() = blocking {
        legacy
            .edit()
            .putString(
                SavedExportHistoryStore.KEY_SAVED_EXPORTS,
                "legacy|content://exports/legacy|PNG|2 x 2|1700000000000",
            )
            .remove(SavedExportHistoryStore.KEY_SAVED_EXPORTS_INITIALIZED)
            .putString(
                SavedExportHistoryStore.KEY_EXPORT_HISTORY_RETENTION,
                ExportHistoryRetention.Never.name,
            )
            .commit()
        val provider = registerProvider()
        val store = productionStore(dataStore())

        val mutation = store.loadOrRebuildWithMutation()

        assertEquals(listOf("legacy"), mutation.items.map { it.displayName })
        assertEquals(0, provider.queryCalls)
    }

    @Test
    fun noLegacyHistoryRemainsUninitializedAndNormalStartupRebuilds() = blocking {
        val provider = registerProvider()
        val dataStore = dataStore()
        val defaults = dataStore.data.first().toStateForTest()
        val store = productionStore(dataStore)
        val mutation = store.loadOrRebuildWithMutation()

        assertFalse(defaults.initialized)
        assertEquals(listOf("accepted.png"), mutation.items.map { it.displayName })
        assertEquals(1, provider.queryCalls)
    }

    @Test
    fun migrationDoesNotConsumeOrInterpretUnrelatedLegacyPreference() = blocking {
        seedLegacy(raw = "", initialized = true, retention = ExportHistoryRetention.Never)
        legacy.edit().putString("unrelated_editor_preference", "keep-me").commit()

        dataStore().data.first()

        assertEquals("keep-me", legacy.getString("unrelated_editor_preference", null))
    }

    private fun seedLegacy(
        raw: String,
        initialized: Boolean,
        retention: ExportHistoryRetention,
    ) {
        legacy
            .edit()
            .putString(SavedExportHistoryStore.KEY_SAVED_EXPORTS, raw)
            .putBoolean(SavedExportHistoryStore.KEY_SAVED_EXPORTS_INITIALIZED, initialized)
            .putString(SavedExportHistoryStore.KEY_EXPORT_HISTORY_RETENTION, retention.name)
            .commit()
    }

    private fun dataStore() =
        createSavedExportHistoryDataStoreForTest(
            context,
            File(context.cacheDir, "saved-history-${UUID.randomUUID()}.preferences_pb"),
        )

    private fun productionStore(dataStore: androidx.datastore.core.DataStore<Preferences>) =
        SavedExportHistoryStore(
            context,
            clock = { now },
            persistence = PreferencesDataStoreSavedExportHistoryPersistence(dataStore),
        )

    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

    private fun registerProvider(): MigrationMediaProvider {
        val provider = MigrationMediaProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        return provider
    }
}

private fun Preferences.toStateForTest(): SavedExportPersistedState =
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

private class MigrationMediaProvider : ContentProvider() {
    var queryCalls = 0

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queryCalls++
        val columns = projection ?: emptyArray()
        return MatrixCursor(columns).also { cursor ->
            val values: List<Any?> =
                columns.map { column ->
                    when (column) {
                        MediaStore.Images.Media._ID -> 1L
                        MediaStore.Images.Media.DISPLAY_NAME -> "accepted.png"
                        MediaStore.Images.Media.MIME_TYPE -> "image/png"
                        MediaStore.Images.Media.WIDTH -> 2
                        MediaStore.Images.Media.HEIGHT -> 2
                        MediaStore.Images.Media.DATE_ADDED -> 1_700_000_000L
                        MediaStore.Images.Media.RELATIVE_PATH -> "Pictures/KeplerStudio"
                        MediaStore.Images.Media.IS_PENDING -> 0
                        else -> null
                    }
                }
            cursor.addRow(values.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/image"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
}
