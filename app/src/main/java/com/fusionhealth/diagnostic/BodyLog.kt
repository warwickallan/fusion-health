package com.fusionhealth.diagnostic

import java.io.File
import java.time.Instant

/**
 * BUILD-005/WP2 — Fusion-owned manual body-measurement log. Chest and waist circumference in
 * centimetres, entered by Warwick in the app. This is the ONLY durably-stored health data in
 * Fusion247 Health: imported Samsung/Withings/MyFitnessPal records remain read-only and in memory.
 * Storage is a small app-private line file (no framework, no Health Connect write permission, no
 * cloud) with an atomic replace on every write. allowBackup=false is preserved at the app level.
 *
 * Pure serialization/ordering/delta logic lives here; [BodyLogStore] is the thin file layer.
 */

internal enum class BodyMetricType { CHEST, WAIST }

internal data class BodyMeasurement(
    val id: String,
    val type: BodyMetricType,
    val valueCm: Double,
    val measuredAt: Instant,
    val createdAt: Instant,
)

/** One line per record: id|type|valueCm|measuredAtEpochMs|createdAtEpochMs */
internal fun serializeMeasurement(m: BodyMeasurement): String =
    listOf(m.id, m.type.name, m.valueCm.toString(), m.measuredAt.toEpochMilli(), m.createdAt.toEpochMilli())
        .joinToString("|")

/** Parses one stored line; null (skipped, never crashes the log) if the line is malformed. */
internal fun parseMeasurementLine(line: String): BodyMeasurement? {
    val parts = line.split("|")
    if (parts.size != 5) return null
    return try {
        BodyMeasurement(
            id = parts[0],
            type = BodyMetricType.valueOf(parts[1]),
            valueCm = parts[2].toDouble(),
            measuredAt = Instant.ofEpochMilli(parts[3].toLong()),
            createdAt = Instant.ofEpochMilli(parts[4].toLong()),
        )
    } catch (e: Exception) {
        null
    }
}

/** Sensible bounds for a circumference in cm — validation only, no fitness judgement. */
internal fun isPlausibleCircumferenceCm(value: Double): Boolean = value > 10.0 && value < 400.0

/** Latest and previous measurement of one type, by measured time (newest first). */
internal fun latestAndPrevious(
    all: List<BodyMeasurement>,
    type: BodyMetricType,
): Pair<BodyMeasurement?, BodyMeasurement?> {
    val ofType = all.filter { it.type == type }.sortedByDescending { it.measuredAt }
    return ofType.getOrNull(0) to ofType.getOrNull(1)
}

/** Change from previous to latest in cm, or null when either is missing. */
internal fun deltaCm(latest: BodyMeasurement?, previous: BodyMeasurement?): Double? =
    if (latest != null && previous != null) latest.valueCm - previous.valueCm else null

/** What the Body Measurements section displays. */
internal data class BodyLogDisplay(
    val latestChest: BodyMeasurement?,
    val previousChest: BodyMeasurement?,
    val latestWaist: BodyMeasurement?,
    val previousWaist: BodyMeasurement?,
    val recent: List<BodyMeasurement>,
)

internal fun buildBodyLogDisplay(all: List<BodyMeasurement>, recentLimit: Int = 6): BodyLogDisplay {
    val (latestChest, prevChest) = latestAndPrevious(all, BodyMetricType.CHEST)
    val (latestWaist, prevWaist) = latestAndPrevious(all, BodyMetricType.WAIST)
    return BodyLogDisplay(
        latestChest = latestChest,
        previousChest = prevChest,
        latestWaist = latestWaist,
        previousWaist = prevWaist,
        recent = all.sortedByDescending { it.measuredAt }.take(recentLimit),
    )
}

private fun cm(v: Double): String = String.format(java.util.Locale.US, "%.1f cm", v)

private fun deltaText(delta: Double?): String = when {
    delta == null -> ""
    else -> String.format(java.util.Locale.US, " (%+.1f cm since previous)", delta)
}

/** Formats the Fusion Body Measurements section. */
internal fun formatBodyLog(
    display: BodyLogDisplay?,
    timeFormatter: (Instant) -> String,
): String {
    val sb = StringBuilder()
    sb.appendLine("BODY MEASUREMENTS — Fusion (manual)")
    if (display == null || (display.latestChest == null && display.latestWaist == null)) {
        sb.appendLine("• No measurements logged yet — use LOG BODY MEASUREMENTS")
        return sb.toString()
    }
    val chest = display.latestChest
    if (chest != null) {
        sb.appendLine(
            "• Chest: ${cm(chest.valueCm)} (${timeFormatter(chest.measuredAt)})" +
                deltaText(deltaCm(chest, display.previousChest))
        )
    } else {
        sb.appendLine("• Chest: Not available")
    }
    val waist = display.latestWaist
    if (waist != null) {
        sb.appendLine(
            "• Waist: ${cm(waist.valueCm)} (${timeFormatter(waist.measuredAt)})" +
                deltaText(deltaCm(waist, display.previousWaist))
        )
    } else {
        sb.appendLine("• Waist: Not available")
    }
    if (display.recent.isNotEmpty()) {
        sb.appendLine("Recent:")
        for (m in display.recent) {
            sb.appendLine("  ${timeFormatter(m.measuredAt)} ${m.type.name.lowercase().replaceFirstChar { it.uppercase() }}: ${cm(m.valueCm)}")
        }
    }
    return sb.toString()
}

/**
 * Thin file-backed store for manual measurements. One line per record; every mutation rewrites the
 * whole file via a temp file + rename so a crash can't corrupt the log. App-private (filesDir).
 */
internal class BodyLogStore(private val file: File) {

    fun loadAll(): List<BodyMeasurement> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let { parseMeasurementLine(it) }
        }
    }

    fun add(measurement: BodyMeasurement) {
        writeAll(loadAll() + measurement)
    }

    fun delete(id: String) {
        writeAll(loadAll().filterNot { it.id == id })
    }

    private fun writeAll(all: List<BodyMeasurement>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(all.joinToString("\n") { serializeMeasurement(it) })
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // Rename failed (rare) — fall back to a direct write so the data is not lost.
            file.writeText(all.joinToString("\n") { serializeMeasurement(it) })
            tmp.delete()
        }
    }
}
