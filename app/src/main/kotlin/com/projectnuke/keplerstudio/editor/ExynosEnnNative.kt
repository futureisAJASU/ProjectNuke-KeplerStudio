package com.projectnuke.keplerstudio.editor

import android.util.Log

/**
 * Process boundary to Samsung's public Exynos ENN runtime (NDK v1).
 *
 * The production implementation is backed by `libenn_kepler_jni.so`, which links the
 * vendored client stub `libenn_public_api_ndk_v1.so`; that stub resolves the vendor
 * library `libenn_user.samsung_slsi.so` at load time on devices that expose it.
 * Unit tests inject [ExynosEnnNativeInterface] fakes instead — there is no fake in
 * the production path.
 */
internal interface ExynosEnnNativeInterface {
    /** True when the vendored client stub can be loaded (vendor library resolvable). */
    fun probeRuntime(): Boolean

    fun initialize(): Int

    fun deinitialize(): Int

    /** Returns the model id or -1 when the NNC cannot be opened on this device. */
    fun openModel(path: String): Long

    fun closeModel(modelId: Long): Boolean

    /** Returns [bufferSetPtr, nInBuffers, nOutBuffers] or null. */
    fun allocateAllBuffers(modelId: Long): LongArray?

    fun releaseBuffers(bufferSet: Long, bufferCount: Int): Boolean

    /** Returns [n, width, height, channel, size] or null. */
    fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray?

    fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Boolean

    fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Boolean

    fun execute(modelId: Long): Boolean

    fun getMetaInfo(metaId: Int, modelId: Long): String?
}

internal object ExynosEnnNative : ExynosEnnNativeInterface {

    private const val TAG = "KeplerExynosEnn"

    @Volatile
    private var stubLoadAttempted = false

    /**
     * Loading the client stub resolves its vendor dependency (`libenn_user.samsung_slsi.so`)
     * inside the app's native library namespace; failure means this device does not expose
     * the public ENN surface. Safe to call repeatedly.
     */
    override fun probeRuntime(): Boolean =
        try {
            System.loadLibrary("enn_public_api_ndk_v1")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "ENN public API stub unavailable on this device", t)
            false
        }

    override fun initialize(): Int {
        ensureStubLoaded()
        return nativeInitialize()
    }

    override fun deinitialize(): Int {
        ensureStubLoaded()
        return nativeDeinitialize()
    }

    override fun openModel(path: String): Long {
        ensureStubLoaded()
        return nativeOpenModel(path)
    }

    override fun closeModel(modelId: Long): Boolean {
        ensureStubLoaded()
        return nativeCloseModel(modelId)
    }

    override fun allocateAllBuffers(modelId: Long): LongArray? {
        ensureStubLoaded()
        return nativeAllocateAllBuffers(modelId)
    }

    override fun releaseBuffers(bufferSet: Long, bufferCount: Int): Boolean {
        ensureStubLoaded()
        return nativeReleaseBuffers(bufferSet, bufferCount)
    }

    override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? {
        ensureStubLoaded()
        return nativeGetBufferInfoByIndex(modelId, direction, index)
    }

    override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Boolean {
        ensureStubLoaded()
        return nativeMemcpyHostToDevice(bufferSet, index, data)
    }

    override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Boolean {
        ensureStubLoaded()
        return nativeMemcpyDeviceToHost(bufferSet, index, out)
    }

    override fun execute(modelId: Long): Boolean {
        ensureStubLoaded()
        return nativeExecute(modelId)
    }

    override fun getMetaInfo(metaId: Int, modelId: Long): String? {
        ensureStubLoaded()
        return nativeGetMetaInfo(metaId, modelId)
    }

    private fun ensureStubLoaded() {
        if (!stubLoadAttempted) {
            synchronized(this) {
                if (!stubLoadAttempted) {
                    runCatching { System.loadLibrary("enn_public_api_ndk_v1") }
                        .onFailure { Log.i(TAG, "ENN stub load failed", it) }
                    stubLoadAttempted = true
                }
            }
        }
    }

    private external fun nativeInitialize(): Int
    private external fun nativeDeinitialize(): Int
    private external fun nativeOpenModel(path: String): Long
    private external fun nativeCloseModel(modelId: Long): Boolean
    private external fun nativeAllocateAllBuffers(modelId: Long): LongArray?
    private external fun nativeReleaseBuffers(bufferSet: Long, bufferCount: Int): Boolean
    private external fun nativeGetBufferInfoByIndex(
        modelId: Long,
        direction: Int,
        index: Int,
    ): IntArray?

    private external fun nativeMemcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Boolean
    private external fun nativeMemcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Boolean
    private external fun nativeExecute(modelId: Long): Boolean
    private external fun nativeGetMetaInfo(metaId: Int, modelId: Long): String?
}
