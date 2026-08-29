package com.projectnuke.keplerstudio.editor

/**
 * Phase N4 — pure, deterministic, platform-independent tile planner.
 *
 * The compiled NNC accepts exactly 128x128 LR pixels and returns exactly 512x512 HR
 * pixels (x4). To upscale an image larger than 128 in either dimension, the image is
 * partitioned into 128x128 source tiles with an overlap-and-crop policy: overlapping
 * source tiles are inferred, the boundary-contaminated output band (the receptive-field
 * halo — see docs/exynos-ai/N4_TILING_CONTRACT.md) is discarded, and every destination
 * pixel is assigned to exactly one tile.
 *
 * This object performs no ENN work and has no Android/platform dependency. It only
 * computes integer rectangles. See [TilePlanner.plan] and [TilePlanResult].
 */
internal object TilePlanner {

    /** Fixed compiled source tile width/height (LR pixels). */
    const val TILE_SIZE = 128

    /** Fixed upscale factor of the compiled model. */
    const val SCALE = 4

    /**
     * Receptive-field radius of the pinned SRVGGNetCompact architecture
     * (1 first + 32 body + 1 final stride-1 3x3 convolution = 34 layers), derived and
     * independently host-verified. A retained output must be at least this far (in LR
     * pixels) from any artificial tile boundary to be independent of that boundary.
     */
    const val RECEPTIVE_FIELD_RADIUS = 34

    /** Production halo per interior boundary (LR pixels), equals the derived radius. */
    const val HALO = RECEPTIVE_FIELD_RADIUS

    /** Usable core of a tile whose two boundaries are interior (LR pixels). */
    const val USABLE_CORE = TILE_SIZE - 2 * HALO

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
    ): TilePlanResult = plan(sourceWidth, sourceHeight, HALO)

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        halo: Int,
    ): TilePlanResult {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "source dimensions must be positive (got ${sourceWidth}x$sourceHeight)"
        }
        require(halo >= 0 && halo < TILE_SIZE / 2) {
            "halo must be in [0, ${TILE_SIZE / 2 - 1}] (got $halo)"
        }
        if (sourceWidth < TILE_SIZE || sourceHeight < TILE_SIZE) {
            return TilePlanResult.UnsupportedSourceSize(sourceWidth, sourceHeight)
        }

        val xAxis = planAxis(sourceWidth, halo)
        val yAxis = planAxis(sourceHeight, halo)

        val tiles = mutableListOf<TilePlacement>()
        var index = 0
        for (iy in yAxis.starts.indices) {
            for (ix in xAxis.starts.indices) {
                val srcX = xAxis.starts[ix]
                val srcY = yAxis.starts[iy]
                val outLeft = xAxis.edges[ix]
                val outRight = xAxis.edges[ix + 1]
                val outTop = yAxis.edges[iy]
                val outBottom = yAxis.edges[iy + 1]
                require(outRight > outLeft && outBottom > outTop)
                tiles +=
                    TilePlacement(
                        index = index,
                        gridX = ix,
                        gridY = iy,
                        source = Rect(srcX, srcY, TILE_SIZE, TILE_SIZE),
                        outputCrop =
                            Rect(
                                outLeft - srcX * SCALE,
                                outTop - srcY * SCALE,
                                outRight - outLeft,
                                outBottom - outTop,
                            ),
                        dest = Rect(outLeft, outTop, outRight - outLeft, outBottom - outTop),
                    )
                index++
            }
        }
        check(tiles.isNotEmpty())
        return TilePlanResult.Planned(
            TilePlan(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                halo = halo,
                tilesX = xAxis.starts.size,
                tilesY = yAxis.starts.size,
                tiles = tiles,
            )
        )
    }

    /**
     * One-axis tiling. Produces strictly increasing, deduplicated LR source starts
     * (first = 0, last = dim - TILE_SIZE, adjacent distance never exceeding the usable
     * core step) and the destination ownership edges (in x4 output coordinates) that
     * partition [0, dim*SCALE) exactly, splitting each adjacent overlap at the midpoint
     * between the two tiles' halo-trimmed valid regions.
     */
    internal fun planAxis(dim: Int, halo: Int): AxisPlan {
        require(dim >= TILE_SIZE)
        val step = TILE_SIZE - 2 * halo
        require(step >= 1) { "usable core step must be positive (halo too large)" }

        val starts = mutableListOf<Int>()
        var pos = 0
        while (true) {
            starts += pos
            if (pos >= dim - TILE_SIZE) break
            val next = pos + step
            pos = if (next >= dim - TILE_SIZE) dim - TILE_SIZE else next
        }
        // Strictly increasing by construction (each append is strictly greater).
        for (i in 1 until starts.size) {
            check(starts[i] > starts[i - 1]) { "tile starts must be strictly increasing" }
        }

        // Destination ownership edges in output (x4) coordinates.
        val edges = mutableListOf<Int>()
        edges += 0
        for (t in 0 until starts.size - 1) {
            val leftValidNext =
                if (starts[t + 1] == 0) 0 else starts[t + 1] + halo
            val rightValidCurrent =
                if (starts[t] + TILE_SIZE == dim) dim else starts[t] + TILE_SIZE - halo
            // 4 * midpoint(leftValidNext, rightValidCurrent) = integer output split.
            edges += (leftValidNext + rightValidCurrent) * 2
        }
        edges += dim * SCALE

        for (i in 1 until edges.size) {
            check(edges[i] > edges[i - 1]) { "axis ownership edges must be strictly increasing" }
        }
        return AxisPlan(starts = starts, edges = edges)
    }
}

/** Per-axis planner output: `edges.size == starts.size + 1`. */
internal data class AxisPlan(
    val starts: List<Int>,
    val edges: List<Int>,
)

/** Axis-aligned integer rectangle with non-negative position and positive extent. */
internal data class Rect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0) {
            "invalid rectangle (left=$left top=$top width=$width height=$height)"
        }
    }

    fun overlaps(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * One tile placement. [source] is the 128x128 LR crop; [outputCrop] is the retained
 * region inside the 512x512 tile output (tile-local coordinates); [dest] is the same
 * region's position inside the full 4W x 4H output. [outputCrop].width == [dest].width
 * and [outputCrop].height == [dest].height.
 */
internal data class TilePlacement(
    val index: Int,
    val gridX: Int,
    val gridY: Int,
    val source: Rect,
    val outputCrop: Rect,
    val dest: Rect,
)

internal data class TilePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val halo: Int,
    val tilesX: Int,
    val tilesY: Int,
    val tiles: List<TilePlacement>,
) {
    val outputWidth: Int get() = sourceWidth * TilePlanner.SCALE
    val outputHeight: Int get() = sourceHeight * TilePlanner.SCALE
    val tileCount: Int get() = tiles.size
}

internal sealed interface TilePlanResult {
    data class Planned(val plan: TilePlan) : TilePlanResult

    /** width < 128 or height < 128; N4 does not synthesize a tiny-image correctness policy. */
    data class UnsupportedSourceSize(val width: Int, val height: Int) : TilePlanResult
}