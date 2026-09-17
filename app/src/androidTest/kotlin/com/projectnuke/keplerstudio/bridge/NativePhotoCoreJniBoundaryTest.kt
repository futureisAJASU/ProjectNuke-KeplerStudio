package com.projectnuke.keplerstudio.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Directly enters the public JNI method with a null reference. */
class NativePhotoCoreJniBoundaryTest {
    @Test
    fun nullCreateSessionReturnsFailureSentinelWithoutCrashing() {
        val method =
            NativePhotoCore::class.java.getDeclaredMethod(
                "nativeCreateSession",
                String::class.java,
            )
        val result = method.invoke(NativePhotoCore, *arrayOfNulls<Any>(1)) as Long
        assertEquals(0L, result)
    }
}
