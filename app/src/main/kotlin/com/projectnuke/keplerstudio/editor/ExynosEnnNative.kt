package com.projectnuke.keplerstudio.editor

import android.util.Log

/**
 * Raw `EnnReturn` codes from the vendored `enn_api-type_ndk_v1.h` (public NDK v1).
 * The adapter preserves these verbatim across the JNI boundary so hardware
 * bring-up diagnostics see the exact runtime status — never a collapsed `-1`.
 */
internal object EnnStatus {
    const val SUCCESS = 0
    const val FAILED = 1
    const val IO = 2
    const val INVAL = 3
    const val FILTERED = 4
    const val MEM_ERR = 5
    const val SIZE = 6
    const val FAILED_TIMEOUT_ENN = 10
    const val FAILED_TIMEOUT_DD = 11
    const val FAILED_TIMEOUT_FW = 12
    const val FAILED_TIMEOUT_HW_NOTRECOVERED = 13
    const val FAILED_TIMEOUT_HW_RECOVERED = 14
    const val FAILED_SERVICE_NULL = 15
    const val FAILED_RESOURCE_BUSY = 16
    const val NOT_SUPPORTED = 0xFF

    fun name(code: Int): String =
        when (code) {
            SUCCESS -> "ENN_RET_SUCCESS"
            FAILED -> "ENN_RET_FAILED"
            IO -> "ENN_RET_IO"
            INVAL -> "ENN_RET_INVAL"
            FILTERED -> "ENN_RET_FILTERED"
            MEM_ERR -> "ENN_RET_MEM_ERR"
            SIZE -> "ENN_RET_SIZE"
            FAILED_TIMEOUT_ENN -> "ENN_RET_FAILED_TIMEOUT_ENN"
            FAILED_TIMEOUT_DD -> "ENN_RET_FAILED_TIMEOUT_DD"
            FAILED_TIMEOUT_FW -> "ENN_RET_FAILED_TIMEOUT_FW"
            FAILED_TIMEOUT_HW_NOTRECOVERED -> "ENN_RET_FAILED_TIMEOUT_HW_NOTRECOVERED"
            FAILED_TIMEOUT_HW_RECOVERED -> "ENN_RET_FAILED_TIMEOUT_HW_RECOVERED"
            FAILED_SERVICE_NULL -> "ENN_RET_FAILED_SERVICE_NULL"
            FAILED_RESOURCE_BUSY -> "ENN_RET_FAILED_RESOURCE_BUSY"
            NOT_SUPPORTED -> "ENN_RET_NOT_SUPPORTED"
            else -> "ENN_RET_UNMAPPED"
        }

    fun describe(code: Int): String = "$code(${name(code)})"
}

/**
 * `EnnMetaTypeId` values from the vendored `enn_api-type_ndk_v1.h`.
 * Named constants are the single source of truth — no magic numbers anywhere else.
 */
internal object EnnMetaIds {
    const val FRAMEWORK = 110
    const val COMMIT = 111
    const val MODEL_COMPILER_NNC = 120
    const val MODEL_COMPILER_NPU = 121
    const val MODEL_COMPILER_DSP = 122
    const val MODEL_SCHEMA = 123
    const val MODEL_VERSION = 124
    const val DD = 130
    const val UNIFIED_FW = 131
    const val NPU_FW = 132
    const val DSP_FW = 133
}

/** `EnnOpenModel` outcome: [status] is the verbatim EnnReturn; [modelId] is valid only on SUCCESS. */
internal data class EnnOpenModelResult(val status: Int, val modelId: Long) {
    val isSuccess: Boolean
        get() = status == EnnStatus.SUCCESS
}

/**
 * `EnnAllocateAllBuffers` outcome: [status] is the verbatim EnnReturn;
 * [bufferSet]/[nInBuffers]/[nOutBuffers] are valid only on SUCCESS.
 */
internal data class EnnAllocateResult(
    val status: Int,
    val bufferSet: Long,
    val nInBuffers: Int,
    val nOutBuffers: Int,
) {
    val isSuccess: Boolean
        get() = status == EnnStatus.SUCCESS
}

/**
 * Process boundary to Samsung's public Exynos ENN runtime (NDK v1).
 *
 * The production implementation is backed by `libenn_kepler_jni.so`, which links the
 * vendored client stub `libenn_public_api_ndk_v1.so`; that stub resolves the vendor
 * library `libenn_user.samsung_slsi.so` at load time on devices that expose it.
 * Unit tests inject [ExynosEnnNativeInterface] fakes instead — there is no fake in
 * the production path.
 *
 * Every status-returning call surfaces the RAW `EnnReturn` via explicit return
 * structures — there is no global "last error" state anywhere in this adapter.
 */
internal interface ExynosEnnNativeInterface {
    /** True when the vendored client stub can be loaded (vendor library resolvable). */
    fun probeRuntime(): Boolean

    fun initialize(): Int

    fun deinitialize(): Int

    /** Raw open outcome; the failure status is never collapsed. */
    fun openModel(path: String): EnnOpenModelResult

    fun closeModel(modelId: Long): Int

    /** Raw allocation outcome; buffer pointer/counts are valid only when status is SUCCESS. */
    fun allocateAllBuffers(modelId: Long): EnnAllocateResult

    fun releaseBuffers(bufferSet: Long, bufferCount: Int): Int

    /** Returns [n, width, height, channel, size] or null. */
    fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray?

    fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int

    fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int

    fun execute(modelId: Long): Int

    fun getMetaInfo(metaId: Int, modelId: Long): String?
}

internal object ExynosEnnNative : ExynosEnnNativeInterface {

    private const val TAG = "KeplerExynosEnn"

    @Volatile
    private var bridgeLoadAttempted = false
    @Volatile
    private var bridgeLoaded = false

    /**
     * Loading the JNI bridge (`libenn_kepler_jni.so`) resolves its dependencies:
     * - libenn_public_api_ndk_v1.so (vendored client stub)
     * - libenn_user.samsung_slsi.so (vendor library, device-provided)
     *
     * Failure means this device does not expose the public ENN surface.
     * Safe to call repeatedly.
     */
    override fun probeRuntime(): Boolean {
        if (bridgeLoadAttempted) return bridgeLoaded
        synchronized(this) {
            if (bridgeLoadAttempted) return bridgeLoaded
            try {
                System.loadLibrary("enn_kepler_jni")
                bridgeLoaded = true
            } catch (t: Throwable) {
                Log.i(TAG, "ENN JNI bridge unavailable on this device", t)
                bridgeLoaded = false
            }
            bridgeLoadAttempted = true
            return bridgeLoaded
        }
    }

    override fun initialize(): Int {
        checkBridgeLoaded()
        return nativeInitialize()
    }

    override fun deinitialize(): Int {
        checkBridgeLoaded()
        return nativeDeinitialize()
    }

    override fun openModel(path: String): EnnOpenModelResult {
        checkBridgeLoaded()
        val raw = nativeOpenModel(path)
        return EnnOpenModelResult(status = raw[STATUS_INDEX].toInt(), modelId = raw[OPEN_MODEL_ID_INDEX])
    }

    override fun closeModel(modelId: Long): Int {
        checkBridgeLoaded()
        return nativeCloseModel(modelId)
    }

    override fun allocateAllBuffers(modelId: Long): EnnAllocateResult {
        checkBridgeLoaded()
        val raw = nativeAllocateAllBuffers(modelId)
        return EnnAllocateResult(
            status = raw[STATUS_INDEX].toInt(),
            bufferSet = raw[ALLOC_BUFFER_SET_INDEX],
            nInBuffers = raw[ALLOC_N_IN_INDEX].toInt(),
            nOutBuffers = raw[ALLOC_N_OUT_INDEX].toInt(),
        )
    }

    override fun releaseBuffers(bufferSet: Long, bufferCount: Int): Int {
        checkBridgeLoaded()
        return nativeReleaseBuffers(bufferSet, bufferCount)
    }

    override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? {
        checkBridgeLoaded()
        return nativeGetBufferInfoByIndex(modelId, direction, index)
    }

    override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int {
        checkBridgeLoaded()
        return nativeMemcpyHostToDevice(bufferSet, index, data)
    }

    override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int {
        checkBridgeLoaded()
        return nativeMemcpyDeviceToHost(bufferSet, index, out)
    }

    override fun execute(modelId: Long): Int {
        checkBridgeLoaded()
        return nativeExecute(modelId)
    }

    override fun getMetaInfo(metaId: Int, modelId: Long): String? {
        checkBridgeLoaded()
        return nativeGetMetaInfo(metaId, modelId)
    }

    private fun checkBridgeLoaded() {
        if (!bridgeLoaded) {
            throw IllegalStateException("ENN JNI bridge not loaded; call probeRuntime() first")
        }
    }

    // Shared primitive-array layout produced by libenn_kepler_jni.so.
    private const val STATUS_INDEX = 0
    private const val OPEN_MODEL_ID_INDEX = 1
    private const val ALLOC_BUFFER_SET_INDEX = 1
    private const val ALLOC_N_IN_INDEX = 2
    private const val ALLOC_N_OUT_INDEX = 3

    private external fun nativeInitialize(): Int
    private external fun nativeDeinitialize(): Int

    /** Returns long[2] = [rawStatus, modelId]; modelId is 0 unless status is SUCCESS. */
    private external fun nativeOpenModel(path: String): LongArray

    private external fun nativeCloseModel(modelId: Long): Int

    /** Returns long[4] = [rawStatus, bufferSetPtr, nInBuf, nOutBuf]. */
    private external fun nativeAllocateAllBuffers(modelId: Long): LongArray

    private external fun nativeReleaseBuffers(bufferSet: Long, bufferCount: Int): Int

    private external fun nativeGetBufferInfoByIndex(
        modelId: Long,
        direction: Int,
        index: Int,
    ): IntArray?

    private external fun nativeMemcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int
    private external fun nativeMemcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int
    private external fun nativeExecute(modelId: Long): Int
    private external fun nativeGetMetaInfo(metaId: Int, modelId: Long): String?
}
