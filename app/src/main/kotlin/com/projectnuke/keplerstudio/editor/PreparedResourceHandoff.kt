package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

internal class PreparedResourceHandoff private constructor(
    val token: Long,
    val prepareTracker: MemoryTrackerScope?,
    val undoSnapshot: EditorHistorySnapshot?,
    private val callerCleanup: () -> Unit
) {
    private enum class Phase { PENDING, CLAIMED, DONE }
    private val phase = AtomicReference(Phase.PENDING)

    fun claim(operationToken: Long): Boolean {
        return token == operationToken && phase.compareAndSet(Phase.PENDING, Phase.CLAIMED)
    }

    fun settleIfClaimed() {
        if (phase.get() == Phase.CLAIMED) phase.compareAndSet(Phase.CLAIMED, Phase.DONE)
    }

    fun isNeverClaimed(): Boolean = phase.get() == Phase.PENDING
    fun isSettled(): Boolean = phase.get() == Phase.DONE

    fun fireCallerCleanupIfUnclaimed() {
        if (phase.compareAndSet(Phase.PENDING, Phase.DONE)) callerCleanup()
    }

    companion object {
        fun create(
            token: Long,
            prepareTracker: MemoryTrackerScope?,
            undoSnapshot: EditorHistorySnapshot?,
            callerCleanup: () -> Unit
        ): PreparedResourceHandoff = PreparedResourceHandoff(token, prepareTracker, undoSnapshot, callerCleanup)
    }
}
