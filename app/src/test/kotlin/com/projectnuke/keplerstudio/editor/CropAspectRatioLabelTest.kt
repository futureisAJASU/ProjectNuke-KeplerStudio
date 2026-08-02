package com.projectnuke.keplerstudio.editor

import kotlin.test.assertEquals
import org.junit.Test

class CropAspectRatioLabelTest {
    @Test
    fun `free and original labels use the production Korean labels`() {
        assertEquals("\uC790\uC720", CropAspectRatio.Free.label)
        assertEquals("\uC6D0\uBCF8", CropAspectRatio.Original.label)
    }
}
