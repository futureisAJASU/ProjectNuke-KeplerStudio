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
 * pressure requests to a LIVE editor's history coordinator. Every live editor
 * owns its own token-keyed registration and dispatch goes to the MOST-RECENT
 * live registration, with deterministic fallback to older live owners after
 * newer teardown. These tests pin that ownership model: replacement is
 * linearizable, stale teardown (any owner, any handle) can never strip another
 * live registration, out-of-order close keeps the survivor answerable,
 * absence stays truthful, cancellation leaves the registration set untouched,
 * and the full production chain StoragePressureController -> registry ->
 * EditorViewModel handler -> EditorHistoryCoordinator executes against real
 * coordinators.
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
    fun newerOwnerClosingFirstRestoresOlderLiveOwnerEligibility() = runBlocking {
        val ownerA = Object()
        val ownerB = Object()
        val callsA = AtomicInteger(0)
        val handleA =
            HistoryPressureRecovery.install(ownerA) { callsA.incrementAndGet(); 11L }
                .also(registryHandles::addFirst)
        val handleB =
            HistoryPressureRecovery.install(ownerB) { 22L }
                .also(registryHandles::addFirst)
        assertEquals(2, HistoryPressureRecovery.liveRegistrationCountForTest())

        // B closes FIRST: A must remain registered and answer pressure.
        handleB.close()
        assertTrue("older live owner survives newer teardown", HistoryPressureRecovery.isInstalled())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerA))
        assertEquals(11L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals(1, callsA.get())
        Unit
    }

    @Test
    fun olderOwnerClosingFirstKeepsNewerLiveOwnerRegistered() = runBlocking {
        val ownerA = Object()
        val ownerB = Object()
        val callsB = AtomicInteger(0)
        val handleA =
            HistoryPressureRecovery.install(ownerA) { 11L }
                .also(registryHandles::addFirst)
        val handleB =
            HistoryPressureRecovery.install(ownerB) { callsB.incrementAndGet(); 22L }
                .also(registryHandles::addFirst)

        // A closes FIRST: B must remain registered and answer pressure.
        handleA.close()
        assertTrue("newer live owner survives older teardown", HistoryPressureRecovery.isInstalled())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerB))
        assertEquals(22L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals(1, callsB.get())
        Unit
    }

    @Test
    fun sameOwnerReinstallMakesStaleHandleToothless() = runBlocking {
        val owner = Object()
        val callsH2 = AtomicInteger(0)
        val handleH1 =
            HistoryPressureRecovery.install(owner) { 111L }
                .also(registryHandles::addFirst)
        val handleH2 =
            HistoryPressureRecovery.install(owner) { callsH2.incrementAndGet(); 222L }
                .also(registryHandles::addFirst)

        // Same-owner reinstall replaced exactly H1's registration.
        assertEquals(1, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(owner))

        // Stale H1 teardown must NOT remove H2.
        handleH1.close()
        assertTrue(
            "stale same-owner close cannot remove the newer registration",
            HistoryPressureRecovery.isInstalled(),
        )
        assertEquals(1, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertEquals(222L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals("live H2 handler answered", 1, callsH2.get())

        // Only the live handle removes the registration.
        handleH2.close()
        assertFalse(HistoryPressureRecovery.isInstalled())
        Unit
    }

    @Test
    fun multipleLiveOwnersDispatchNewestThenFallBackToOlderOnTeardown() = runBlocking {
        val ownerA = Object()
        val ownerB = Object()
        val ownerC = Object()
        val callsA = AtomicInteger(0)
        val callsB = AtomicInteger(0)
        val callsC = AtomicInteger(0)
        val handleA =
            HistoryPressureRecovery.install(ownerA) { callsA.incrementAndGet(); 11L }
                .also(registryHandles::addFirst)
        val handleB =
            HistoryPressureRecovery.install(ownerB) { callsB.incrementAndGet(); 22L }
                .also(registryHandles::addFirst)
        val handleC =
            HistoryPressureRecovery.install(ownerC) { callsC.incrementAndGet(); 33L }
                .also(registryHandles::addFirst)

        // Documented policy A: ONE request consults exactly the MOST-RECENT
        // live registration.
        assertEquals(3, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerA))
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerB))
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerC))
        assertEquals("newest live registration answers", 33L, HistoryPressureRecovery.reclaimSuspendingOrNull())

        // Newest closes -> deterministic fallback to the next-oldest live
        // registration on the NEXT request (never an intra-request loop).
        handleC.close()
        assertEquals(2, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertEquals("older live owner becomes eligible again", 22L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        handleB.close()
        assertEquals(1, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertEquals("oldest live owner answers last", 11L, HistoryPressureRecovery.reclaimSuspendingOrNull())
        assertEquals(1, callsA.get())
        assertEquals(1, callsB.get())
        assertEquals(1, callsC.get())

        // All owners closed -> registry empty and truthfully unavailable.
        handleA.close()
        assertEquals(0, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertFalse(HistoryPressureRecovery.isInstalled())
        assertFalse("empty registry must skip the history step", HistoryPressureRecovery.tryReclaimAndReportAttempt())
        Unit
    }

    @Test
    fun callerCancellationLeavesEveryLiveRegistrationUntouched() = runBlocking {
        val ownerA = Object()
        val ownerB = Object()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handleA =
            HistoryPressureRecovery.install(ownerA) { 11L }
                .also(registryHandles::addFirst)
        val handleB =
            HistoryPressureRecovery.install(ownerB) {
                parked.complete(Unit)
                release.await()
                22L
            }
            .also(registryHandles::addFirst)
        assertEquals(2, HistoryPressureRecovery.liveRegistrationCountForTest())

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
            release.complete(Unit)
            scope.cancel()
        }

        // Cancellation mutates NOTHING: only real owner teardown does.
        assertEquals(
            "cancellation must leave every live registration installed",
            2,
            HistoryPressureRecovery.liveRegistrationCountForTest(),
        )
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerA))
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(ownerB))
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
        // Two LIVE editors coexist: each owns its own registration; neither
        // replaces nor strips the other's.
        val storeA = androidx.lifecycle.ViewModelStore()
        val editorA = EditorViewModel(application)
        storeA.put("editor-a", editorA)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val storeB = androidx.lifecycle.ViewModelStore()
        val editorB = EditorViewModel(application)
        storeB.put("editor-b", editorB)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("both live editors are simultaneously represented", HistoryPressureRecovery.isInstalled())
        assertEquals(2, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(editorA))
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(editorB))

        // Editor A's teardown removes exactly A; B stays registered.
        storeA.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertFalse(HistoryPressureRecovery.isOwnerInstalled(editorA))
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(editorB))

        // Only B's teardown empties the registry.
        storeB.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, HistoryPressureRecovery.liveRegistrationCountForTest())
        assertFalse(HistoryPressureRecovery.isInstalled())
        Unit
    }

    @Test
    fun newestEditorTeardownRestoresOlderEditorAsPressureDispatchTarget() = runBlocking {
        val application = context.applicationContext as android.app.Application
        val storeA = androidx.lifecycle.ViewModelStore()
        val editorA = EditorViewModel(application)
        storeA.put("editor-a", editorA)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val storeB = androidx.lifecycle.ViewModelStore()
        val editorB = EditorViewModel(application)
        storeB.put("editor-b", editorB)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, HistoryPressureRecovery.liveRegistrationCountForTest())

        // The NEWEST live editor tears down first; the older still-live editor
        // becomes the dispatch target again.
        storeB.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(HistoryPressureRecovery.isOwnerInstalled(editorB))
        assertTrue("older editor regains eligibility", HistoryPressureRecovery.isInstalled())
        assertTrue(HistoryPressureRecovery.isOwnerInstalled(editorA))

        // The surviving older editor answers a full production pressure request:
        // transient sweep -> history step -> authoritative reread -> action.
        val readIndex = AtomicInteger(0)
        val override =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { _ ->
                        if (readIndex.getAndIncrement() < 2) 0L else Long.MAX_VALUE / 4
                    },
                    reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                ),
            )
        try {
            val callerScope = CoroutineScope(Dispatchers.Default)
            val actionRan = AtomicBoolean(false)
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
                description = "older live editor must answer pressure after newest teardown",
                completion = deferred,
                timeoutMillis = 20_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
            )
            runBlocking { deferred.await() }
            assertTrue("write proceeded through the older editor's coordinator", actionRan.get())
            assertEquals(3, readIndex.get())
            callerScope.cancel()
        } finally {
            override.close()
        }

        storeA.clear()
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
