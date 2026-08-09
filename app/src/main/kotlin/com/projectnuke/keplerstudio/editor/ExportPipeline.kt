package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ExportRowRequest(
    val fileName: String,
    val format: ExportFormat,
)

/** Production-used seam around one pending MediaStore row. */
internal interface ExportRowStore {
    suspend fun insertPending(request: ExportRowRequest): Uri
    suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat)
    suspend fun publish(uri: Uri)
    suspend fun delete(uri: Uri)
}

/**
 * Exact owner of one pending MediaStore row.
 *
 * Publication is the only commit state. A failed rollback remains retryable
 * and idempotent: repeated settlement after publish or after a successful
 * delete is a no-op, while a pending row whose delete failed can be retried by
 * a later owner any number of times. The production pipeline performs a
 * deterministic bounded same-operation retry (one extra attempt in its final
 * settlement path) and, if cleanup still fails, surfaces the failure
 * structurally instead of leaving an undocumented global finalizer assumption.
 */
internal class ExportRowTransaction(
    private val rows: ExportRowStore,
) {
    private sealed interface State {
        data object New : State
        data class Pending(val uri: Uri) : State
        data class Published(val uri: Uri) : State
        data object Settled : State
    }

    private val mutex = Mutex()
    private var state: State = State.New

    suspend fun insert(request: ExportRowRequest): Uri =
        mutex.withLock {
            check(state == State.New) { "export row transaction already started" }
            // Once insertion starts, always recover the returned URI before observing
            // cancellation so a row can never exist outside this owner's state.
            withContext(NonCancellable) {
                rows.insertPending(request)
            }.also { state = State.Pending(it) }
        }

    suspend fun encode(bitmap: Bitmap, format: ExportFormat) {
        mutex.withLock {
            val pending = state as? State.Pending
                ?: error("export row must be pending before encode")
            rows.encode(pending.uri, bitmap, format)
        }
    }

    suspend fun publish(): Uri =
        mutex.withLock {
            val pending = state as? State.Pending
                ?: error("export row must be pending before publish")
            rows.publish(pending.uri)
            state = State.Published(pending.uri)
            pending.uri
        }

    suspend fun rollbackNoThrow(): Throwable? =
        mutex.withLock {
            val pending = state as? State.Pending ?: return@withLock null
            try {
                rows.delete(pending.uri)
                state = State.Settled
                null
            } catch (failure: Throwable) {
                // Keep Pending so a later bounded retry by the same owner can
                // attempt cleanup again; settlement is idempotent once it
                // succeeds or the row is published.
                failure
            }
        }

    suspend fun pendingUriOrNull(): Uri? =
        mutex.withLock { (state as? State.Pending)?.uri }

    suspend fun isPublished(): Boolean =
        mutex.withLock { state is State.Published }
}

internal sealed class ExportPipelineResult<out T> {
    data class Published<T>(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val metadata: T,
    ) : ExportPipelineResult<T>()

    data class PublishedWithMetadataFailure(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val failure: Throwable,
    ) : ExportPipelineResult<Nothing>()

    data class Failed(val failure: Throwable) : ExportPipelineResult<Nothing>()
    data object Stale : ExportPipelineResult<Nothing>()

    /**
     * The export reached a terminal outcome but an unreadable pending row
     * could not be removed after a deterministic bounded retry. The image was
     * NOT published. surfaced to diagnostics so the caller can report a
     * factual cleanup-debt failure instead of leaving an undocumented owner.
     */
    data class CleanupFailed(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val cleanupFailure: Throwable,
    ) : ExportPipelineResult<Nothing>()
}

/**
 * Failure-atomic export transaction.
 *
 * The rendered Bitmap and pending row have one owner here. Before
 * publication, every stale, failed, or cancelled path deletes the row.
 * Publication is the commit point: metadata failure is reported without
 * deleting an already-visible image, and rows are never deleted after a
 * successful publication.
 *
 * The final settlement path performs a deterministic bounded same-operation
 * retry (one extra cleanup attempt) when the first delete of an unreadable
 * pending row fails. If cleanup still fails, the result is downgraded to
 * [ExportPipelineResult.CleanupFailed] for stale/failed outcomes, surfacing
 * the debt to diagnostics instead of relying on a later finalizer.
 *
 * Cancellation is always rethrown per structured-concurrency rules; any
 * pending row remaining during cancellation still receives the bounded
 * cleanup attempt before the cancel propagates.
 */
internal suspend fun <T> executeExportPipeline(
    request: ExportRowRequest,
    rows: ExportRowStore,
    isCurrent: () -> Boolean,
    render: suspend () -> Bitmap?,
    persistMetadata: suspend (uri: Uri, width: Int, height: Int) -> T,
    onCancellationCleanupFailure: (Throwable) -> Unit = { failure ->
        Log.e("KeplerStudio.Export", "cancelled export cleanup failed", failure)
    },
): ExportPipelineResult<T> {
    var rendered: Bitmap? = null
    val transaction = ExportRowTransaction(rows)
    var intended: ExportPipelineResult<T> = ExportPipelineResult.Stale
    var cancelled = false
    var cancellationCause: CancellationException? = null
    try {
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale.also { intended = it }
        rendered = render()
            ?: return ExportPipelineResult.Stale.also { intended = it }
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale.also { intended = it }

        val pending = transaction.insert(request)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale.also { intended = it }

        transaction.encode(checkNotNull(rendered), request.format)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale.also { intended = it }

        val uri = pending
        val width = checkNotNull(rendered).width
        val height = checkNotNull(rendered).height
        val published = withContext(NonCancellable) {
            if (!isCurrent()) {
                ExportPipelineResult.Stale
            } else {
                transaction.publish()
                try {
                    ExportPipelineResult.Published(
                        uri = uri,
                        width = width,
                        height = height,
                        metadata = persistMetadata(uri, width, height),
                    )
                } catch (failure: Throwable) {
                    ExportPipelineResult.PublishedWithMetadataFailure(
                        uri = uri,
                        width = width,
                        height = height,
                        failure = failure,
                    )
                }
            }
        }
        intended = published
        return published
    } catch (cancellation: CancellationException) {
        cancelled = true
        cancellationCause = cancellation
        throw cancellation
    } catch (failure: Throwable) {
        intended = ExportPipelineResult.Failed(failure)
        return intended
    } finally {
        val cleanupFailure =
            withContext(NonCancellable) {
                if (transaction.isPublished()) {
                    null
                } else {
                    var failure: Throwable? = transaction.rollbackNoThrow()
                    if (failure != null) {
                        // Deterministic bounded same-operation retry: one extra
                        // attempt while the owner still exists, no sleeps.
                        val retry = transaction.rollbackNoThrow()
                        if (retry == null) failure = null
                    }
                    failure
                }
            }
        val pendingUriForReport =
            if (cleanupFailure != null && !cancelled) transaction.pendingUriOrNull() else null
        val renderedWidth = rendered?.width ?: 0
        val renderedHeight = rendered?.height ?: 0
        rendered?.takeUnless(Bitmap::isRecycled)?.recycle()
        // Surface an unreadable pending row that could not be removed for a
        // stale or failed (non-cancelled) outcome; never override a published
        // image and never swallow a CancellationException. Preserve cancelled
        // cleanup debt on the original exception for structured diagnostics.
        if (cancelled && cleanupFailure != null) {
            cancellationCause?.addSuppressed(cleanupFailure)
            onCancellationCleanupFailure(cleanupFailure)
        }
        if (pendingUriForReport != null && cleanupFailure != null && !cancelled) {
            when (intended) {
                is ExportPipelineResult.Stale,
                is ExportPipelineResult.Failed,
                -> {
                    return@executeExportPipeline ExportPipelineResult.CleanupFailed(
                        uri = pendingUriForReport,
                        width = renderedWidth,
                        height = renderedHeight,
                        cleanupFailure = cleanupFailure,
                    )
                }
                else -> {}
            }
        }
    }
}
