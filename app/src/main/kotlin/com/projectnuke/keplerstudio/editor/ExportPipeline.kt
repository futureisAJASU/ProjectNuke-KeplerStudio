package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.net.Uri
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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
    var pending: Uri? = null
    var published = false
    try {
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale
        rendered = render() ?: return ExportPipelineResult.Stale
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        pending = rows.insertPending(request)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        rows.encode(checkNotNull(pending), checkNotNull(rendered), request.format)
        coroutineContext.ensureActive()
        if (!isCurrent()) return ExportPipelineResult.Stale

        val uri = checkNotNull(pending)
        val width = checkNotNull(rendered).width
        val height = checkNotNull(rendered).height
        return withContext(NonCancellable) {
            if (!isCurrent()) {
                ExportPipelineResult.Stale
            } else {
                rows.publish(uri)
                published = true
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
        if (!published) {
            pending?.let { uri ->
                withContext(NonCancellable) {
                    runCatching { rows.delete(uri) }
                }
            }
        }
        rendered?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}
