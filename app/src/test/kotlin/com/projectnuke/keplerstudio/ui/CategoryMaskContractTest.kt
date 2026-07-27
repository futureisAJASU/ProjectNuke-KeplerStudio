package com.projectnuke.keplerstudio.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CategoryMaskContractTest {
    @Test
    fun onlyDeclaredCategoriesBecomeForeground() {
        val foreground = setOf(1, 4)
        assertEquals(0, categoryMaskAlpha(0, foreground))
        assertEquals(255, categoryMaskAlpha(1, foreground))
        assertEquals(0, categoryMaskAlpha(2, foreground))
        assertEquals(255, categoryMaskAlpha(4, foreground))
    }

    @Test
    fun emptyMappingFailsClosed() {
        assertFailsWith<IllegalArgumentException> { categoryMaskAlpha(1, emptySet()) }
    }
}

