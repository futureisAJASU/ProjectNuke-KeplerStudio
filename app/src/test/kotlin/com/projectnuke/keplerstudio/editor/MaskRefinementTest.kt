package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskRefinementTest {
    @Test
    fun identityOptionsPreserveCentralThinBorderAndMultipleSubjects() {
        val fixtures =
            listOf(
                mask(7, 7, setOf(3 to 3)),
                mask(7, 7, (0..6).map { 3 to it }.toSet()),
                mask(7, 7, setOf(0 to 2, 0 to 3, 1 to 2, 5 to 4, 6 to 4)),
                FloatArray(7 * 7) { if (it % 7 in 2..4) 0.51f else 0.49f },
            )

        fixtures.forEach {
            assertContentEquals(
                it,
                MaskRefinement.refine(it, 7, 7, MaskRefinementOptions()),
            )
        }
    }

    @Test
    fun componentFilterRemovesNoiseButPreservesMultipleSubjects() {
        val source =
            mask(
                9,
                5,
                setOf(
                    0 to 0,
                    1 to 1, 2 to 1, 1 to 2, 2 to 2,
                    6 to 1, 7 to 1, 6 to 2, 7 to 2,
                ),
            )

        val result =
            MaskRefinement.refine(
                source,
                9,
                5,
                MaskRefinementOptions(minimumComponentPixels = 3),
            )

        assertEquals(0f, result[0])
        assertEquals(8, result.count { it == 1f })
    }

    @Test
    fun holeFillAndFeatherAreBoundedAndDeterministic() {
        val source = FloatArray(5 * 5) { 1f }.also { it[12] = 0f }
        val options = MaskRefinementOptions(fillSinglePixelHoles = true, featherRadius = 1)

        val first = MaskRefinement.refine(source, 5, 5, options)
        val second = MaskRefinement.refine(source, 5, 5, options)

        assertContentEquals(first, second)
        assertTrue(first.all { it in 0f..1f })
        assertEquals(1f, first[12])
    }

    @Test
    fun erosionAndDilationAreExplicitlyToggleable() {
        val source = mask(5, 5, setOf(2 to 2))
        val dilated =
            MaskRefinement.refine(
                source,
                5,
                5,
                MaskRefinementOptions(dilationRadius = 1),
            )
        val eroded =
            MaskRefinement.refine(
                dilated,
                5,
                5,
                MaskRefinementOptions(erosionRadius = 1),
            )

        assertEquals(9, dilated.count { it == 1f })
        assertEquals(1, eroded.count { it == 1f })
    }

    private fun mask(width: Int, height: Int, points: Set<Pair<Int, Int>>): FloatArray =
        FloatArray(width * height) { index ->
            if ((index % width to index / width) in points) 1f else 0f
        }
}
