package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentalPipelinePlanningTest {
    @Test
    fun flarePlanIncludesConversionAnalysisAndRefinementOverlap() {
        val plan = FlareGuardV2.plan(1920, 1080)
        assertEquals(
            setOf("bitmap-conversion", "mask-analysis", "mask-refinement"),
            plan.stageBytes.keys,
        )
        assertEquals(plan.knownTransientBytes, plan.stageBytes.values.sum())
        assertTrue(plan.hasUnknownContributors)
        assertTrue(plan.knownTransientBytes > 1920L * 1080L * 24L)
    }

    @Test
    fun remasterPlanUsesTheLargerRealStagePeak() {
        val plan = RemasterV2.plan(640, 480)
        assertEquals(
            plan.stageBytes.values.max(),
            plan.knownTransientBytes,
        )
        assertTrue(plan.knownTransientBytes >= 640L * 480L * 28L)
        assertTrue(!plan.hasUnknownContributors)
    }

    @Test
    fun subjectManualPlanAccountsForAllInputPlanes() {
        val model = SubjectSelectionV2.plan(320, 240, includesManualMask = false)
        val combined = SubjectSelectionV2.plan(320, 240, includesManualMask = true)
        assertEquals(320L * 240L * 8L, combined.knownTransientBytes - model.knownTransientBytes)
    }

    @Test
    fun everyPlannerRejectsOverflowBeforeAllocation() {
        assertFailsWith<IllegalArgumentException> {
            SubjectSelectionV2.plan(Int.MAX_VALUE, Int.MAX_VALUE, true)
        }
        assertFailsWith<IllegalArgumentException> {
            FlareGuardV2.plan(Int.MAX_VALUE, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            RemasterV2.plan(Int.MAX_VALUE, 2)
        }
    }
}

