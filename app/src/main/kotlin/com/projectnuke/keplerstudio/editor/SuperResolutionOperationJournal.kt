package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

internal enum class SuperResolutionJournalPhase {
    ADMITTED,
    SOURCE_PREPARING,
    RGB8_CREATED,
    PENDING_INSERTED,
    ENCODING,
    BEFORE_PUBLICATION,
    PUBLISHED,
}

internal data class SuperResolutionDebtRecord(
    val operationId: Long,
    val phase: SuperResolutionJournalPhase,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val rgb8Path: String? = null,
    val rgb8StagingPath: String? = null,
    val pendingUri: String? = null,
    val publicPublicationCommitted: Boolean = false,
    val version: Int = JOURNAL_VERSION,
)

/** Synchronous SharedPreferences journal for the small, irreversible ownership boundaries. */
internal class SuperResolutionOperationJournal(private val context: Context) {
    private val preferences =
        context.getSharedPreferences(
            SuperResolutionMediaProcessingService.JOURNAL_PREFS,
            Context.MODE_PRIVATE,
        )

    fun read(): SuperResolutionDebtRecord? {
        val operationId = preferences.getLong(KEY_OPERATION_ID, -1L)
        if (operationId <= 0L) return null
        val phase =
            runCatching {
                SuperResolutionJournalPhase.valueOf(
                    preferences.getString(KEY_PHASE, null) ?: return null,
                )
            }.getOrNull() ?: return null
        return SuperResolutionDebtRecord(
            operationId = operationId,
            phase = phase,
            startedAtMillis = preferences.getLong(KEY_STARTED_AT, 0L),
            updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
            rgb8Path = preferences.getString(KEY_RGB8_PATH, null),
            rgb8StagingPath = preferences.getString(KEY_RGB8_STAGING_PATH, null),
            pendingUri = preferences.getString(KEY_PENDING_URI, null),
            publicPublicationCommitted = preferences.getBoolean(KEY_PUBLIC_COMMITTED, false),
            version = preferences.getInt(KEY_VERSION, JOURNAL_VERSION),
        )
    }

    fun hasData(): Boolean = preferences.contains(KEY_OPERATION_ID)

    fun write(record: SuperResolutionDebtRecord) {
        check(
            preferences.edit()
                .putLong(KEY_OPERATION_ID, record.operationId)
                .putString(KEY_PHASE, record.phase.name)
                .putLong(KEY_STARTED_AT, record.startedAtMillis)
                .putLong(KEY_UPDATED_AT, record.updatedAtMillis)
                .putString(KEY_RGB8_PATH, record.rgb8Path)
                .putString(KEY_RGB8_STAGING_PATH, record.rgb8StagingPath)
                .putString(KEY_PENDING_URI, record.pendingUri)
                .putBoolean(KEY_PUBLIC_COMMITTED, record.publicPublicationCommitted)
                .putInt(KEY_VERSION, record.version)
                .commit(),
        ) { "could not durably persist SR operation journal" }
    }

    fun clear(operationId: Long) {
        if (read()?.operationId != operationId) return
        check(preferences.edit().clear().commit()) { "could not durably clear SR operation journal" }
    }
}

internal fun SuperResolutionDebtRecord.withEvent(
    phase: SuperResolutionJournalPhase,
    rgb8Path: String? = this.rgb8Path,
    rgb8StagingPath: String? = this.rgb8StagingPath,
    pendingUri: String? = this.pendingUri,
    publicPublicationCommitted: Boolean = this.publicPublicationCommitted,
    nowMillis: Long = System.currentTimeMillis(),
): SuperResolutionDebtRecord =
    copy(
        phase = phase,
        updatedAtMillis = nowMillis,
        rgb8Path = rgb8Path,
        rgb8StagingPath = rgb8StagingPath,
        pendingUri = pendingUri,
        publicPublicationCommitted = publicPublicationCommitted,
    )

internal fun validateOwnedRgb8Path(context: Context, path: String): Boolean {
    val cache = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return false
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    return candidate.parentFile == cache &&
        candidate.name.startsWith("sr6_") &&
        (candidate.name.endsWith(".rgb8") ||
            (candidate.name.contains(".rgb8.") && candidate.name.endsWith(".tmp")))
}

internal suspend fun recoverExactSuperResolutionDebt(
    context: Context,
    journal: SuperResolutionOperationJournal = SuperResolutionOperationJournal(context),
    pendingState: (Uri) -> Boolean? = { uri -> queryPendingMediaStoreRow(context, uri) },
    deletePending: suspend (Uri) -> Int = { uri -> context.contentResolver.delete(uri, null, null) },
    rowExists: (Uri) -> Boolean? = { uri -> queryMediaStoreRow(context, uri) },
): Boolean {
    val record = journal.read() ?: return !journal.hasData()
    var settled = true
    listOfNotNull(record.rgb8Path, record.rgb8StagingPath).distinct().forEach { path ->
        if (!validateOwnedRgb8Path(context, path)) {
            settled = false
        } else {
            val file = File(path)
            if (file.exists() && (!file.delete() || file.exists())) settled = false
        }
    }
    val pending = record.pendingUri?.let(Uri::parse)
    if (pending != null && !record.publicPublicationCommitted) {
        val appOwnedMediaStoreUri = pending.scheme == "content" && pending.authority == "media"
        val pendingRowState = pendingState(pending)
        if (!appOwnedMediaStoreUri) {
            settled = false
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pendingRowState == null) {
            settled = false
        } else if (pendingRowState == false) {
            // A journaled row that is no longer pending has crossed the public boundary. It is
            // preserved and the journal is advanced so a crash between publish and the event
            // callback cannot turn a public Gallery item into cleanup debt.
            when (rowExists(pending)) {
                true ->
                journal.write(
                    record.copy(
                        phase = SuperResolutionJournalPhase.PUBLISHED,
                        publicPublicationCommitted = true,
                        rgb8StagingPath = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                false -> Unit
                null -> settled = false
            }
        } else {
            val deleted = runCatching { deletePending(pending) }.getOrDefault(0)
            if (deleted <= 0 || rowExists(pending) != false) settled = false
        }
    }
    if (settled) journal.clear(record.operationId)
    return settled
}

private fun queryPendingMediaStoreRow(context: Context, uri: Uri): Boolean? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        } ?: false
    }.getOrNull()

private fun queryMediaStoreRow(context: Context, uri: Uri): Boolean? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrNull()

internal const val JOURNAL_VERSION = 2
private const val KEY_OPERATION_ID = "operation_id"
private const val KEY_PHASE = "phase"
private const val KEY_STARTED_AT = "started_at"
private const val KEY_UPDATED_AT = "updated_at"
private const val KEY_RGB8_PATH = "rgb8_path"
private const val KEY_RGB8_STAGING_PATH = "rgb8_staging_path"
private const val KEY_PENDING_URI = "pending_uri"
private const val KEY_PUBLIC_COMMITTED = "public_publication_committed"
private const val KEY_VERSION = "version"
