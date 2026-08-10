package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Narrow, owner-bound test Bitmap-copy seam.
 *
 * Provides an override for [Bitmap.copy] used by [Bitmap.copyOrThrow] so tests can
 * prove independence via [Bitmap.createBitmap] + [Canvas.drawBitmap] instead of the
 * native GraphicsMode shadow. The copied Bitmap must not be the same object, must not
 * share recycling state, and must preserve pixels, dimensions, and config.
 */
internal object BitmapCopyTestSeam {
    private val lock = Any()
    @Volatile private var installed: Boolean = false
    @Volatile private var customCopyEnabled: Boolean = false

    /** Enables the custom Bitmap-copy fallback for the duration of this installation. */
    internal fun install(): AutoCloseable {
        synchronized(lock) {
            check(!installed) { "BitmapCopyTestSeam already installed" }
            installed = true
        }
        customCopyEnabled = true
        return AutoCloseable {
            synchronized(lock) {
                installed = false
            }
            customCopyEnabled = false
        }
    }

    internal fun isInstalled(): Boolean = installed

    internal fun isCustomCopyEnabled(): Boolean = customCopyEnabled

    /**
     * Custom owner-bound copy using createBitmap + drawBitmap.
     * Returns a new Bitmap that is guaranteed to be a different object reference
     * and not share recycling state with [source].
     */
    internal fun copyOwned(source: Bitmap, config: Bitmap.Config, mutable: Boolean): Bitmap {
        check(!source.isRecycled) { "bitmap is recycled" }
        val copy = Bitmap.createBitmap(source.width, source.height, config, mutable)
        val canvas = Canvas(copy)
        canvas.drawBitmap(source, 0f, 0f, null)
        return copy
    }
}
