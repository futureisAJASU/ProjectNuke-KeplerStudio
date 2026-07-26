package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal class ManagedEditLaunchController(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var operationToken = 0L
    private var operationJob: Job? = null

    val token: Long
        get() = synchronized(lock) { operationToken }

    val job: Job?
        get() = synchronized(lock) { operationJob }

    fun launch(
        handoff: PreparedResourceHandoff?,
        block: suspend (Long) -> Unit,
    ): Job =
        synchronized(lock) {
            operationJob?.cancel()
            val launchedToken = ++operationToken
            val launchedJob =
                scope.launch {
                    val self = currentCoroutineContext()[Job]
                    val claimed = handoff?.claimForChild() ?: true
                    try {
                        if (claimed) block(launchedToken)
                    } finally {
                        if (claimed) handoff?.settleChildOwned()
                        clearIfCurrent(launchedToken, self)
                    }
                }
            operationJob = launchedJob
            launchedJob.invokeOnCompletion {
                handoff?.settleCallerOwned()
                clearIfCurrent(launchedToken, launchedJob)
            }
            clearIfCompleted(launchedToken, launchedJob)
            launchedJob
        }

    fun invalidate() {
        synchronized(lock) {
            ++operationToken
            operationJob?.cancel()
            operationJob = null
        }
    }

    fun isCurrent(candidate: Long): Boolean = synchronized(lock) { operationToken == candidate }

    fun clearCompleted() {
        synchronized(lock) {
            operationJob = operationJob?.takeIf { !it.isCompleted }
        }
    }

    private fun clearIfCompleted(token: Long, job: Job) {
        if (job.isCompleted) clearIfCurrent(token, job)
    }

    private fun clearIfCurrent(token: Long, job: Job?) {
        synchronized(lock) {
            if (operationToken == token && operationJob === job) operationJob = null
        }
    }
}
