package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PendingHistorySnapshotTest {
    private fun snapshot(bitmap: Bitmap) =
        EditorHistorySnapshot(
            params = EditParams(),
            correctionEngine = CorrectionEngine.Engine1,
            noiseEngine = NoiseEngine.FastEdgeAware,
            detailEngine = DetailEngine.MaskedUnsharp,
            toneEngine = ToneEngine.HistogramAuto,
            hazeEngine = DehazeEngine.FastContrast,
            baseBitmapDirty = false,
            baseContentToken = "test",
            previewBitmap = bitmap,
            originalPreviewBitmap = null,
            presetLook = null,
            cropState = CropState(),
            selectionLayers = emptyList(),
            activeSelectionLayerId = null,
            selectionPaintSettings = SelectionPaintSettings(),
            showSelectionOverlay = false,
            activeQuickEffects = emptyList(),
            flareGuardRuntimeStatus = null,
        )

    @Test
    fun `close after producer completion recycles unclaimed result`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pending = PendingHistorySnapshot(CompletableDeferred())
        pending.complete(snapshot(bitmap))

        pending.close()

        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `take transfers result and later close does not recycle it`() = runBlocking {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val value = snapshot(bitmap)
        val pending = PendingHistorySnapshot(CompletableDeferred())
        pending.complete(value)

        assertSame(value, pending.await())
        pending.close()

        assertFalse(bitmap.isRecycled)
        value.recycleBitmaps()
    }

    @Test
    fun `close before completion rejects and recycles late result`() = runBlocking {
        val deferred = CompletableDeferred<EditorHistorySnapshot?>()
        val pending = PendingHistorySnapshot(deferred)
        pending.close()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        pending.complete(snapshot(bitmap))

        assertNull(pending.await())
        assertTrue(bitmap.isRecycled)
    }
}
