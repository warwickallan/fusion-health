package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Coverage for the Fusion manual body log: line serialization round-trip (malformed lines skipped,
 * never crashing), ordering, latest/previous deltas, plausibility validation, formatting, and the
 * file store's save/reload/delete behaviour across "restarts" (fresh store instances on the same
 * file — the same guarantee that survives an app restart).
 */
class BodyLogTest {

    private fun m(type: BodyMetricType, cm: Double, at: String, id: String = "id-$cm-$at") =
        BodyMeasurement(id, type, cm, Instant.parse(at), Instant.parse(at))

    @Test
    fun `serialization round-trips exactly`() {
        val original = m(BodyMetricType.CHEST, 104.5, "2026-07-15T08:30:00Z", id = "abc-123")
        assertEquals(original, parseMeasurementLine(serializeMeasurement(original)))
    }

    @Test
    fun `a malformed line parses to null instead of crashing the log`() {
        assertNull(parseMeasurementLine("garbage"))
        assertNull(parseMeasurementLine("id|CHEST|not-a-number|0|0"))
        assertNull(parseMeasurementLine("id|SHOE_SIZE|10.0|0|0"))
    }

    @Test
    fun `latest and previous are ordered by measured time, per type`() {
        val all = listOf(
            m(BodyMetricType.WAIST, 90.0, "2026-07-01T08:00:00Z"),
            m(BodyMetricType.CHEST, 105.0, "2026-07-01T08:00:00Z"),
            m(BodyMetricType.CHEST, 104.0, "2026-07-15T08:00:00Z"),
        )
        val (latest, previous) = latestAndPrevious(all, BodyMetricType.CHEST)
        assertEquals(104.0, latest!!.valueCm, 0.0)
        assertEquals(105.0, previous!!.valueCm, 0.0)

        val (latestWaist, previousWaist) = latestAndPrevious(all, BodyMetricType.WAIST)
        assertEquals(90.0, latestWaist!!.valueCm, 0.0)
        assertNull(previousWaist)
    }

    @Test
    fun `delta is latest minus previous and null when either is missing`() {
        val prev = m(BodyMetricType.WAIST, 92.0, "2026-07-01T08:00:00Z")
        val latest = m(BodyMetricType.WAIST, 90.5, "2026-07-15T08:00:00Z")
        assertEquals(-1.5, deltaCm(latest, prev)!!, 0.0001)
        assertNull(deltaCm(latest, null))
    }

    @Test
    fun `plausibility bounds accept sensible circumferences and reject nonsense`() {
        assertTrue(isPlausibleCircumferenceCm(104.5))
        assertFalse(isPlausibleCircumferenceCm(0.0))
        assertFalse(isPlausibleCircumferenceCm(-90.0))
        assertFalse(isPlausibleCircumferenceCm(4000.0))
    }

    @Test
    fun `formatting shows latest, change since previous and recent history`() {
        val display = buildBodyLogDisplay(
            listOf(
                m(BodyMetricType.CHEST, 105.0, "2026-07-01T08:00:00Z"),
                m(BodyMetricType.CHEST, 104.0, "2026-07-15T08:00:00Z"),
                m(BodyMetricType.WAIST, 90.0, "2026-07-15T08:00:00Z"),
            )
        )
        val text = formatBodyLog(display) { "T" }
        assertTrue(text.contains("Chest: 104.0 cm"))
        assertTrue(text.contains("(-1.0 cm since previous)"))
        assertTrue(text.contains("Waist: 90.0 cm"))
        assertTrue(text.contains("Recent:"))
    }

    @Test
    fun `a valid chest with an invalid waist persists nothing and cannot duplicate on resubmit`() {
        val file = File.createTempFile("body-log-allornothing", ".txt")
        file.deleteOnExit()
        file.delete()
        val store = BodyLogStore(file)
        val at = Instant.parse("2026-07-15T08:30:00Z")
        var counter = 0
        val idFor: (BodyMetricType) -> String = { "${it.name}-${counter++}" }

        // First submit: chest valid, waist nonsense -> Invalid -> write nothing.
        val first = buildPendingMeasurements("114", "abc", at, at, idFor)
        assertTrue(first is PendingMeasurements.Invalid)
        if (first is PendingMeasurements.Valid) store.addAll(first.measurements)
        assertTrue(store.loadAll().isEmpty())

        // Corrected resubmit: both valid -> exactly two records, no duplicate chest.
        val second = buildPendingMeasurements("114", "87", at, at, idFor)
        assertTrue(second is PendingMeasurements.Valid)
        store.addAll((second as PendingMeasurements.Valid).measurements)

        val all = store.loadAll()
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.type == BodyMetricType.CHEST })
        assertEquals(1, all.count { it.type == BodyMetricType.WAIST })
    }

    @Test
    fun `builder allows saving one field alone and rejects an empty submit`() {
        val at = Instant.parse("2026-07-15T08:30:00Z")
        val idFor: (BodyMetricType) -> String = { it.name }

        val chestOnly = buildPendingMeasurements("104", "", at, at, idFor)
        assertTrue(chestOnly is PendingMeasurements.Valid)
        assertEquals(listOf(BodyMetricType.CHEST), (chestOnly as PendingMeasurements.Valid).measurements.map { it.type })

        val nothing = buildPendingMeasurements("", "  ", at, at, idFor)
        assertTrue(nothing is PendingMeasurements.Invalid)
    }

    @Test
    fun `store persists across instances and supports deletion`() {
        val file = File.createTempFile("body-log-test", ".txt")
        file.deleteOnExit()
        file.delete() // start with no file, like a fresh install

        val store = BodyLogStore(file)
        assertTrue(store.loadAll().isEmpty())

        val chest = m(BodyMetricType.CHEST, 104.5, "2026-07-15T08:30:00Z", id = "c1")
        val waist = m(BodyMetricType.WAIST, 90.5, "2026-07-15T08:31:00Z", id = "w1")
        store.add(chest)
        store.add(waist)

        // A fresh store on the same file = app closed and reopened.
        val reloaded = BodyLogStore(file).loadAll()
        assertEquals(setOf("c1", "w1"), reloaded.map { it.id }.toSet())
        assertEquals(104.5, reloaded.first { it.id == "c1" }.valueCm, 0.0)

        BodyLogStore(file).delete("c1")
        assertEquals(listOf("w1"), BodyLogStore(file).loadAll().map { it.id })
    }
}
