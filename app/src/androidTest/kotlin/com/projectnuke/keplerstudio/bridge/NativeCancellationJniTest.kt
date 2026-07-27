package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCancellationJniTest {
    @Test
    fun cancellationDuringKernelWaitsForUnlockAndNeverAdoptsDisposableOutput() = runBlocking {
        val output = Bitmap.createBitmap(4096, 4096, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff304050.toInt())
        val adopted = AtomicBoolean(false)
        val enteredNative = AtomicBoolean(false)
        val operation =
            async(Dispatchers.Default) {
                try {
                    enteredNative.set(true)
                    NativePhotoCore.nativeApplySpecialEffectInPlace(
                        output,
                        effect = 2,
                        strength = 1f,
                        revision = 91,
                    )
                    adopted.set(true)
                } catch (_: CancellationException) {
                    // Expected. The wrapper returns only after the C++ row loop exits.
                }
            }

        withTimeout(5_000) {
            while (!enteredNative.get() || NativePhotoCore.nativeActiveCancellationTokenCount() == 0) {
                delay(1)
            }
        }
        operation.cancelAndJoin()

        assertFalse(adopted.get())
        assertEquals(0, NativePhotoCore.nativeActiveCancellationTokenCount())
        output.eraseColor(0xff010203.toInt())
        assertEquals(0xff010203.toInt(), output.getPixel(0, 0))
        output.recycle()
    }

    @Test
    fun releasedTokenCannotCancelNewOperationAndRegistryDrains() {
        val old = NativePhotoCore.nativeRegisterCancellationToken()
        assertTrue(old > 0)
        assertTrue(NativePhotoCore.nativeReleaseCancellationToken(old))
        val newer = NativePhotoCore.nativeRegisterCancellationToken()
        assertTrue(newer > old)
        assertFalse(NativePhotoCore.nativeSignalCancellation(old))
        assertTrue(NativePhotoCore.nativeReleaseCancellationToken(newer))
        assertEquals(0, NativePhotoCore.nativeActiveCancellationTokenCount())
    }
}
