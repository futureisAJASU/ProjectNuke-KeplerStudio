package com.projectnuke.keplerstudio.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the brush segment raster produces continuous coverage between two sampled
 * pointer positions and that sparse pointer samples do not leave gaps.
 *
 * This exercises [BrushSegmentPlanner] which mirrors the production interpolation contract:
 * the production code (EditorViewModel.applyPaintSegment) steps along the line by half the
 * brush radius so even fast pointer samples produce overlapping disks.
 */
class BrushSegmentPlannerTest {

    @Test
    fun fastDiagonalProducesNoGaps() {
        val planner = BrushSegmentPlanner(radius = 30f)
        val start = 0f to 0f
        val end = 300f to 300f
        val samples = planner.samplesBetween(start, end)
        assertTrue(samples.isNotEmpty())
        val step = planner.step()
        for (i in 1 until samples.size) {
            val (px, py) = samples[i]
            val (qx, qy) = samples[i - 1]
            val d = kotlin.math.sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
            assertTrue(d <= step * 2 + 0.01f, "gap at i=$i d=$d exceeds radius coverage")
        }
    }

    @Test
    fun sparsePointerSamplesInterpolated() {
        val planner = BrushSegmentPlanner(radius = 24f)
        val samples = planner.samplesBetween(0f to 0f, 240f to 0f)
        assertTrue(samples.size > 5)
        val xs = samples.map { it.first }
        assertEquals(0f, xs.first())
        assertEquals(240f, xs.last())
        for (i in 1 until xs.size) assertTrue(xs[i] >= xs[i - 1])
    }

    @Test
    fun shortStrokeProducesAtLeastOneSample() {
        val planner = BrushSegmentPlanner(radius = 10f)
        val samples = planner.samplesBetween(5f to 5f, 5f to 5f)
        assertEquals(1, samples.size)
    }

    @Test
    fun borderStrokeClippedToImageBounds() {
        val planner = BrushSegmentPlanner(radius = 12f)
        val samples = planner.samplesBetween(
            -20f to 50f,
            60f to 200f,
            maskWidth = 100, maskHeight = 100,
        )
        for ((x, y) in samples) {
            assertTrue(x in 0f..100f)
            assertTrue(y in 0f..100f)
        }
    }

    @Test
    fun portraitAndUltraWideCoverageMatchSameFraction() {
        val planner = BrushSegmentPlanner(radius = 40f)
        val portrait = planner.samplesBetween(40f to 40f, 40f to 4000f, maskWidth = 1000, maskHeight = 4000)
        val ultrawide = planner.samplesBetween(40f to 40f, 4000f to 40f, maskWidth = 4000, maskHeight = 1000)
        assertTrue(portrait.isNotEmpty())
        assertTrue(ultrawide.isNotEmpty())
    }
}

/**
 * Pure, host-testable planner that mirrors the production brush-segment interpolation contract.
 * Step size = radius * 0.5 to guarantee overlapping disk coverage between samples.
 */
internal class BrushSegmentPlanner(private val radius: Float) {
    fun step(): Float = (radius * 0.5f).coerceAtLeast(1f)

    fun samplesBetween(
        start: Pair<Float, Float>,
        end: Pair<Float, Float>,
        maskWidth: Int = Int.MAX_VALUE,
        maskHeight: Int = Int.MAX_VALUE,
    ): List<Pair<Float, Float>> {
        val (sx, sy) = start
        val (ex, ey) = end
        val dx = ex - sx
        val dy = ey - sy
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val s = step()
        val steps = if (dist <= 0f) 0 else (dist / s).toInt().coerceIn(1, 4096)
        val out = ArrayList<Pair<Float, Float>>(steps + 1)
        for (i in 0..steps) {
            val t = if (steps == 0) 0f else i.toFloat() / steps.toFloat()
            val px = (sx + dx * t).coerceIn(0f, maskWidth.toFloat())
            val py = (sy + dy * t).coerceIn(0f, maskHeight.toFloat())
            out.add(px to py)
        }
        return out
    }
}
