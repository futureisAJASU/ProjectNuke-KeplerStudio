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

/** Atomic owner transfer across a cancellable dispatcher return. */
internal class OwnedHandoff<T : AutoCloseable> : AutoCloseable {
    private sealed interface State<T> {
        class Open<T> : State<T>
        data class Published<T>(val value: T) : State<T>
        class Transferred<T> : State<T>
        class Closed<T> : State<T>
    }

    private val state = AtomicReference<State<T>>(State.Open())

    fun publish(value: T): Boolean {
        while (true) {
            when (val current = state.get()) {
                is State.Open -> if (state.compareAndSet(current, State.Published(value))) return true
                is State.Published, is State.Transferred, is State.Closed -> {
                    value.close()
                    return false
                }
            }
        }
    }

    fun take(): T? {
        while (true) {
            when (val current = state.get()) {
                is State.Published -> if (state.compareAndSet(current, State.Transferred())) return current.value
                is State.Open, is State.Transferred, is State.Closed -> return null
            }
        }
    }

    override fun close() {
        while (true) {
            when (val current = state.get()) {
                is State.Open -> if (state.compareAndSet(current, State.Closed())) return
                is State.Published -> if (state.compareAndSet(current, State.Closed())) {
                    current.value.close()
                    return
                }
                is State.Transferred, is State.Closed -> return
            }
        }
    }
}

internal class OwnedRenderSuccess(val result: RenderResult.Success) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun takeOutput(): Bitmap? = if (closed.compareAndSet(false, true)) result.output else null

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            result.output.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }
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
    private val handoff = OwnedHandoff<PreparedSelectionPreview>()

    fun publish(value: PreparedSelectionPreview): Boolean = handoff.publish(value)

    fun take(): PreparedSelectionPreview? = handoff.take()

    override fun close() = handoff.close()
}
