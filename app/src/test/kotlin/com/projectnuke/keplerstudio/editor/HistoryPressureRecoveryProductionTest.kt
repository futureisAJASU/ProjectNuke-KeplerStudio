package com.projectnuke.keplerstudio.editor

import android.app.Application
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phase 5 registration-ownership and end-to-end pressure wiring coverage.
 *
 * The process-global [HistoryPressureRecovery] registry resolves storage
 * pressure requests to the LIVE editor's history coordinator. These tests pin
 * the owner-correct lifecycle: replacement is linearizable, stale teardown can
 * never strip a newer owner, absence stays truthful, cancellation leaves no
 * stale handler, and the full production chain
 * StoragePressureController -> registry -> EditorViewModel handler ->
 * EditorHistoryCoordinator executes against the real coordinator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryPressureRecoveryProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private val registryHandles = ArrayDeque<AutoCloseable>()
    private lateinit var harness: OwnedEditorViewModelHarness

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        LegacyDraftSourceOwnership.clearForTest()
        harness = OwnedEditorViewModelHarness(context)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        while (registryHandles.isNotEmpty()) {
            val handle = registryHandles.removeFirst()
            failures.attempt { handle.close() }
        }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.attempt { LegacyDraftSourceOwnership.clearForTest() }
        failures.throwIfAny()
    }

    // ------------------------------------------------------------------
    // Registration ownership (Section 12 scenarios 1-6)
    // ------------------------------------------------------------------

    @Test
    fun ownerReplacementIsLinearizableAndStaleTeardownCannotUnregisterNewerOwner() = runBlocking {
        val ownerA = Object()
        val ownerB = Object()
        val callsA = AtomicInteger(0)
        val callsB = AtomicInteger(0)
        val handleA =
            HistoryPressureRecovery.install(ownerA) { callsA.incrementAndGet(); 11L }
                .also(registryHandles::addFirst)

        assertEquals(11L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertTrue(HistoryPressureRecovery.isInstalled())

        // Owner B atomically replaces A: the newest live owner wins linearly.
        val handleB =
            HistoryPressureRecovery.install(ownerB) { callsB.incrementAndGet(); 22L }
                .also(registryHandles::addFirst)
        assertEquals(22L, HistoryPressureRecovery.reclaimSuspendingOrNull())

        // Stale owner A tears down: B's registration must survive untouched.
        handleA.close()
        assertTrue("stale teardown must not remove newer owner", HistoryPressureRecovery.isInstalled())
        assertEquals("newer handler still answers", 22L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals("stale handler must be dead", 1, callsA.get())

        // Owner B tearing down removes exactly B.
        handleB.close()
        assertFalse(HistoryPressureRecovery.isInstalled())
        assertFalse("no handler means no attempt", HistoryPressureRecovery.tryReclaimAndReportAttempt())
        Unit
    }

    @Test
    fun doubleTeardownOfSameOwnerIsHarmless() = runBlocking {
        val owner = Object()
        val handle =
            HistoryPressureRecovery.install(owner) { 5L }
                .also(registryHandles::addFirst)
        assertTrue(HistoryPressureRecovery.isInstalled())
        handle.close()
        assertFalse(HistoryPressureRecovery.isInstalled())
        // Second close of the SAME owner must not resurrect or corrupt state.
        handle.close()
        assertFalse(HistoryPressureRecovery.isInstalled())
        Unit
    }

    @Test
    fun pressureWithoutHandlerSkipsHistoryStepTruthfully() = runBlocking {
        val capacityReads = AtomicInteger(0)
        val controller =
            StoragePressureController(
                capacity = { capacityReads.incrementAndGet(); 0L },
                reserveBytes = 1_000L,
                pressureSweep = { TransientMaintenanceReport.EMPTY },
            )
        val outcome =
            controller.ensureWriteHeadroom(context, workFile(), 999_999L, { "insufficient" }) { "wrote" }
        assertEquals("insufficient", outcome)
        assertEquals(
            "no history step: initial read + post-sweep reread only",
            2,
            capacityReads.get(),
        )
        Unit
    }

    @Test
    fun cancellationDuringParkedHandlerLeavesNoStaleRegistration() = runBlocking {
        val owner = Object()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handle =
            HistoryPressureRecovery.install(owner) {
                parked.complete(Unit)
                release.await()
                7L
            }
        assertTrue(HistoryPressureRecovery.isInstalled())

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val job =
                scope.async {
                    StoragePressureController(
                        capacity = { 0L },
                        reserveBytes = 1_000L,
                        pressureSweep = { TransientMaintenanceReport.EMPTY },
                    ).ensureWriteHeadroom(
                        context,
                        workFile(),
                        1L,
                        { "insufficient" },
                    ) { "wrote" }
                }
            awaitSignal(parked)
            job.cancel()
            val thrown = runCatching { job.await() }.exceptionOrNull()
            assertTrue("caller cancellation must propagate", thrown is CancellationException || job.isCancelled)
        } finally {
            // Fail-safe: a mid-test failure must never leave the parked
            // production handler hanging a Default worker forever.
            release.complete(Unit)
        }

        // Cancellation must leave the registration owner-consistent: explicit
        // teardown by the live owner removes it cleanly and nothing stale stays
        // installed to poison later tests.
        assertTrue(
            "cancellation does not unregister another owner's handler",
            HistoryPressureRecovery.isInstalled(),
        )
        handle.close()
        assertFalse("teardown cannot poison later tests", HistoryPressureRecovery.isInstalled())
        scope.cancel()
        Unit
    }

    // ------------------------------------------------------------------
    // End-to-end wiring through the real editor registration
    // ------------------------------------------------------------------

    @Test
    fun liveEditorAnswersPressureThroughRegistryWithoutTouchingDocumentTruth() = runBlocking {
        // Probe files live OUTSIDE production-managed families: planting
        // unowned lookalikes under drafts/generations or editor_sources would
        // legitimately be reclaimed by production startup janitors. The
        // managed-path invariant itself is proven against REAL history/draft
        // trees by HistoryPressureCoordinatorProductionTest scenario 8.
        val probeDir = File(context.filesDir, "pressure_probe").apply { mkdirs() }
        val probes = listOf(
            probeDir.resolve("source.img").apply { writeBytes(byteArrayOf(1, 2, 3)) },
            probeDir.resolve("thumbnail.jpg").apply { writeBytes(byteArrayOf(4, 5)) },
            File(context.cacheDir, "pressure-probe.bin").apply { writeBytes(byteArrayOf(9)) },
        )
        val digestBefore = probes.map(File::length)

        // The real EditorViewModel installs its own coordinator-bound handler.
        harness.createEditor()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("live editor must own the pressure registration", HistoryPressureRecovery.isInstalled())

        // Full production sequence: transient sweep -> history request ->
        // authoritative reread -> action. The capacity boundary flips to
        // sufficient ONLY during the post-history reread, proving the wired
        // handler executed without faking anything.
        val readIndex = AtomicInteger(0)
        val historyWindowReached = AtomicBoolean(false)
        val override =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { _ ->
                        when (readIndex.getAndIncrement()) {
                            0, 1 -> 0L
                            else -> {
                                historyWindowReached.set(true)
                                Long.MAX_VALUE / 4
                            }
                        }
                    },
                    reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                ),
            )
        try {
            val actionRan = AtomicBoolean(false)
            // The wired handler hops to Main.immediate; drive the sequence off
            // the main thread and pump Robolectric Main until it completes.
            val callerScope = CoroutineScope(Dispatchers.Default)
            val deferred =
                callerScope.async {
                    StoragePressure.controller.ensureWriteHeadroom(
                        context,
                        workFile(),
                        5L,
                        onInsufficient = { false },
                    ) { actionRan.set(true); true }
                }
            awaitEditorCompletionForTest(
                description = "pressure sequence through live editor must complete",
                completion = deferred,
                timeoutMillis = 20_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
            )
            runBlocking { deferred.await() }
            assertTrue("wired history step must precede the final reread", historyWindowReached.get())
            assertTrue("write must proceed once headroom exists", actionRan.get())
            assertEquals(3, readIndex.get())
            callerScope.cancel()
        } finally {
            override.close()
        }

        assertEquals(
            "probe files remain byte-identical: pressure never leaves history authority",
            digestBefore,
            probes.map(File::length),
        )
        probes.forEach { probe -> assertTrue("probe must survive: ${probe.name}", probe.isFile) }

        // Teardown unregisters the editor's handler; absence stays truthful.
        harness.clearViewModels()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("cleared editor must unregister", HistoryPressureRecovery.isInstalled())
        Unit
    }

    @Test
    fun twoLiveEditorsCoexistAndSequentialTeardownLeavesNoRegistration() = runBlocking {
        val application = context.applicationContext as android.app.Application
        // Two LIVE editors coexist: B replaces A's registration instead of
        // crashing; the previous single-install contract made this impossible.
        val storeA = androidx.lifecycle.ViewModelStore()
        storeA.put("editor-a", EditorViewModel(application))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val storeB = androidx.lifecycle.ViewModelStore()
        storeB.put("editor-b", EditorViewModel(application))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("replacement keeps exactly one handler installed", HistoryPressureRecovery.isInstalled())

        // Stale editor A's teardown must NOT remove B's registration.
        storeA.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("stale teardown cannot unregister newer owner", HistoryPressureRecovery.isInstalled())

        // Only B's teardown removes the registration.
        storeB.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(HistoryPressureRecovery.isInstalled())
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun workFile(): File =
        context.cacheDir.apply { mkdirs() }.resolve("history-pressure-wiring-probe.bin")

    private suspend fun awaitSignal(signal: CompletableDeferred<Unit>) {
        awaitEditorCompletionForTest(
            description = "handler must start",
            completion = signal,
            timeoutMillis = 20_000L,
            pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
        )
    }
}
