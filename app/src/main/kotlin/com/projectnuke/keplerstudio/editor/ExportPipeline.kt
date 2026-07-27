package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.net.Uri
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
 * Publication is the only commit state. A failed rollback remains retryable, while repeated
 * settlement after publish or successful deletion is a no-op.
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
                // Keep Pending so a later lifecycle finalizer can retry.
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
}

/**
 * Failure-atomic export transaction.
 *
 * The rendered Bitmap and pending row have one owner here. Before publication, every stale,
 * failed, or cancelled path deletes the row. Publication is the commit point: metadata failure
 * is reported without deleting an already-visible image.
 */
internal suspend fun <T> executeExportPipeline(
    request: ExportRowRequest,
    rows: ExportRowStore,
    isCurrent: () -> Boolean,
    render: suspend () -> Bitmap?,
    persistMetadata: suspend (uri: Uri, width: Int, height: Int) -> T,
): ExportPipelineResult<T> {
    var rendered: Bitmap? = null
    val transaction = ExportRowTransaction(rows)
    try {
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale
        rendered = render() ?: return ExportPipelineResult.Stale
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        val pending = transaction.insert(request)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        transaction.encode(checkNotNull(rendered), request.format)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        val uri = pending
        val width = checkNotNull(rendered).width
        val height = checkNotNull(rendered).height
        return withContext(NonCancellable) {
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
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        return ExportPipelineResult.Failed(failure)
    } finally {
        withContext(NonCancellable) {
            if (!transaction.isPublished()) {
                transaction.rollbackNoThrow()
            }
        }
        rendered?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}
