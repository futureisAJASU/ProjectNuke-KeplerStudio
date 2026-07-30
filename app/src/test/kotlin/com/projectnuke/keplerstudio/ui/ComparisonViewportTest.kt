package com.projectnuke.keplerstudio.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.assertEquals
import org.junit.Test

class ComparisonViewportTest {
    @Test
    fun splitDragUsesViewportWidthAndStaysBounded() {
        assertEquals(0.6f, moveComparisonSplit(0.5f, 100f, 1000), 0.0001f)
        assertEquals(0.95f, moveComparisonSplit(0.9f, 500f, 1000), 0.0001f)
        assertEquals(0.05f, moveComparisonSplit(0.1f, -500f, 1000), 0.0001f)
    }

    @Test
    fun viewportOffsetResetsAtIdentityAndClampsWhenZoomed() {
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(120f, -80f), IntSize(400, 200), 1f),
        )
        assertEquals(
            Offset(200f, -100f),
            clampComparisonOffset(Offset(900f, -900f), IntSize(400, 200), 2f),
        )
    }
}
