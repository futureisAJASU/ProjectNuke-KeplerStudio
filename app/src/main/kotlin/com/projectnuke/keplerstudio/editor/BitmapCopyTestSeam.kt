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
    private var activeInstallation: Installation? = null

    private class Installation : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                if (activeInstallation === this) activeInstallation = null
            }
        }
    }

    /** Enables the custom Bitmap-copy fallback for the duration of this installation. */
    internal fun install(): AutoCloseable {
        synchronized(lock) {
            check(activeInstallation == null) { "BitmapCopyTestSeam already installed" }
            return Installation().also { activeInstallation = it }
        }
    }

    internal fun isInstalled(): Boolean = synchronized(lock) { activeInstallation != null }

    internal fun isCustomCopyEnabled(): Boolean = isInstalled()

    internal fun installedForTestCount(): Int = synchronized(lock) {
        if (activeInstallation == null) 0 else 1
    }

    /**
     * Custom owner-bound copy using createBitmap + drawBitmap.
     * Returns a new Bitmap that is guaranteed to be a different object reference
     * and not share recycling state with [source].
     */
    internal fun copyOwned(source: Bitmap, config: Bitmap.Config, mutable: Boolean): Bitmap {
        check(!source.isRecycled) { "bitmap is recycled" }
        var intermediate: Bitmap? = null
        try {
            // The Boolean on this overload is hasAlpha, never mutability. Always
            // create a mutable intermediate, then make the immutable result through
            // the colors overload when mutable=false.
            val destination = Bitmap.createBitmap(source.width, source.height, config, true)
            intermediate = destination
            Canvas(destination).drawBitmap(source, 0f, 0f, null)
            destination.setHasAlpha(source.hasAlpha())
            destination.density = source.density
            if (mutable) return destination.also { intermediate = null }

            if (config != Bitmap.Config.ARGB_8888 && config != Bitmap.Config.RGB_565) {
                throw IllegalArgumentException(
                    "immutable Bitmap-copy seam does not support config=$config",
                )
            }
            val pixels = IntArray(source.width * source.height)
            destination.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val immutable = Bitmap.createBitmap(pixels, source.width, source.height, config)
            immutable.setHasAlpha(source.hasAlpha())
            immutable.density = source.density
            return immutable
        } finally {
            intermediate?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }
}
