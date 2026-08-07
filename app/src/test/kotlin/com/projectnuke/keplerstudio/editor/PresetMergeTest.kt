package com.projectnuke.keplerstudio.editor

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests the real production [mergePresets] settlement through its public entry point. */
@RunWith(RobolectricTestRunner::class)
class PresetMergeTest {

    private fun preset(name: String, ts: Long): Preset =
        Preset(id = "id-$name", name = name, params = EditParams(), timestampMillis = ts, look = null)

    private fun namesOf(presets: List<Preset>): List<String> = presets.map { it.name }

    @Test
    fun noDuplicatesKeepsAllUniqueEntries() {
        val current = listOf(preset("A", 100L), preset("B", 200L))
        val merged = mergePresets(current, emptyList())
        assertEquals(listOf("B", "A"), namesOf(merged.presets))
        assertEquals(2, merged.retainedCount)
        assertEquals(0, merged.importedCount)
        assertEquals(0, merged.replacedCount)
    }

    @Test
    fun caseInsensitiveSameNameReplacement() {
        val current = listOf(preset("Sunrise", 100L))
        val incoming = listOf(preset("SUNRISE", 999L))
        val merged = mergePresets(current, incoming)
        // The incoming (newer) same-name preset replaces the current one.
        assertEquals(listOf("SUNRISE"), namesOf(merged.presets))
        assertEquals(1, merged.retainedCount)
        assertEquals(1, merged.importedCount)
        assertEquals(1, merged.replacedCount)
        assertEquals(999L, merged.presets.single().timestampMillis)
    }

    @Test
    fun deterministicTimestampOrdering() {
        val incoming = listOf(preset("C", 30L), preset("A", 10L), preset("B", 20L))
        val merged = mergePresets(emptyList(), incoming)
        assertEquals(listOf("C", "B", "A"), namesOf(merged.presets))
    }

    @Test
    fun exactlyFortyRetainedWithoutTruncation() {
        val current = (1..40).map { preset("P%03d".format(it), it.toLong()) }
        val merged = mergePresets(current, emptyList())
        assertEquals(40, merged.retainedCount)
        assertEquals(40, merged.presets.size)
    }

    @Test
    fun moreThanFortyEntriesRetainsExactlyForty() {
        val incoming = (1..45).map { preset("N%02d".format(it), it.toLong()) }
        val merged = mergePresets(emptyList(), incoming)
        assertEquals(40, merged.retainedCount)
        assertEquals(40, merged.presets.size)
        assertTrue(merged.importedCount <= 40)
        assertEquals(40, merged.importedCount)
    }

    @Test
    fun incomingReplacementNearRetentionBoundary() {
        // 39 current entries plus an incoming that replaces one and an incoming that is new
        // land the merged list exactly at the 40-entry limit.
        val current = (1..39).map { preset("K%02d".format(it), it.toLong()) }
        val incoming = listOf(preset("K05", 5_000L), preset("NewOne", 40_000L))
        val merged = mergePresets(current, incoming)
        assertEquals(40, merged.retainedCount)
        assertEquals(2, merged.importedCount)
        assertEquals(1, merged.replacedCount)
        assertEquals(40_000L, merged.presets.first().timestampMillis)
    }

    @Test
    fun callerInputListsRemainUnchanged() {
        val current = mutableListOf(preset("A", 100L))
        val incoming = mutableListOf(preset("B", 200L), preset("C", 300L))
        mergePresets(current, incoming)
        assertEquals(listOf("A"), namesOf(current))
        assertEquals(listOf("B", "C"), namesOf(incoming))
    }

    @Test
    fun reportedCountsReflectActualRetainedList() {
        // Incoming has 45 distinct entries but only the newest 40 are retained.
        val incoming = (1..45).map { preset("R%02d".format(it), it.toLong()) }
        val merged = mergePresets(emptyList(), incoming)
        assertEquals(40, merged.presets.size)
        assertEquals(40, merged.retainedCount)
        // 5 incoming entries were dropped by the retention rule, so importedCount is 40, not 45.
        assertEquals(40, merged.importedCount)
    }
}
