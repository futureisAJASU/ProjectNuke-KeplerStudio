package com.projectnuke.keplerstudio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionMaskOverlayTest {
    @Test
    fun `mask alpha follows intensity opacity and overlay`() {
        assertEquals(0, selectionOverlayAlpha(0, 1f, 1f, false))
        assertEquals(255, selectionOverlayAlpha(255, 1f, 1f, false))
        assertEquals(64, selectionOverlayAlpha(128, 0.5f, 1f, false))
        assertEquals(64, selectionOverlayAlpha(128, 1f, 0.5f, false))
    }

    @Test
    fun `inverted mask swaps transparent and opaque ends`() {
        assertEquals(255, selectionOverlayAlpha(0, 1f, 1f, true))
        assertEquals(0, selectionOverlayAlpha(255, 1f, 1f, true))
    }

    @Test
    fun `soft edge remains linear and bounded`() {
        assertEquals(64, selectionOverlayAlpha(64, 1f, 1f, false))
        assertEquals(192, selectionOverlayAlpha(192, 1f, 1f, false))
        assertEquals(0, selectionOverlayAlpha(-10, 1f, 1f, false))
        assertEquals(255, selectionOverlayAlpha(300, 1f, 1f, false))
    }
}
