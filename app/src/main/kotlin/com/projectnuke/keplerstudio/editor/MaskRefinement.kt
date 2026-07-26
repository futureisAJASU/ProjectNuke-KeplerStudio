package com.projectnuke.keplerstudio.editor

data class MaskRefinementOptions(
    val minimumComponentPixels: Int = 0,
    val fillSinglePixelHoles: Boolean = false,
    val dilationRadius: Int = 0,
    val erosionRadius: Int = 0,
    val featherRadius: Int = 0,
    val connectivity: MaskConnectivity = MaskConnectivity.Four,
    val activationThreshold: Float = 0.5f,
)

data class MaskRefinementPlan(
    val pixelCount: Int,
    val maximumRadius: Int,
    val knownPeakTransientBytes: Long,
)

object MaskRefinement {
    const val HARD_MAXIMUM_RADIUS = 64

    fun plan(
        width: Int,
        height: Int,
        options: MaskRefinementOptions,
    ): MaskRefinementPlan {
        require(width > 0 && height > 0)
        validateOptions(options)
        val pixelCountLong =
            runCatching { Math.multiplyExact(width.toLong(), height.toLong()) }.getOrNull()
        require(pixelCountLong != null) { "Mask dimensions overflow the supported element count" }
        require(pixelCountLong <= Int.MAX_VALUE) { "Mask dimensions exceed supported element count" }
        val pixelCount = pixelCountLong.toInt()
        val sourceCopyBytes = Math.multiplyExact(pixelCountLong, Float.SIZE_BYTES.toLong())
        val twoFloatPlanes = Math.multiplyExact(sourceCopyBytes, 2L)
        val componentWorkspace =
            if (options.minimumComponentPixels > 1) {
                Math.addExact(
                    pixelCountLong,
                    Math.multiplyExact(pixelCountLong, Int.SIZE_BYTES.toLong()),
                )
            } else {
                0L
            }
        val lineLength =
            Math.addExact(
                maxOf(width, height).toLong(),
                Math.addExact(2L * maximumRadius(options), 1L),
            )
        val lineWorkspace = Math.multiplyExact(lineLength, Int.SIZE_BYTES.toLong())
        return MaskRefinementPlan(
            pixelCount = pixelCount,
            maximumRadius = maximumRadius(options),
            knownPeakTransientBytes =
                Math.addExact(
                    sourceCopyBytes,
                    maxOf(twoFloatPlanes + lineWorkspace, componentWorkspace),
                ),
        )
    }

    fun refine(
        source: FloatArray,
        width: Int,
        height: Int,
        options: MaskRefinementOptions,
        isCancelled: () -> Boolean = { false },
    ): FloatArray {
        val plan = plan(width, height, options)
        require(source.size == plan.pixelCount)
        require(source.all { it.isFinite() && it in 0f..1f })
        var current = source.copyOf()
        if (options.minimumComponentPixels > 1) {
            current =
                removeSmallComponents(
                    current,
                    width,
                    height,
                    options,
                    isCancelled,
                )
        }
        checkNotCancelled(isCancelled)
        if (options.fillSinglePixelHoles) {
            current = fillSinglePixelHoles(current, width, height, isCancelled)
        }
        if (options.dilationRadius > 0) {
            current =
                morphologySeparable(
                    current,
                    width,
                    height,
                    options.dilationRadius,
                    dilate = true,
                    isCancelled,
                )
        }
        if (options.erosionRadius > 0) {
            current =
                morphologySeparable(
                    current,
                    width,
                    height,
                    options.erosionRadius,
                    dilate = false,
                    isCancelled,
                )
        }
        if (options.featherRadius > 0) {
            current = boxBlurSeparable(current, width, height, options.featherRadius, isCancelled)
        }
        return current
    }

    private fun removeSmallComponents(
        source: FloatArray,
        width: Int,
        height: Int,
        options: MaskRefinementOptions,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val output = source.copyOf()
        val state = ByteArray(source.size) { index ->
            if (source[index] >= options.activationThreshold) 1 else 0
        }
        val queue = IntArray(source.size)
        state.indices.forEach { start ->
            if (state[start].toInt() != 1) return@forEach
            var head = 0
            var tail = 0
            queue[tail++] = start
            state[start] = 2
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun enqueue(xx: Int, yy: Int) {
                    if (xx !in 0 until width || yy !in 0 until height) return
                    val candidate = yy * width + xx
                    if (state[candidate].toInt() == 1) {
                        state[candidate] = 2
                        queue[tail++] = candidate
                    }
                }
                enqueue(x - 1, y)
                enqueue(x + 1, y)
                enqueue(x, y - 1)
                enqueue(x, y + 1)
                if (options.connectivity == MaskConnectivity.Eight) {
                    enqueue(x - 1, y - 1)
                    enqueue(x + 1, y - 1)
                    enqueue(x - 1, y + 1)
                    enqueue(x + 1, y + 1)
                }
            }
            if (tail < options.minimumComponentPixels) {
                for (index in 0 until tail) output[queue[index]] = 0f
            }
            checkNotCancelled(isCancelled)
        }
        return output
    }

    private fun fillSinglePixelHoles(
        source: FloatArray,
        width: Int,
        height: Int,
        isCancelled: () -> Boolean,
    ): FloatArray {
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
            if ((y and 63) == 0) checkNotCancelled(isCancelled)
        }
        return output
    }

    private fun morphologySeparable(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        dilate: Boolean,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)
        val deque = IntArray(maxOf(width, height) + radius * 2 + 1)
        for (y in 0 until height) {
            slidingExtremumLine(
                length = width,
                radius = radius,
                deque = deque,
                sample = { x -> source[y * width + x] },
                write = { x, value -> horizontal[y * width + x] = value },
                maximum = dilate,
            )
            if ((y and 31) == 0) checkNotCancelled(isCancelled)
        }
        for (x in 0 until width) {
            slidingExtremumLine(
                length = height,
                radius = radius,
                deque = deque,
                sample = { y -> horizontal[y * width + x] },
                write = { y, value -> output[y * width + x] = value },
                maximum = dilate,
            )
            if ((x and 31) == 0) checkNotCancelled(isCancelled)
        }
        return output
    }

    private fun slidingExtremumLine(
        length: Int,
        radius: Int,
        deque: IntArray,
        sample: (Int) -> Float,
        write: (Int, Float) -> Unit,
        maximum: Boolean,
    ) {
        var head = 0
        var tail = 0
        for (virtual in -radius until length + radius) {
            val sampleIndex = virtual.coerceIn(0, length - 1)
            val value = sample(sampleIndex)
            while (head < tail) {
                val previousVirtual = deque[tail - 1]
                val previous = sample(previousVirtual.coerceIn(0, length - 1))
                if (if (maximum) previous > value else previous < value) break
                tail--
            }
            deque[tail++] = virtual
            val firstAllowed = virtual - radius * 2
            while (head < tail && deque[head] < firstAllowed) head++
            val outputIndex = virtual - radius
            if (outputIndex in 0 until length) {
                write(outputIndex, sample(deque[head].coerceIn(0, length - 1)))
            }
        }
    }

    private fun boxBlurSeparable(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)
        val windowSize = radius * 2 + 1
        for (y in 0 until height) {
            var sum = 0.0
            for (virtual in -radius..radius) {
                sum += source[y * width + virtual.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = (sum / windowSize).toFloat().coerceIn(0f, 1f)
                val outgoing = (x - radius).coerceIn(0, width - 1)
                val incoming = (x + radius + 1).coerceIn(0, width - 1)
                sum += source[y * width + incoming] - source[y * width + outgoing]
            }
            if ((y and 31) == 0) checkNotCancelled(isCancelled)
        }
        for (x in 0 until width) {
            var sum = 0.0
            for (virtual in -radius..radius) {
                sum += horizontal[virtual.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                output[y * width + x] = (sum / windowSize).toFloat().coerceIn(0f, 1f)
                val outgoing = (y - radius).coerceIn(0, height - 1)
                val incoming = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[incoming * width + x] - horizontal[outgoing * width + x]
            }
            if ((x and 31) == 0) checkNotCancelled(isCancelled)
        }
        return output
    }

    private fun maximumRadius(options: MaskRefinementOptions): Int =
        maxOf(options.dilationRadius, options.erosionRadius, options.featherRadius)

    private fun validateOptions(options: MaskRefinementOptions) {
        require(options.minimumComponentPixels >= 0)
        require(options.activationThreshold.isFinite() && options.activationThreshold in 0f..1f)
        listOf(options.dilationRadius, options.erosionRadius, options.featherRadius).forEach {
            require(it in 0..HARD_MAXIMUM_RADIUS) {
                "Mask refinement radius exceeds the hard maximum"
            }
        }
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("Mask refinement cancelled")
    }
}
