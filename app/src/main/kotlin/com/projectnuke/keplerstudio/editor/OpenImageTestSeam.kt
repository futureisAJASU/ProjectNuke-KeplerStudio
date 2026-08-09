package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * Owner-bound seams for the external image-open boundaries. The ViewModel
 * captures one instance before launching the operation; it never re-captures
 * a later test installation while the operation is in flight.
 */
internal class OpenImageTestSeam(
    internal val sourceTransactionFactory:
        ((Context, Uri) -> IncomingSourceTransaction)? = null,
    internal val decode: (suspend (String) -> Bitmap)? = null,
    internal val nativeSessionFactory: ((String) -> Long)? = null,
    internal val nativeSessionReleaser: ((Long) -> Unit)? = null,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: OpenImageTestSeam? = null

        internal fun install(seam: OpenImageTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "open-image test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): OpenImageTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
