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

    @Test
    fun viewportOffsetResetsForNonFiniteScale() {
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(120f, -80f), IntSize(400, 200), Float.NaN),
        )
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(120f, -80f), IntSize(400, 200), Float.POSITIVE_INFINITY),
        )
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(120f, -80f), IntSize(400, 200), Float.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun viewportOffsetResetsForNonPositiveViewport() {
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(50f, 50f), IntSize(0, 200), 2f),
        )
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(Offset(50f, 50f), IntSize(200, -5), 2f),
        )
    }

    @Test
    fun viewportOffsetSanitizesNonFiniteComponentsIndependently() {
        // NaN x sanitized to 0f, finite y clamped normally.
        assertEquals(
            Offset(0f, 30f),
            clampComparisonOffset(Offset(Float.NaN, 30f), IntSize(400, 200), 2f),
        )
        // Non-finite x sanitized to 0f, finite y clamped normally.
        assertEquals(
            Offset(0f, -100f),
            clampComparisonOffset(Offset(Float.POSITIVE_INFINITY, -900f), IntSize(400, 200), 2f),
        )
        // Both components non-finite resolve to a finite bounded output.
        assertEquals(
            Offset.Zero,
            clampComparisonOffset(
                Offset(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
                IntSize(400, 200),
                2f,
            ),
        )
    }
}
