package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generic prepared-owner contract coverage.
 *
 * Caller-specific production integration is intentionally kept in the owning controller tests;
 * this fixture must not be counted as production caller coverage.
 */
class PreparedOperationOwnershipContractTest {
    private val operationNames =
        listOf(
            "parameterRender",
            "autoEnhance",
            "engineChange",
            "resetAdjustments",
            "presetApplication",
            "cropApply",
            "ruleFlare",
            "modelFlareGuard",
            "maskAwareRemaster",
            "subjectSelection",
            "activeSelectionLocalEdit",
            "selectionLivePreview",
            "nativeSelectionBake",
            "nativeSpecialEffects",
            "autoStraighten",
            "dirtyExport",
        )

    @Test
    fun everyCaller_childNeverStarts_settlesAllPreparedOwners() {
        operationNames.forEach { name ->
            val fixture = Fixture(name)
            assertTrue(fixture.handoff.settleCallerOwned())
            fixture.assertAllSettled()
        }
    }

    @Test
    fun everyCaller_failureCancellationStaleAndReplacement_settleChildOwners() {
        operationNames.forEach { name ->
            listOf("failure", "cancellation", "stale", "replacement", "shutdown").forEach {
                val fixture = Fixture("$name:$it")
                assertTrue(fixture.handoff.claimForChild())
                assertTrue(fixture.handoff.settleChildOwned())
                fixture.assertAllSettled()
            }
        }
    }

    @Test
    fun everyCaller_successDisarmsUiAndHistoryTransfers() {
        operationNames.forEach { name ->
            val fixture = Fixture(name)
            assertTrue(fixture.handoff.claimForChild())
            fixture.bitmap.disarm()
            fixture.snapshot.disarm()
            assertTrue(fixture.handoff.settleChildOwned())

            assertEquals(0, fixture.bitmap.releases.get())
            assertEquals(0, fixture.snapshot.releases.get())
            assertEquals(1, fixture.tracker.releases.get())
            assertEquals(1, fixture.edge.releases.get())
            assertFalse(fixture.busy.value)
        }
    }

    private class Fixture(name: String) {
        val bitmap = FakeOwner()
        val snapshot = FakeOwner()
        val tracker = FakeOwner()
        val edge = FakeOwner()
        val busy = FakeBusy()
        val handoff =
            PreparedResourceHandoff.create(
                name,
                bitmap::release,
                snapshot::release,
                tracker::release,
                edge::release,
                busy::clear,
            )

        fun assertAllSettled() {
            assertEquals(1, bitmap.releases.get())
            assertEquals(1, snapshot.releases.get())
            assertEquals(1, tracker.releases.get())
            assertEquals(1, edge.releases.get())
            assertFalse(busy.value)
        }
    }

    private class FakeOwner {
        val releases = AtomicInteger()
        private var armed = true

        fun disarm() {
            armed = false
        }

        fun release() {
            if (armed) {
                armed = false
                releases.incrementAndGet()
            }
        }
    }

    private class FakeBusy {
        var value = true
        fun clear() {
            value = false
        }
    }
}
