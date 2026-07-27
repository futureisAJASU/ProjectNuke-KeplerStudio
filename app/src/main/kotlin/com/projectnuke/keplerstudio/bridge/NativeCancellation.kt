package com.projectnuke.keplerstudio.bridge

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface NativeCancellationBackend {
    fun register(): Long
    fun signal(token: Long): Boolean
    fun release(token: Long): Boolean
}

private object JniCancellationBackend : NativeCancellationBackend {
    override fun register() = NativePhotoCore.nativeRegisterCancellationToken()
    override fun signal(token: Long) = NativePhotoCore.nativeSignalCancellation(token)
    override fun release(token: Long) = NativePhotoCore.nativeReleaseCancellationToken(token)
}

/** Exact owner of a C++ registry entry. */
class CancellableNativeOperation internal constructor(
    private val backend: NativeCancellationBackend = JniCancellationBackend,
) : AutoCloseable {
    val token: Long = backend.register()
    private val closed = AtomicBoolean(false)

    fun signal(): Boolean = !closed.get() && backend.signal(token)

    override fun close() {
        if (closed.compareAndSet(false, true)) backend.release(token)
    }
}

/**
 * Executes one JNI kernel off Main and owns its native registry entry.
 *
 * A cancelled coroutine cannot abandon a still-running JNI call: cancellation first signals
 * C++, then waits in [NonCancellable] until the kernel has returned and released every Bitmap
 * lock. The token is unregistered only after that join, so a released token can never race a
 * kernel which is still polling it.
 */
internal suspend fun executeCancellableNative(
    backend: NativeCancellationBackend = JniCancellationBackend,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    call: (Long) -> Int,
): Int = coroutineScope {
    coroutineContext.ensureActive()
    val operation = CancellableNativeOperation(backend)
    val nativeCall = async(dispatcher) { call(operation.token) }
    try {
        val result = nativeCall.await()
        coroutineContext.ensureActive()
        if (isNativeCancelledCode(result)) {
            throw CancellationException("native operation cancelled: code=$result")
        }
        result
    } finally {
        if (!nativeCall.isCompleted) {
            operation.signal()
        }
        withContext(NonCancellable) {
            nativeCall.join()
        }
        operation.close()
    }
}

enum class CancelledNativeExitCode(val code: Int) {
    CancelledBeforeStart(-7),
    CancelledMidPass(-8),
    CancelledAtCommit(-9),
}

fun isNativeCancelledCode(exitCode: Int): Boolean =
    CancelledNativeExitCode.entries.any { it.code == exitCode }
