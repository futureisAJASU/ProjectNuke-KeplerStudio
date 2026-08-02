package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class SelectionPreviewIdentity(
    val gestureId: Long,
    val previewToken: Long,
    val revision: Int,
    val documentGeneration: String,
    val baseContentToken: String,
    val activeSelectionLayerId: String?,
) {
    fun matches(
        activeGestureId: Long?,
        latestPreviewToken: Long?,
        stateRevision: Int,
        stateDocumentGeneration: String,
        stateBaseContentToken: String,
        stateActiveSelectionLayerId: String?,
    ): Boolean =
        activeGestureId == gestureId &&
            latestPreviewToken == previewToken &&
            stateRevision == revision &&
            stateDocumentGeneration == documentGeneration &&
            stateBaseContentToken == baseContentToken &&
            stateActiveSelectionLayerId == activeSelectionLayerId
}

internal enum class SelectionPreviewFailureKind {
    StaleOrSuperseded,
    Cancelled,
    MissingSource,
    AllocationFailure,
    RenderFailure,
    InvariantFailure,
}

internal sealed interface SelectionPreviewPreparationOutcome {
    val identity: SelectionPreviewIdentity

    data class Prepared(
        override val identity: SelectionPreviewIdentity,
        val observedRevision: Int,
    ) : SelectionPreviewPreparationOutcome

    data class Rejected(
        override val identity: SelectionPreviewIdentity,
        val kind: SelectionPreviewFailureKind,
        val message: String,
        val failure: Throwable? = null,
    ) : SelectionPreviewPreparationOutcome
}

/** Owns every source-side resource created by one prepared live-preview attempt. */
internal class PreparedSelectionPreview(
    val identity: SelectionPreviewIdentity,
    val snapshot: LeasedEditorSnapshot,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var base: Bitmap? = null
    private var layers: List<SelectionLayer> = emptyList()
    private var reservations: MaskReservationBatch? = null
    private var tracker: MemoryTrackerScope? = null

    fun attachBase(value: Bitmap) {
        check(!closed.get())
        base = value
    }

    fun attachLayers(value: List<SelectionLayer>) {
        check(!closed.get())
        layers = value
    }

    fun attachReservations(value: MaskReservationBatch) {
        check(!closed.get())
        reservations = value
    }

    fun attachTracker(value: MemoryTrackerScope) {
        check(!closed.get())
        tracker = value
    }

    fun requireBase(): Bitmap = checkNotNull(base)

    fun requireLayers(): List<SelectionLayer> = layers

    fun tracker(): MemoryTrackerScope? = tracker

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        base?.takeUnless { it.isRecycled }?.recycle()
        layers.forEach { it.bitmap.takeUnless { bitmap -> bitmap.isRecycled }?.recycle() }
        reservations?.close()
        tracker?.end()
        snapshot.close()
        base = null
        layers = emptyList()
        reservations = null
        tracker = null
    }
}

/** Retains a prepared owner across a cancellable dispatcher handoff. */
internal class PreparedSelectionPreviewSlot : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val owner = AtomicReference<PreparedSelectionPreview?>(null)

    fun publish(value: PreparedSelectionPreview) {
        if (closed.get() || !owner.compareAndSet(null, value)) {
            value.close()
        }
    }

    fun take(): PreparedSelectionPreview? = owner.getAndSet(null)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        owner.getAndSet(null)?.close()
    }
}
