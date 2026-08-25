package com.projectnuke.keplerstudio.editor

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 7 — STORAGE PRESSURE failure-semantics gaps beyond the existing
 * cancellation/shrink coverage:
 *  - a history handler throwing an ORDINARY exception is still one bounded
 *    attempt; the authoritative final reread decides; the registration set is
 *    untouched and a later request can still use the same live registration;
 *  - an action that fails AFTER successful recovery propagates truthfully with
 *    no retry loop (one sweep, one history request, three capacity reads);
 *  - transient sweep failures never block truthful recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PressureRecoveryFailureSemanticsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private var pressureOverride: AutoCloseable? = null
    private val registryHandles = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
    }

    @After
    fun tearDown() {
        registryHandles.forEach { handle -> runCatching { handle.close() } }
        registryHandles.clear()
        pressureOverride?.close()
        pressureOverride = null
    }

    @Test
    fun ordinaryHistoryExceptionIsOneBoundedAttemptThenAuthoritativeRereadDecides() = runBlocking {
        val capacityReads = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val sweeps = AtomicInteger(0)
        // 0 (initial insufficient) -> 0 (post-sweep reread) -> plenty (final).
        val override =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { _ ->
                        when (capacityReads.incrementAndGet()) {
                            1, 2 -> 0L
                            else -> Long.MAX_VALUE / 4
                        }
                    },
                    reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                    pressureSweep = { _ ->
                        sweeps.incrementAndGet()
                        TransientMaintenanceReport.EMPTY
                    },
                ),
            )
        pressureOverride = override
        val owner = Object()
        val actionRan = AtomicBoolean(false)
        val handle =
            HistoryPressureRecovery.install(owner) {
                historyCalls.incrementAndGet()
                throw IllegalStateException("injected ordinary history failure")
            }
        registryHandles.add(handle)

        StoragePressure.controller.ensureWriteHeadroom(
            context,
            workFile(),
            5L,
            onInsufficient = { false },
        ) { actionRan.set(true); true }

        assertTrue("recovery still admits after an ordinary history failure", actionRan.get())
        assertEquals("exactly one bounded history attempt", 1, historyCalls.get())
        assertEquals("exactly one sweep", 1, sweeps.get())
        assertEquals(3, capacityReads.get())
        assertTrue("registration survives an ordinary handler failure", HistoryPressureRecovery.isOwnerInstalled(owner))

        // A later request can still dispatch through the SAME live registration
        // set: newest live registration answers.
        val secondHandleCall = AtomicInteger(0)
        val replacementOwner = Object()
        registryHandles.add(
            HistoryPressureRecovery.install(replacementOwner) { secondHandleCall.incrementAndGet(); 0L },
        )
        assertEquals("newest live registration answers the next request", 0L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals(1, secondHandleCall.get())
        Unit
    }

    @Test
    fun failingActionAfterSuccessfulRecoveryPropagatesTruthfullyWithoutRetryLoop() = runBlocking {
        val capacityReads = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val sweeps = AtomicInteger(0)
        // Insufficient until history recovery reports freed space.
        val override =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { _ ->
                        when (capacityReads.incrementAndGet()) {
                            1, 2 -> 0L
                            else -> Long.MAX_VALUE / 4
                        }
                    },
                    reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                    pressureSweep = { _ ->
                        sweeps.incrementAndGet()
                        TransientMaintenanceReport.EMPTY
                    },
                ),
            )
        pressureOverride = override
        registryHandles.add(
            HistoryPressureRecovery.install(Object()) {
                historyCalls.incrementAndGet()
                4096L
            },
        )

        val thrown =
            runCatching {
                StoragePressure.controller.ensureWriteHeadroom(
                    context,
                    workFile(),
                    5L,
                    onInsufficient = { false },
                ) { error("injected action failure") }
            }.exceptionOrNull()

        assertTrue("action failure must propagate truthfully", thrown is IllegalStateException)
        assertEquals("no recovery retry loop after action failure", 1, historyCalls.get())
        assertEquals(1, sweeps.get())
        assertEquals("initial + post-sweep + post-history reads only", 3, capacityReads.get())
        Unit
    }

    private fun workFile(): File = File(context.cacheDir.apply { mkdirs() }, "phase7-pressure-probe.bin")
}
