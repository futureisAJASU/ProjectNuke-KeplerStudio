package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/** Scoped decode boundary used only to make reset ownership tests deterministic. */
internal class ResetAdjustmentsTestSeam(
    internal val decode: suspend (String) -> Bitmap,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: ResetAdjustmentsTestSeam? = null

        internal fun install(seam: ResetAdjustmentsTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "reset decode test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): ResetAdjustmentsTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
