package com.projectnuke.keplerstudio.editor

data class MaskRefinementOptions(
    val minimumComponentPixels: Int = 0,
    val fillSinglePixelHoles: Boolean = false,
    val dilationRadius: Int = 0,
    val erosionRadius: Int = 0,
    val featherRadius: Int = 0,
)

object MaskRefinement {
    fun refine(
        source: FloatArray,
        width: Int,
        height: Int,
        options: MaskRefinementOptions,
    ): FloatArray {
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() == source.size.toLong())
        require(source.all { it.isFinite() && it in 0f..1f })
        var current = source.copyOf()
        if (options.minimumComponentPixels > 1) {
            current = removeSmallComponents(current, width, height, options.minimumComponentPixels)
        }
        if (options.fillSinglePixelHoles) current = fillSinglePixelHoles(current, width, height)
        if (options.dilationRadius > 0) {
            current = morphology(current, width, height, options.dilationRadius, dilate = true)
        }
        if (options.erosionRadius > 0) {
            current = morphology(current, width, height, options.erosionRadius, dilate = false)
        }
        if (options.featherRadius > 0) current = boxBlur(current, width, height, options.featherRadius)
        return current
    }

    private fun removeSmallComponents(
        source: FloatArray,
        width: Int,
        height: Int,
        minimum: Int,
    ): FloatArray {
        val output = source.copyOf()
        val visited = BooleanArray(source.size)
        val queue = IntArray(source.size)
        val members = IntArray(source.size)
        source.indices.forEach { start ->
            if (visited[start] || source[start] < 0.5f) return@forEach
            var head = 0
            var tail = 0
            var memberCount = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                members[memberCount++] = index
                val x = index % width
                val y = index / width
                fun enqueue(candidate: Int) {
                    if (!visited[candidate] && source[candidate] >= 0.5f) {
                        visited[candidate] = true
                        queue[tail++] = candidate
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
            if (memberCount < minimum) {
                for (i in 0 until memberCount) output[members[i]] = 0f
            }
        }
        return output
    }

    private fun fillSinglePixelHoles(source: FloatArray, width: Int, height: Int): FloatArray {
        val output = source.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (source[index] >= 0.5f) continue
                if (
                    source[index - 1] >= 0.5f &&
                    source[index + 1] >= 0.5f &&
                    source[index - width] >= 0.5f &&
                    source[index + width] >= 0.5f
                ) {
                    output[index] = 1f
                }
            }
        }
        return output
    }

    private fun morphology(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        dilate: Boolean,
    ): FloatArray {
        val safeRadius = radius.coerceIn(1, maxOf(width, height))
        return FloatArray(source.size) { index ->
            val x = index % width
            val y = index / width
            var value = if (dilate) 0f else 1f
            for (dy in -safeRadius..safeRadius) {
                val yy = (y + dy).coerceIn(0, height - 1)
                for (dx in -safeRadius..safeRadius) {
                    val xx = (x + dx).coerceIn(0, width - 1)
                    value =
                        if (dilate) maxOf(value, source[yy * width + xx])
                        else minOf(value, source[yy * width + xx])
                }
            }
            value
        }
    }

    private fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val safeRadius = radius.coerceIn(1, maxOf(width, height))
        return FloatArray(source.size) { index ->
            val x = index % width
            val y = index / width
            var sum = 0f
            var count = 0
            for (dy in -safeRadius..safeRadius) {
                val yy = (y + dy).coerceIn(0, height - 1)
                for (dx in -safeRadius..safeRadius) {
                    val xx = (x + dx).coerceIn(0, width - 1)
                    sum += source[yy * width + xx]
                    count++
                }
            }
            (sum / count).coerceIn(0f, 1f)
        }
    }
}
