package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.os.PowerManager

/**
 * Small seam-owned wakelock wrapper for N5 physical stress tests.
 *
 * Requirements:
 * - PARTIAL_WAKE_LOCK only
 * - do NOT use FLAG_KEEP_SCREEN_ON, FULL_WAKE_LOCK, SCREEN_DIM_WAKE_LOCK
 * - acquire immediately before bounded physical workload, release in finally path
 * - release survives success, assertion failure, native failure, cancellation, stale, throw
 */
internal interface N5WakeLock {
    fun acquire()
    fun release()
    val isHeld: Boolean
}

internal class RealN5WakeLock(
    context: Context,
    private val tag: String = "KeplerN5Stress",
) : N5WakeLock {
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
        }
    }

    override fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    override fun release() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    override val isHeld: Boolean get() = wakeLock.isHeld
}

/**
 * Fake wake-lock for unit tests: records acquisition/release lifecycle.
 */
internal class FakeN5WakeLock : N5WakeLock {
    var acquireCalls = 0
    var releaseCalls = 0
    private var held = false

    override fun acquire() {
        acquireCalls++
        held = true
    }

    override fun release() {
        if (held) {
            releaseCalls++
            held = false
        }
    }

    override val isHeld: Boolean get() = held
}
