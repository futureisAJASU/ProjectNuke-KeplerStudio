package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class OwnedHandoffTest {
    private class CloseableValue(private val closes: AtomicInteger) : AutoCloseable {
        override fun close() { closes.incrementAndGet() }
    }

    @Test
    fun publishTakeTransfersExactlyOnce() {
        val closes = AtomicInteger()
        val handoff = OwnedHandoff<CloseableValue>()
        val value = CloseableValue(closes)
        assertTrue(handoff.publish(value))
        assertNotNull(handoff.take())
        assertNull(handoff.take())
        handoff.close()
        assertEquals(0, closes.get())
    }

    @Test
    fun closeBeforePublishClosesPublishedValue() {
        val closes = AtomicInteger()
        val handoff = OwnedHandoff<CloseableValue>()
        handoff.close()
        assertFalse(handoff.publish(CloseableValue(closes)))
        assertEquals(1, closes.get())
        assertNull(handoff.take())
    }

    @Test
    fun closeAfterPublishReleasesExactlyOnce() {
        val closes = AtomicInteger()
        val handoff = OwnedHandoff<CloseableValue>()
        assertTrue(handoff.publish(CloseableValue(closes)))
        handoff.close()
        handoff.close()
        assertEquals(1, closes.get())
        assertNull(handoff.take())
    }
}
