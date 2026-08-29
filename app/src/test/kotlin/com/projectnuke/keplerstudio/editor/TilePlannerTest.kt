package com.projectnuke.keplerstudio.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase N4.5 — exhaustive pure-planner property tests (no ENN).
 *
 * Asserts, over many dimensions and a generated range, that every plan satisfies the
 * tiling contract: exact 128x128 source tiles inside the image, first/last alignment,
 * exact x4 destination coverage (no gaps, no double ownership), deterministic stable
 * ordering, and halo/receptive-field safety for every retained interior region.
 */
class TilePlannerTest {

    private fun planOrFail(w: Int, h: Int): TilePlan =
        when (val result = TilePlanner.plan(w, h)) {
            is TilePlanResult.Planned -> result.plan
            is TilePlanResult.UnsupportedSourceSize ->
                throw AssertionError("expected a plan for ${w}x$h")
        }

    @Test
    fun unsupportedSourcesBelowTileSizeAreReportedExplicitly() {
        val tiny = listOf(127 to 128, 128 to 127, 1 to 1, 64 to 64, 127 to 127, 0 to 100)
        for ((w, h) in tiny) {
            if (w <= 0 || h <= 0) continue
            val result = TilePlanner.plan(w, h)
            assertTrue("${w}x$h must be unsupported", result is TilePlanResult.UnsupportedSourceSize)
        }
    }

    @Test
    fun minimumPlanIsSingleFullTile() {
        val plan = planOrFail(128, 128)
        assertEquals(1, plan.tileCount)
        assertEquals(1, plan.tilesX)
        assertEquals(1, plan.tilesY)
        val tile = plan.tiles.single()
        assertEquals(Rect(0, 0, 128, 128), tile.source)
        assertEquals(Rect(0, 0, 512, 512), tile.dest)
        assertEquals(Rect(0, 0, 512, 512), tile.outputCrop)
    }

    @Test
    fun namedPlanarSensitiveDimensionsPassAllProperties() {
        val dims =
            listOf(
                128 to 128, 129 to 128, 128 to 129, 129 to 129,
                187 to 187, 188 to 188, 189 to 189, 191 to 257, 257 to 191,
                256 to 256, 257 to 257, 301 to 227, 227 to 301, 511 to 513,
                513 to 511, 512 to 512, 180 to 200, 200 to 180,
            )
        for ((w, h) in dims) {
            assertPlanProperties(w, h, pixelProof = w <= 300 && h <= 300)
        }
    }

    @Test
    fun generatedRangeWidth128To512PassesAllProperties() {
        val heights =
            listOf(128, 129, 130, 160, 187, 191, 200, 255, 256, 257, 300, 301, 341, 384, 511, 512)
        for (w in 128..512) {
            for (h in heights) {
                assertPlanProperties(w, h, pixelProof = false)
            }
        }
    }

    @Test
    fun generatedRangeHeight128To512PassesAllProperties() {
        val widths = listOf(128, 129, 160, 188, 191, 227, 256, 257, 301, 303, 400, 511, 512)
        for (h in 128..512) {
            for (w in widths) {
                assertPlanProperties(w, h, pixelProof = false)
            }
        }
    }

    @Test
    fun planIsDeterministicAndOrderingIsStable() {
        val plan1 = planOrFail(391, 389)
        val plan2 = planOrFail(391, 389)
        assertEquals(plan1, plan2)
        val parsed = TilePlanner.plan(391, 389)
        assertTrue(parsed is TilePlanResult.Planned)
        val plan = (parsed as TilePlanResult.Planned).plan
        plan.tiles.forEachIndexed { i, tile ->
            assertEquals(i, tile.index)
            assertEquals(i % plan.tilesX, tile.gridX)
            assertEquals(i / plan.tilesX, tile.gridY)
        }
        val indices = plan.tiles.map { it.index }
        assertEquals(indices.sorted(), indices)
    }

    private fun assertPlanProperties(w: Int, h: Int, pixelProof: Boolean) {
        val plan = planOrFail(w, h)
        val outW = w * TilePlanner.SCALE
        val outH = h * TilePlanner.SCALE

        // Axis starts: first 0, last dim-128, strictly increasing.
        val xs = plan.tiles.map { it.source.left }.distinct().sorted()
        val ys = plan.tiles.map { it.source.top }.distinct().sorted()
        assertEquals(0, xs.first())
        assertEquals(w - TilePlanner.TILE_SIZE, xs.last())
        assertEquals(0, ys.first())
        assertEquals(h - TilePlanner.TILE_SIZE, ys.last())
        for (i in 1 until xs.size) assertTrue(xs[i] > xs[i - 1])
        for (i in 1 until ys.size) assertTrue(ys[i] > ys[i - 1])

        var totalArea = 0L
        for (tile in plan.tiles) {
            // Exact 128x128 source tile fully inside the image.
            assertEquals(TilePlanner.TILE_SIZE, tile.source.width)
            assertEquals(TilePlanner.TILE_SIZE, tile.source.height)
            assertTrue(tile.source.left >= 0 && tile.source.top >= 0)
            assertTrue(tile.source.right <= w && tile.source.bottom <= h)

            // Destination fully inside the 4W x 4H output and aligned to the retained crop.
            assertTrue(tile.dest.left >= 0 && tile.dest.top >= 0)
            assertTrue(tile.dest.right <= outW && tile.dest.bottom <= outH)
            assertEquals(tile.dest.width, tile.outputCrop.width)
            assertEquals(tile.dest.height, tile.outputCrop.height)
            assertTrue(tile.outputCrop.left >= 0 && tile.outputCrop.top >= 0)
            assertTrue(tile.outputCrop.right <= TilePlanner.TILE_SIZE * TilePlanner.SCALE)
            assertTrue(tile.outputCrop.bottom <= TilePlanner.TILE_SIZE * TilePlanner.SCALE)

            // Exact x4 source->dest mapping: the owned destination lies within this tile's
            // own x4 source footprint (dest coordinate = source*4 + outputCrop offset).
            assertTrue(tile.dest.left >= tile.source.left * TilePlanner.SCALE)
            assertTrue(tile.dest.right <= tile.source.right * TilePlanner.SCALE)
            assertTrue(tile.dest.top >= tile.source.top * TilePlanner.SCALE)
            assertTrue(tile.dest.bottom <= tile.source.bottom * TilePlanner.SCALE)
            assertEquals(tile.source.left * TilePlanner.SCALE + tile.outputCrop.left, tile.dest.left)
            assertEquals(tile.source.top * TilePlanner.SCALE + tile.outputCrop.top, tile.dest.top)

            // Halo / receptive-field safety on every interior boundary.
            assertHaloSafety(tile, w, h, plan.halo)

            totalArea += tile.dest.width.toLong() * tile.dest.height.toLong()
        }

        // Exact destination coverage: no overlaps + total area == full area => no gaps.
        val n = plan.tiles.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                assertFalse(
                    "tiles $i and $j must not overlap in destination ownership (${plan.tiles[i].dest} vs ${plan.tiles[j].dest})",
                    plan.tiles[i].dest.overlaps(plan.tiles[j].dest),
                )
            }
        }
        assertEquals("total owned area must equal full output area", outW.toLong() * outH.toLong(), totalArea)

        if (pixelProof) {
            assertPixelOwnership(plan, outW, outH)
        }
    }

    private fun assertHaloSafety(tile: TilePlacement, w: Int, h: Int, halo: Int) {
        val haloOut = halo * TilePlanner.SCALE
        val tileOut = TilePlanner.TILE_SIZE * TilePlanner.SCALE
        if (tile.source.left > 0) {
            assertTrue("left interior boundary must be halo-trimmed", tile.outputCrop.left >= haloOut)
        }
        if (tile.source.right < w) {
            assertTrue("right interior boundary must be halo-trimmed", tile.outputCrop.right <= tileOut - haloOut)
        }
        if (tile.source.top > 0) {
            assertTrue("top interior boundary must be halo-trimmed", tile.outputCrop.top >= haloOut)
        }
        if (tile.source.bottom < h) {
            assertTrue("bottom interior boundary must be halo-trimmed", tile.outputCrop.bottom <= tileOut - haloOut)
        }
    }

    /** Direct pixel ownership matrix proving no gap and no double ownership. */
    private fun assertPixelOwnership(plan: TilePlan, outW: Int, outH: Int) {
        val owner = IntArray(outW * outH)
        for (tile in plan.tiles) {
            for (y in tile.dest.top until tile.dest.bottom) {
                for (x in tile.dest.left until tile.dest.right) {
                    val idx = y * outW + x
                    owner[idx]++
                }
            }
        }
        val bad = owner.count { it != 1 }
        assertEquals("every destination pixel must be owned exactly once", 0, bad)
    }
}