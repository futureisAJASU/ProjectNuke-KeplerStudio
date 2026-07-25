package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicReference

internal class PreparedResourceHandoff private constructor(
    private val cleanup: () -> Unit,
) {
    private enum class Phase { PENDING, CLAIMED, DONE }
    private val phase = AtomicReference(Phase.PENDING)

    fun tryAcquire(): Boolean = phase.compareAndSet(Phase.PENDING, Phase.CLAIMED)

    fun settle(): Boolean = phase.compareAndSet(Phase.CLAIMED, Phase.DONE)

    fun isClaimed(): Boolean = phase.get() == Phase.CLAIMED

    fun isSettled(): Boolean = phase.get() == Phase.DONE

    fun fireCallerCleanupIfUnclaimed(): Boolean {
        return when (val current = phase.get()) {
            Phase.SETTLED -> false
            Phase.CLAIMED -> false
            Phase.PENDING -> {
                val moved = phase.compareAndSet(Phase.PENDING, Phase.DONE)
                if (moved) cleanup() else false
            }
        }
    }

    companion object {
        fun create(cleanup: () -> Unit): PreparedResourceHandoff =
            PreparedResourceHandoff(cleanup)
    }
}
