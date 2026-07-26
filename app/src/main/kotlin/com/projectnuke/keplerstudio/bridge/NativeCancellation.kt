package com.projectnuke.keplerstudio.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Cancellation registry for native compute operations.
 *
 * Every coroutine-backed native invocation creates a unique token, registers
 * it before JNI dispatch, and signals cancellation from the coroutine-join
 * step. The native kernel can check the flag between passes without probing
 * every pixel注: bounded-checkpoints only.
 *
 * Tokens are unregistered RAII-style on return (or via cancellation pre-close).
 * An old token can never cancel a newer operation because the signal only fires
 * for the owning coroutine's currently-active token.
 */
internal object NativeCancellation {
    private val nextId = AtomicLong(0L)
    private val flags = ConcurrentHashMap<Long, TokenEntry>()

    private data class TokenEntry(
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    fun createToken(): Long {
        val id = nextId.incrementAndGet()
        flags[id] = TokenEntry()
        return id
    }

    fun signal(token: Long): Boolean {
        flags[token]?.cancelled?.set(true)
        val entry = flags[token]
        return entry != null
    }

    fun isCancelled(token: Long): Boolean {
        return flags[token]?.cancelled?.get() ?: true
    }

    fun release(token: Long) {
        flags.remove(token)
    }

    fun cleanup() {
        flags.clear()
    }
}

/**
 * Cancellation that maps the native cancellation token to a Kotlin coroutine [Job].
 *
 * Usage:
 *   val token = CancellableNativeOperation.begin()
 *   try {
 *       // native render / crop / flare / specialEffect
 *       val result = nativeLike(...)
 *       token.checkCancelled()
 *   } finally {
 *       token.close()
 *   }
 *
 * Implementation: the token lives as a Kotlin token for the native registry [NativeCancellation];
 * the coroutine cancellation produces a [kotlinx.coroutines.CancellationException]; return values
 * from the native layer map to known cancellation codes are caught and classified as cancellation.
 * The native cancellation patch is transactional: no partial bitmap mutation is visible across
 * a cancelled operation.
 */
class CancellableNativeOperation {
    val token: Long = NativeCancellation.createToken()

    fun checkCancelled(): Boolean = NativeCancellation.isCancelled(token)

    fun signal() {
        NativeCancellation.signal(token)
    }

    fun close() {
        NativeCancellation.release(token)
    }
}

/** Code returned by cancelled native kernels. */
enum class CancelledNativeExitCode(val code: Int) {
    CancelledBeforeStart(-7),
    CancelledMidPass(-8),
    CancelledAtCommit(-9),
}

fun isNativeCancelledCode(exitCode: Int): Boolean =
    exitCode == CancelledNativeExitCode.CancelledBeforeStart.code ||
        exitCode == CancelledNativeExitCode.CancelledMidPass.code ||
        exitCode == CancelledNativeExitCode.CancelledAtCommit.code