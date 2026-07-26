package com.projectnuke.keplerstudio.bridge

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

/** Runs JNI off Main, forwards Job cancellation, waits for native return, then settles once. */
internal suspend fun executeCancellableNative(call: (Long) -> Int): Int {
    val operation = CancellableNativeOperation()
    val job = currentCoroutineContext()[Job]
    val cancellation = job?.invokeOnCompletion { cause ->
        if (cause is CancellationException) operation.signal()
    }
    return try {
        val result = withContext(Dispatchers.Default) { call(operation.token) }
        if (isNativeCancelledCode(result)) throw CancellationException("native operation cancelled")
        result
    } finally {
        cancellation?.dispose()
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
