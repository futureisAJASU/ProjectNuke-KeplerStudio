package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicReference

internal class PreparedResourceHandoff private constructor(private val cleanup: () -> Unit) {
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
        runCatching(cleanup)
        return true
    }

    companion object {
        fun create(cleanup: () -> Unit): PreparedResourceHandoff = PreparedResourceHandoff(cleanup)
    }
}
