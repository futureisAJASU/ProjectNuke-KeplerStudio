package com.projectnuke.keplerstudio.ui

import androidx.compose.ui.geometry.Size
import kotlin.test.assertEquals
import org.junit.Test

class PreviewAnalysisOverlayTest {
    @Test
    fun histogramSeparatesRgbAndUsesRec709Luminance() {
        val histogram =
            calculatePreviewHistogram(
                intArrayOf(
                    0xffff0000.toInt(),
                    0xff00ff00.toInt(),
                    0xff0000ff.toInt(),
                    0x00000000,
                )
            )

        assertEquals(3, histogram.sampledPixels)
        assertEquals(1, histogram.red[255])
        assertEquals(1, histogram.green[255])
        assertEquals(1, histogram.blue[255])
        assertEquals(1, histogram.luminance[54])
        assertEquals(1, histogram.luminance[182])
        assertEquals(1, histogram.luminance[19])
    }

    @Test
    fun fittedPreviewRectRespectsLetterboxing() {
        val wide = fittedPreviewRect(Size(400f, 400f), 400, 200)
        assertEquals(0f, wide.left, 0.001f)
        assertEquals(100f, wide.top, 0.001f)
        assertEquals(400f, wide.right, 0.001f)
        assertEquals(300f, wide.bottom, 0.001f)
    }
}
