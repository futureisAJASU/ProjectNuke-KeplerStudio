package com.projectnuke.keplerstudio.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeScratchPlannerTest {
    @Test
    fun plansKnownMainAndSpecialEffectBuffers() {
        assertEquals(
            5L * 4096L,
            NativeScratchPlanner.mainRender(4096, needsFiveRows = true, needsThreeRows = true)
                .knownBytes,
        )
        assertEquals(
            4096L * 3000L,
            NativeScratchPlanner.specialEffect(4096, 3000, effect = 0).knownBytes,
        )
        assertEquals(
            0L,
            NativeScratchPlanner.specialEffect(4096, 3000, effect = 1).knownBytes,
        )
    }

    @Test
    fun flarePlansMatchNativeTemporaryPolicies() {
        val correction = NativeScratchPlanner.flareCorrection(4000, 1000, 500)
        assertEquals(4000L * 500L + 1000L * 500L * 4L * 3L, correction.knownBytes)
        assertEquals(
            200L,
            NativeScratchPlanner.flareMask(10, 10, radius = 3, passes = 2).knownBytes,
        )
        assertEquals(
            100L,
            NativeScratchPlanner.flareMask(10, 10, radius = 0, passes = 2).knownBytes,
        )
    }

    @Test
    fun excessiveOrOverflowingPlansFailBeforeJni() {
        assertFalse(
            NativeScratchPlanner.specialEffect(200_000, 2_000, effect = 3)
                .withinNativeBudget,
        )
        assertFailsWith<IllegalArgumentException> {
            NativeScratchPlanner.flareCorrection(0, Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertTrue(NativeScratchPlanner.crop().withinNativeBudget)
        assertTrue(NativeScratchPlanner.selectionBlend().withinNativeBudget)
    }
}
