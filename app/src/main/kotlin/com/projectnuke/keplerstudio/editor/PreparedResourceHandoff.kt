package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

internal class PreparedResourceHandoff private constructor(
    private val cleanupActions: List<() -> Unit>
) {
    private enum class Ownership {
        CallerOwned,
        ChildOwned,
        Settled,
    }

    private val ownership = AtomicReference(Ownership.CallerOwned)

    fun claimForChild(): Boolean =
        ownership.compareAndSet(Ownership.CallerOwned, Ownership.ChildOwned)

    fun settleCallerOwned(): Boolean = settle(Ownership.CallerOwned)

    fun settleChildOwned(): Boolean = settle(Ownership.ChildOwned)

    private fun settle(expected: Ownership): Boolean {
        if (!ownership.compareAndSet(expected, Ownership.Settled)) {
            return false
        }
        var aggregate: Throwable? = null
        cleanupActions.forEach { action ->
            runCatching(action).exceptionOrNull()?.let { failure ->
                if (aggregate == null) aggregate = failure else aggregate?.addSuppressed(failure)
            }
        }
        aggregate?.let {
            runCatching {
                logger.log(Level.WARNING, "Prepared resource cleanup completed with failures", it)
            }
        }
        return true
    }

    companion object {
        private val logger = Logger.getLogger(PreparedResourceHandoff::class.java.name)

        fun create(cleanupAction: () -> Unit): PreparedResourceHandoff =
            PreparedResourceHandoff(listOf(cleanupAction))

        fun create(vararg cleanupActions: () -> Unit): PreparedResourceHandoff =
            PreparedResourceHandoff(cleanupActions.toList())

        fun create(cleanupActions: List<() -> Unit>): PreparedResourceHandoff =
            PreparedResourceHandoff(cleanupActions.toList())
    }
}
