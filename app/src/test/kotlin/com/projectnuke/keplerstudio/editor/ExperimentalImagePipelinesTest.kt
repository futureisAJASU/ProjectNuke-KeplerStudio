package com.projectnuke.keplerstudio.editor

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class ExperimentalImagePipelinesTest {
    @Test
    fun productionSelectionDefaultsToV1() {
        ExperimentalLabController.resetForTest()
        assertEquals(ExperimentalLabSelection(), ExperimentalLabController.resolvedSelection(CorrectionEngine.Engine1))
    }

    @Test
    fun flareV2RulePathIsDeterministicBoundedAndExact() {
        val source = flareFixture(9, 7)

        val first =
            FlareGuardV2.process(source, 9, 7, FlareGuardMode.NightLight, strength = 0.65f)
        val second =
            FlareGuardV2.process(source, 9, 7, FlareGuardMode.NightLight, strength = 0.65f)

        assertContentEquals(first.argb, second.argb)
        assertContentEquals(first.mask, second.mask)
        assertEquals(FlareGuardV2Decision.RuleSelected, first.decision)
        assertTrue(first.argb.indices.any { first.argb[it] != source[it] })
        assertTrue(maximumChannelDelta(source, first.argb) <= 32)
        assertEquals(
            -5621778256173100593L,
            exactHash(first.argb),
            "record contract-2 point-light/veiling protected V2 fixture hash",
        )
    }

    @Test
    fun flareV2RejectsNoisyModelAndFallsBackToRuleMask() {
        val source = flareFixture(9, 7)
        val noise = FloatArray(source.size) { if (it % 2 == 0) 0.9f else 0f }

        val result =
            FlareGuardV2.process(
                source,
                9,
                7,
                FlareGuardMode.DaySun,
                0.5f,
                modelMask = noise,
            )

        assertEquals(FlareGuardV2Decision.ModelRejectedByQuality, result.decision)
        assertTrue(result.decisionDetail?.contains("rejected") == true)
    }

    @Test
    fun flareV2ZeroStrengthIsIdentityAndCancellationStopsPass() {
        val source = flareFixture(16, 16)
        assertContentEquals(
            source,
            FlareGuardV2.process(source, 16, 16, FlareGuardMode.DaySun, 0f).argb,
        )
        var checks = 0
        assertFailsWith<CancellationException> {
            FlareGuardV2.process(source, 16, 16, FlareGuardMode.DaySun, 0.5f) {
                ++checks > 1
            }
        }
    }

    @Test
    fun flareV2SuppressesHighlightTextureFalsePositives() {
        val width = 32
        val height = 24
        val source =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x + y) % 2 == 0) {
                    argb(255, 250, 238, 218)
                } else {
                    argb(255, 132, 121, 108)
                }
            }

        val result =
            FlareGuardV2.process(
                source,
                width,
                height,
                FlareGuardMode.DaySun,
                strength = 1f,
            )
        val changedRatio =
            result.argb.indices.count { result.argb[it] != source[it] }.toFloat() /
                source.size

        assertTrue(changedRatio <= 0.05f, "textured highlights must not become a broad flare mask")
    }

    @Test
    fun flareV2ProtectsPointLightCoreButCorrectsBroadWarmVeil() {
        val width = 31
        val height = 21
        val pointLight =
            IntArray(width * height) { argb(255, 24, 26, 30) }.also {
                it[(height / 2) * width + width / 2] = argb(255, 255, 250, 232)
            }
        val protected =
            FlareGuardV2.process(
                pointLight,
                width,
                height,
                FlareGuardMode.NightLight,
                1f,
            )
        assertTrue(
            protected.argb.indices.count { protected.argb[it] != pointLight[it] } <= 2,
            "a compact point-light core must not seed a broad correction",
        )

        val warmVeil =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val dx = (x - width / 2f) / width
                val dy = (y - height / 2f) / height
                if (dx * dx + dy * dy < 0.11f) {
                    argb(255, 212, 166, 104)
                } else {
                    argb(255, 104, 108, 112)
                }
            }
        val corrected =
            FlareGuardV2.process(
                warmVeil,
                width,
                height,
                FlareGuardMode.DaySun,
                0.8f,
            )
        assertTrue(
            corrected.argb.indices.count { corrected.argb[it] != warmVeil[it] } >
                warmVeil.size / 10,
            "a smooth warm veil should remain reachable",
        )
    }

    @Test
    fun remasterV2PreservesAlphaMultipleSubjectsAndHasExactFixture() {
        val width = 9
        val height = 7
        val source =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val alpha = if (x == 0) 96 else 255
                val base = 45 + x * 17 + y * 8
                argb(alpha, base.coerceAtMost(255), (base + 9).coerceAtMost(255), (base + 4).coerceAtMost(255))
            }
        val mask = FloatArray(source.size)
        for (y in 1..5) {
            for (x in 1..2) mask[y * width + x] = 1f
            for (x in 6..7) mask[y * width + x] = 1f
        }

        val result = RemasterV2.process(source, mask, width, height)

        assertEquals(2, result.maskMetrics.connectedComponentCount)
        assertTrue(result.argb.indices.all { alpha(result.argb[it]) == alpha(source[it]) })
        assertTrue(maximumChannelDelta(source, result.argb) <= 24)
        assertEquals(
            1521270168617583706L,
            exactHash(result.argb),
            "record contract-3 alpha/boundary protected fixture hash",
        )
    }

    @Test
    fun remasterV2TranslucentEdgesRemainStableOverMultipleBackgrounds() {
        val width = 7
        val height = 5
        val alphas = intArrayOf(16, 48, 96, 160, 224, 255, 96)
        val source =
            IntArray(width * height) { index ->
                argb(alphas[index % width], 188, 132, 104)
            }
        val result =
            RemasterV2.process(
                source,
                FloatArray(source.size) { 1f },
                width,
                height,
                foregroundStrength = 0.9f,
                backgroundStrength = 0.4f,
            )
        val backgrounds =
            intArrayOf(
                argb(255, 0, 0, 0),
                argb(255, 255, 255, 255),
                argb(255, 32, 96, 180),
            )

        result.argb.indices.forEach { index ->
            assertEquals(alpha(source[index]), alpha(result.argb[index]))
            backgrounds.forEach { background ->
                val before = compositeOpaque(source[index], background)
                val after = compositeOpaque(result.argb[index], background)
                val maximumAllowed = if (alpha(source[index]) <= 48) 1 else 10
                assertTrue(maxChannelDelta(before, after) <= maximumAllowed)
            }
        }
    }

    @Test
    fun remasterV2RejectsMalformedMaskAndCancels() {
        assertFailsWith<IllegalArgumentException> {
            RemasterV2.process(IntArray(4), floatArrayOf(1f, Float.NaN, 0f, 0f), 2, 2)
        }
        var checks = 0
        assertFailsWith<CancellationException> {
            RemasterV2.process(
                IntArray(128 * 128) { argb(255, 80, 90, 100) },
                FloatArray(128 * 128) { 1f },
                128,
                128,
                isCancelled = { ++checks > 2 },
            )
        }
    }

    @Test
    fun subjectSelectionV2PreservesMultipleAndBorderTouchingSubjects() {
        val width = 9
        val height = 7
        val raw = FloatArray(width * height)
        for (y in 1..5) {
            raw[y * width] = 0.95f
            raw[y * width + 1] = 0.95f
            raw[y * width + 6] = 0.9f
            raw[y * width + 7] = 0.9f
        }
        raw[4 * width + 4] = 0.7f
        val operation = operation(41L, "doc-a")

        val result = SubjectSelectionV2.refine(raw, width, height, operation)

        assertEquals(2, result.metrics.connectedComponentCount)
        assertTrue(result.metrics.borderContactRatio > 0f)
        assertEquals(41L, result.operationToken)
        assertEquals("doc-a", result.documentGeneration)
        assertEquals(
            -8522811236559730425L,
            exactHash(result.mask),
            "record thin-structure-preserving V2 fixture hash",
        )
    }

    @Test
    fun subjectSelectionV2ManualEditsAndIdentityAreEnforced() {
        val raw = FloatArray(25).also { it[12] = 1f }
        val manual = FloatArray(25).also {
            it[6] = 1f
            it[7] = 1f
            it[11] = 1f
            it[12] = 1f
        }
        val added =
            SubjectSelectionV2.refine(
                raw,
                5,
                5,
                operation(5L, "doc"),
                manual,
                ManualMaskEditMode.Add,
            )
        assertTrue(added.mask[6] > 0f)

        assertFailsWith<StaleModelGenerationException> {
            SubjectSelectionV2.refine(
                raw,
                5,
                5,
                operation(5L, "stale", current = false),
            )
        }
        assertFailsWith<CancellationException> {
            SubjectSelectionV2.refine(
                raw,
                5,
                5,
                operation(5L, "doc", cancelled = true),
            )
        }
    }

    @Test
    fun subjectSelectionV2KeepsSupportedThinStructuresAndManualPoints() {
        val width = 7
        val height = 7
        val raw = FloatArray(width * height)
        for (y in 0 until height) raw[y * width + 3] = 0.9f
        raw[width + 1] = 0.98f

        val refined =
            SubjectSelectionV2.refine(
                raw,
                width,
                height,
                operation(17L, "thin"),
            )

        assertTrue((0 until height).all { refined.mask[it * width + 3] >= 0.72f })
        assertTrue(refined.mask[width + 1] < 0.35f)

        val manual = FloatArray(width * height).also { it[width * 5 + 5] = 1f }
        val manualResult =
            SubjectSelectionV2.refine(
                FloatArray(width * height),
                width,
                height,
                operation(18L, "manual-point"),
                manualMask = manual,
                manualMode = ManualMaskEditMode.Add,
            )
        assertTrue(manualResult.mask[width * 5 + 5] >= 0.9f)
    }

    @Test
    fun subjectSelectionV2KeepsLowConfidenceBridgeAndHonorsSubtractAfterFeather() {
        val width = 9
        val height = 5
        val raw = FloatArray(width * height)
        for (y in 1..3) {
            raw[y * width + 2] = 0.9f
            raw[y * width + 6] = 0.9f
        }
        raw[2 * width + 3] = 0.38f
        raw[2 * width + 4] = 0.38f
        raw[2 * width + 5] = 0.38f
        val connected =
            SubjectSelectionV2.refine(
                raw,
                width,
                height,
                operation(19L, "low-confidence-bridge"),
            )
        assertTrue(connected.mask[2 * width + 4] >= 0.35f)
        assertEquals(1, connected.metrics.connectedComponentCount)

        val subtract = FloatArray(width * height)
        subtract[2 * width + 4] = 1f
        subtract[2 * width + 3] = 0.7f
        subtract[2 * width + 5] = 0.7f
        val removed =
            SubjectSelectionV2.refine(
                raw,
                width,
                height,
                operation(20L, "subtract-authority"),
                manualMask = subtract,
                manualMode = ManualMaskEditMode.Subtract,
            )
        assertEquals(0f, removed.mask[2 * width + 4])
        assertTrue(removed.mask[2 * width + 3] < connected.mask[2 * width + 3])
        assertTrue(removed.mask[2 * width + 5] < connected.mask[2 * width + 5])
    }

    @Test
    fun experimentalPlannersRejectOverflowBeforeAllocation() {
        assertFailsWith<IllegalArgumentException> {
            FlareGuardV2.plan(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            RemasterV2.plan(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun operation(
        token: Long,
        generation: String,
        current: Boolean = true,
        cancelled: Boolean = false,
    ) =
        ModelOperationContext(
            operationToken = token,
            documentGeneration = generation,
            isCurrent = { operationToken, documentGeneration ->
                current && operationToken == token && documentGeneration == generation
            },
            isCancelled = { cancelled },
        )

    private fun flareFixture(width: Int, height: Int): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = x - width / 2
            val dy = y - height / 2
            val distance = abs(dx) + abs(dy)
            when {
                distance == 0 -> argb(255, 255, 238, 126)
                distance <= 2 -> argb(255, 238, 191, 92)
                else -> argb(255, 24 + x * 3, 28 + y * 2, 35 + x)
            }
        }

    private fun maximumChannelDelta(before: IntArray, after: IntArray): Int =
        before.indices.maxOf { index ->
            maxOf(
                abs(red(before[index]) - red(after[index])),
                abs(green(before[index]) - green(after[index])),
                abs(blue(before[index]) - blue(after[index])),
            )
        }

    private fun exactHash(values: IntArray): Long {
        var hash = -0x340d631b7bdddcdbL
        values.forEach { value ->
            repeat(4) { byte ->
                hash = (hash xor ((value ushr (byte * 8)) and 0xff).toLong()) * 0x100000001b3L
            }
        }
        return hash
    }

    private fun exactHash(values: FloatArray): Long =
        exactHash(IntArray(values.size) { values[it].toRawBits() })

    private fun alpha(argb: Int) = (argb ushr 24) and 0xff
    private fun red(argb: Int) = (argb ushr 16) and 0xff
    private fun green(argb: Int) = (argb ushr 8) and 0xff
    private fun blue(argb: Int) = argb and 0xff
    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun compositeOpaque(foreground: Int, background: Int): Int {
        val alpha = alpha(foreground)
        fun channel(shift: Int): Int {
            val front = (foreground ushr shift) and 0xff
            val back = (background ushr shift) and 0xff
            return (front * alpha + back * (255 - alpha) + 127) / 255
        }
        return argb(255, channel(16), channel(8), channel(0))
    }

    private fun maxChannelDelta(left: Int, right: Int): Int =
        maxOf(
            abs(red(left) - red(right)),
            abs(green(left) - green(right)),
            abs(blue(left) - blue(right)),
        )
}
