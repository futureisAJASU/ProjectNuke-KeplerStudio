package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.Job

/**
 * Owns the single registered history job visible to editor-action admission.
 *
 * A job is registered before it is started so the launch window between
 * coroutine creation and coordinator admission is still action-blocking.
 */
internal class HistoryActivityRegistry(
    private val coordinatorBusy: () -> Boolean,
    private val onChanged: () -> Unit = {},
) {
    class Registration internal constructor(internal val job: Job) {
        suspend fun await() {
            job.join()
        }
    }

    private val lock = Any()
    private var registered: Job? = null

    val job: Job?
        get() = synchronized(lock) { registered }

    fun isBusy(): Boolean {
        val registeredBusy = synchronized(lock) { registered?.let { !it.isCompleted } == true }
        return registeredBusy || coordinatorBusy()
    }

    fun register(job: Job): Boolean {
        return registerHandle(job) != null
    }

    fun registerHandle(job: Job): Registration? {
        val accepted = synchronized(lock) {
            val current = registered
            if (current != null && !current.isCompleted && current !== job) {
                false
            } else {
                registered = job
                true
            }
        }
        if (!accepted) return null
        job.invokeOnCompletion { clearIfOwned(job) }
        onChanged()
        return Registration(job)
    }

    fun clearIfOwned(job: Job) {
        val cleared = synchronized(lock) {
            if (registered === job) {
                registered = null
                true
            } else false
        }
        if (cleared) onChanged()
    }

    fun cancel() {
        val current = synchronized(lock) {
            val value = registered
            registered = null
            value
        }
        current?.cancel()
        if (current != null) onChanged()
    }

    fun clearCompleted() {
        val cleared = synchronized(lock) {
            if (registered?.isCompleted == true) {
                registered = null
                true
            } else false
        }
        if (cleared) onChanged()
    }
}
