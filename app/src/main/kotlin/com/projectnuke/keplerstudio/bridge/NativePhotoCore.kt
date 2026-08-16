package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.PresetColorLook
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.applyPresetColorLookInPlace
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin -> C++ bridge.
 * Kotlin은 세션/명령만 전달하고, 픽셀 반복 처리는 C++에서 수행한다.
 * 프리셋 LUT는 네이티브 렌더 직후 Kotlin 후처리로 적용한다.
 */
object NativePhotoCore {
    init {
        System.loadLibrary("kepler_photocore")
    }

    external fun nativeVersion(): String

    external fun nativeCreateSession(sourcePath: String): Long

    external fun nativeReleaseSession(handle: Long)

    internal external fun nativeRegisterCancellationToken(): Long
    internal external fun nativeSignalCancellation(token: Long): Boolean
    internal external fun nativeReleaseCancellationToken(token: Long): Boolean
    internal external fun nativeActiveCancellationTokenCount(): Int

    /**
     * Preview와 export가 같은 네이티브 픽셀 파이프라인을 사용한다.
     */
    suspend fun nativeRenderPreviewInPlace(
        bitmap: Bitmap,
        exposure: Float,
        contrast: Float,
        shadows: Float,
        highlights: Float,
        whites: Float,
        blacks: Float,
        temperature: Float,
        tint: Float,
        saturation: Float,
        vibrance: Float,
        clarity: Float,
        dehaze: Float,
        sharpness: Float,
        noiseReduction: Float,
        luminanceNoiseReduction: Float = noiseReduction,
        colorNoiseReduction: Float = noiseReduction,
        noiseDetailProtection: Float = 0.50f,
        noiseEngine: Int,
        detailEngine: Int,
        toneEngine: Int,
        hazeEngine: Int,
        revision: Int,
        look: PresetColorLook? = null,
        diagnostics: MemoryTrackerScope? = null,
    ): Int {
        val scratchPlan =
            NativeScratchPlanner.mainRender(
                rowBytes = bitmap.rowBytes,
                needsFiveRows =
                    luminanceNoiseReduction > 0.001f || colorNoiseReduction > 0.001f,
                needsThreeRows = sharpness > 0.001f,
                width = bitmap.width,
                height = bitmap.height,
                noiseEngine = noiseEngine,
                detailEngine = detailEngine,
                hazeEngine = hazeEngine,
            )
        // Kotlin keeps highlights signed opposite the native kernel, so pass the negated value here.
        val result =
            executeNative(scratchPlan, diagnostics) { operationToken ->
                nativeRenderPreviewInPlaceNative(
                    bitmap,
                    exposure,
                    contrast,
                    shadows,
                    -highlights,
                    whites,
                    blacks,
                    temperature,
                    tint,
                    saturation,
                    vibrance,
                    clarity,
                    dehaze,
                    sharpness,
                    noiseReduction,
                    luminanceNoiseReduction,
                    colorNoiseReduction,
                    noiseDetailProtection,
                    noiseEngine,
                    detailEngine,
                    toneEngine,
                    hazeEngine,
                    revision,
                    operationToken,
                )
            }
        if (result >= 0) {
            applyPresetColorLookInPlace(bitmap, look)
        }
        return result
    }

    suspend fun nativeApplySpecialEffectInPlace(
        bitmap: Bitmap,
        effect: Int,
        strength: Float,
        revision: Int,
        diagnostics: MemoryTrackerScope? = null,
    ): Int {
        val plan = NativeScratchPlanner.specialEffect(bitmap.rowBytes, bitmap.height, effect)
        return executeNative(plan, diagnostics) { operationToken ->
            nativeApplySpecialEffectInPlaceNative(bitmap, effect, strength, revision, operationToken)
        }
    }

    suspend fun nativeApplyFlareGuardInPlace(
        bitmap: Bitmap,
        mode: Int,
        strength: Float,
        revision: Int,
        diagnostics: MemoryTrackerScope? = null,
    ): Int {
        val plan =
            NativeScratchPlanner.flareCorrection(
                bitmap.rowBytes,
                bitmap.width,
                bitmap.height,
            )
        return executeNative(plan, diagnostics) { operationToken ->
            nativeApplyFlareGuardInPlaceNative(bitmap, mode, strength, revision, operationToken)
        }
    }

    suspend fun nativeCreateFlareMask(
        source: Bitmap,
        mask: Bitmap,
        threshold: Float,
        radius: Int,
        passes: Int,
        diagnostics: MemoryTrackerScope? = null,
    ): Int {
        val plan =
            NativeScratchPlanner.flareMask(source.width, source.height, radius, passes)
        return executeNative(plan, diagnostics) { operationToken ->
            nativeCreateFlareMaskNative(source, mask, threshold, radius, passes, operationToken)
        }
    }

    suspend fun nativeBlendSelectionLayerInPlace(
        target: Bitmap,
        local: Bitmap,
        mask: Bitmap,
        inverted: Boolean,
        opacity: Float,
        diagnostics: MemoryTrackerScope? = null,
    ): Int =
        executeNative(NativeScratchPlanner.selectionBlend(), diagnostics) { operationToken ->
            nativeBlendSelectionLayerInPlaceNative(
                target,
                local,
                mask,
                inverted,
                opacity,
                operationToken,
            )
        }

    suspend fun nativeRenderCropTransform(
        source: Bitmap,
        destination: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        revision: Int,
        diagnostics: MemoryTrackerScope? = null,
    ): Int =
        executeNative(NativeScratchPlanner.crop(), diagnostics) { operationToken ->
            nativeRenderCropTransformNative(
                source,
                destination,
                cropLeft,
                cropTop,
                cropRight,
                cropBottom,
                rotationDegrees,
                flipHorizontal,
                revision,
                operationToken,
            )
        }

    /**
     * Experimental transactional V2 correction. [destination] is not touched until every
     * cancellable stage succeeds and the native kernel reaches its final commit boundary.
     */
    suspend fun nativeApplyCorrectionsV2(
        source: Bitmap,
        destination: Bitmap,
        params: NativeCorrectionV2Params,
        diagnostics: MemoryTrackerScope? = null,
    ): Int {
        require(source !== destination) { "V2 correction source and destination must not alias" }
        require(
            source.width == destination.width &&
                source.height == destination.height &&
                source.rowBytes == destination.rowBytes
        ) {
            "V2 correction destination layout must match source"
        }
        params.requireValid()
        val plan = NativeScratchPlanner.correctionsV2(source.rowBytes, source.height)
        return executeNative(plan, diagnostics) { operationToken ->
            nativeApplyCorrectionsV2Native(
                source,
                destination,
                params.detail,
                params.luminanceNoise,
                params.chromaNoise,
                params.highlightProtection,
                params.shadowProtection,
                params.chromaticAberration,
                params.vignette,
                params.spotCleanup,
                operationToken,
            )
        }
    }

    private external fun nativeRenderPreviewInPlaceNative(
        bitmap: Bitmap,
        exposure: Float,
        contrast: Float,
        shadows: Float,
        highlights: Float,
        whites: Float,
        blacks: Float,
        temperature: Float,
        tint: Float,
        saturation: Float,
        vibrance: Float,
        clarity: Float,
        dehaze: Float,
        sharpness: Float,
        noiseReduction: Float,
        luminanceNoiseReduction: Float,
        colorNoiseReduction: Float,
        noiseDetailProtection: Float,
        noiseEngine: Int,
        detailEngine: Int,
        toneEngine: Int,
        hazeEngine: Int,
        revision: Int,
        operationToken: Long,
    ): Int

    private external fun nativeApplySpecialEffectInPlaceNative(
        bitmap: Bitmap,
        effect: Int,
        strength: Float,
        revision: Int,
        operationToken: Long,
    ): Int

    private external fun nativeApplyFlareGuardInPlaceNative(
        bitmap: Bitmap,
        mode: Int,
        strength: Float,
        revision: Int,
        operationToken: Long,
    ): Int

    private external fun nativeCreateFlareMaskNative(
        source: Bitmap,
        mask: Bitmap,
        threshold: Float,
        radius: Int,
        passes: Int,
        operationToken: Long,
    ): Int

    private external fun nativeBlendSelectionLayerInPlaceNative(
        target: Bitmap,
        local: Bitmap,
        mask: Bitmap,
        inverted: Boolean,
        opacity: Float,
        operationToken: Long,
    ): Int

    private external fun nativeRenderCropTransformNative(
        source: Bitmap,
        destination: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        revision: Int,
        operationToken: Long,
    ): Int

    private external fun nativeApplyCorrectionsV2Native(
        source: Bitmap,
        destination: Bitmap,
        detail: Float,
        luminanceNoise: Float,
        chromaNoise: Float,
        highlightProtection: Float,
        shadowProtection: Float,
        chromaticAberration: Float,
        vignette: Float,
        spotCleanup: Float,
        operationToken: Long,
    ): Int
}

/**
 * Deterministic session factory seam for production tests. When set, every
 * session-creation call site uses it instead of the native library; the
 * default (null) keeps the production native path unchanged.
 *
 * Lives outside the [NativePhotoCore] object on purpose: touching the object
 * triggers its class initializer (System.loadLibrary), which fails in
 * Robolectric and is cached as NoClassDefFoundError.
 */
private val nativeSessionFactoryTestLock = Any()
private class NativeSessionFactoryInstallation(
    val generation: Long,
    private val factory: (String) -> Long,
    private val releaser: ((Long) -> Unit)?,
) {
    @Volatile private var active = true

    fun create(sourcePath: String): Long {
        check(active) { "native session test owner closed before creation" }
        return factory(sourcePath)
    }

    fun release(session: Long) {
        releaser?.invoke(session)
    }

    fun close() {
        active = false
    }
}
internal data class NativeSessionCreation(
    val handle: Long,
    val releaseOverride: ((Long) -> Unit)?,
)
private var nativeSessionFactoryInstallation: NativeSessionFactoryInstallation? = null
private val nativeSessionFactoryInstallationGeneration = AtomicLong()

private fun installNativeSessionFactoryInternal(
    factory: (String) -> Long,
    releaser: ((Long) -> Unit)? = null,
): AutoCloseable {
    val installation =
        NativeSessionFactoryInstallation(
            nativeSessionFactoryInstallationGeneration.incrementAndGet(),
            factory,
            releaser,
        )
    synchronized(nativeSessionFactoryTestLock) {
        check(nativeSessionFactoryInstallation == null) { "native session factory test seam already installed" }
        nativeSessionFactoryInstallation = installation
    }
    return AutoCloseable {
        installation.close()
        synchronized(nativeSessionFactoryTestLock) {
            if (nativeSessionFactoryInstallation === installation) nativeSessionFactoryInstallation = null
        }
    }
}

internal fun installNativeSessionFactoryForTest(factory: (String) -> Long): AutoCloseable =
    installNativeSessionFactoryInternal(factory)

internal fun installNativeSessionFactoryWithReleaseForTest(
    factory: (String) -> Long,
    releaser: (Long) -> Unit,
): AutoCloseable = installNativeSessionFactoryInternal(factory, releaser)

internal fun nativeSessionFactoryTestSeamCount(): Int = synchronized(nativeSessionFactoryTestLock) {
    if (nativeSessionFactoryInstallation == null) 0 else 1
}

/**
 * Session creation routed through the test seam when one is installed,
 * otherwise the native library call.
 */
internal fun nativeCreateSessionOrTest(sourcePath: String): Long =
    nativeCreateSessionHandleOrTest(sourcePath).handle

/** Creates a session and binds its test release owner at the same boundary. */
internal fun nativeCreateSessionHandleOrTest(sourcePath: String): NativeSessionCreation =
    synchronized(nativeSessionFactoryTestLock) {
        nativeSessionFactoryInstallation
    }?.let { installation ->
        NativeSessionCreation(installation.create(sourcePath), installation::release)
    } ?: NativeSessionCreation(NativePhotoCore.nativeCreateSession(sourcePath), null)

/** Release counterpart for [installNativeSessionFactoryForTest]. */
internal fun nativeReleaseSessionOrTest(session: Long): Boolean {
    val installation = synchronized(nativeSessionFactoryTestLock) { nativeSessionFactoryInstallation }
    if (installation != null) {
        installation.release(session)
        return true
    }
    NativePhotoCore.nativeReleaseSession(session)
    return false
}

/**
 * Deterministic in-place flare kernel seam for production tests. When set,
 * every flare rule call site uses it instead of the native library; the
 * default (null) keeps the production native path unchanged.
 *
 * Lives outside the [NativePhotoCore] object on purpose: touching the object
 * triggers its class initializer (System.loadLibrary), which fails in
 * Robolectric and is cached as NoClassDefFoundError.
 */
private val nativeFlareGuardTestLock = Any()
private class NativeFlareGuardInstallation(
    val generation: Long,
    private val kernel: suspend (Bitmap, Int, Float, Int) -> Int,
) {
    @Volatile private var active = true

    suspend fun invoke(bitmap: Bitmap, mode: Int, strength: Float, revision: Int): Int {
        check(active) { "native flare test owner closed before execution" }
        return kernel(bitmap, mode, strength, revision)
    }

    fun close() {
        active = false
    }
}
private var nativeFlareGuardInstallation: NativeFlareGuardInstallation? = null
private val nativeFlareGuardInstallationGeneration = AtomicLong()

internal fun installNativeFlareGuardInPlaceForTest(
    kernel: suspend (Bitmap, Int, Float, Int) -> Int,
): AutoCloseable {
    val installation = NativeFlareGuardInstallation(nativeFlareGuardInstallationGeneration.incrementAndGet(), kernel)
    synchronized(nativeFlareGuardTestLock) {
        check(nativeFlareGuardInstallation == null) { "native flare test seam already installed" }
        nativeFlareGuardInstallation = installation
    }
    return AutoCloseable {
        installation.close()
        synchronized(nativeFlareGuardTestLock) {
            if (nativeFlareGuardInstallation === installation) nativeFlareGuardInstallation = null
        }
    }
}

internal fun nativeFlareGuardTestSeamCount(): Int = synchronized(nativeFlareGuardTestLock) {
    if (nativeFlareGuardInstallation == null) 0 else 1
}

/**
 * Flare rule kernel routed through the test seam when one is installed,
 * otherwise the native library call.
 */
internal suspend fun nativeApplyFlareGuardInPlaceOrTest(
    bitmap: Bitmap,
    mode: Int,
    strength: Float,
    revision: Int,
): Int =
    synchronized(nativeFlareGuardTestLock) { nativeFlareGuardInstallation }?.invoke(bitmap, mode, strength, revision)
        ?: NativePhotoCore.nativeApplyFlareGuardInPlace(
            bitmap,
            mode,
            strength,
            revision,
        )

data class NativeCorrectionV2Params(
    val detail: Float = 0f,
    val luminanceNoise: Float = 0f,
    val chromaNoise: Float = 0f,
    val highlightProtection: Float = 0.5f,
    val shadowProtection: Float = 0.5f,
    val chromaticAberration: Float = 0f,
    val vignette: Float = 0f,
    val spotCleanup: Float = 0f,
) {
    internal fun requireValid() {
        listOf(
            detail,
            luminanceNoise,
            chromaNoise,
            highlightProtection,
            shadowProtection,
            chromaticAberration,
            vignette,
            spotCleanup,
        ).forEach { value ->
            require(value.isFinite() && value in 0f..1f) {
                "Native V2 correction parameters must be finite and within 0..1"
            }
        }
    }
}

private suspend fun executeNative(
    plan: NativeScratchPlan,
    diagnostics: MemoryTrackerScope?,
    call: (Long) -> Int,
): Int {
    val operationBudget =
        diagnostics?.availableNativeScratchBytes()
            ?: com.projectnuke.keplerstudio.editor.BitmapMemoryBudget.operationReserveBytes()
    if (!plan.admitted(operationBudget)) return -12
    return executeCancellableNative { token ->
        invokeWithScratch(plan, diagnostics) { call(token) }
    }
}

internal fun invokeWithScratch(
    plan: NativeScratchPlan,
    diagnostics: MemoryTrackerScope?,
    call: () -> Int,
): Int {
    val operationBudget =
        diagnostics?.availableNativeScratchBytes()
            ?: com.projectnuke.keplerstudio.editor.BitmapMemoryBudget.operationReserveBytes()
    if (!plan.admitted(operationBudget)) return -12
    val known =
        diagnostics?.trackTransientBytes("native:${plan.kind}:knownScratch", plan.knownBytes) ?: 0L
    val opaque =
        diagnostics?.trackTransientBytes("native:${plan.kind}:opaqueAllocator", null) ?: 0L
    return try {
        call()
    } finally {
        diagnostics?.releaseTransient(opaque)
        diagnostics?.releaseTransient(known)
    }
}
