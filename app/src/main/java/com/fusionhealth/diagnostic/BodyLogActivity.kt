package com.fusionhealth.diagnostic

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * BUILD-005/WP2 — LOG BODY MEASUREMENTS. Entry form for Fusion-owned manual chest and waist
 * circumference in centimetres. Either field can be saved alone or both together; the measurement
 * date/time defaults to now and is editable. Entries persist in app-private storage (see
 * [BodyLogStore]) and a mistaken entry can be deleted (with confirmation) and re-entered.
 * No Health Connect write permission; nothing leaves the device.
 */
class BodyLogActivity : AppCompatActivity() {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private lateinit var store: BodyLogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_log)
        store = BodyLogStore(File(filesDir, "body_measurements.txt"))

        findViewById<EditText>(R.id.measuredAtInput).setText(
            LocalDateTime.now().format(dateTimeFormat)
        )
        findViewById<Button>(R.id.saveMeasurementsButton).setOnClickListener { onSaveClicked() }
        renderHistory()
    }

    private fun onSaveClicked() {
        val chestText = findViewById<EditText>(R.id.chestInput).text.toString().trim()
        val waistText = findViewById<EditText>(R.id.waistInput).text.toString().trim()
        val whenText = findViewById<EditText>(R.id.measuredAtInput).text.toString().trim()

        if (chestText.isEmpty() && waistText.isEmpty()) {
            setStatus("Enter a chest and/or waist value in cm before saving.")
            return
        }

        val measuredAt = try {
            LocalDateTime.parse(whenText, dateTimeFormat).atZone(ZoneId.systemDefault()).toInstant()
        } catch (e: Exception) {
            setStatus("Date/time must look like 2026-07-15 08:30")
            return
        }

        val saved = mutableListOf<String>()
        for ((text, type) in listOf(chestText to BodyMetricType.CHEST, waistText to BodyMetricType.WAIST)) {
            if (text.isEmpty()) continue
            val value = text.toDoubleOrNull()
            if (value == null || !isPlausibleCircumferenceCm(value)) {
                setStatus("${type.name.lowercase().replaceFirstChar { it.uppercase() }} must be a sensible number of centimetres.")
                return
            }
            store.add(
                BodyMeasurement(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    valueCm = value,
                    measuredAt = measuredAt,
                    createdAt = Instant.now(),
                )
            )
            saved += type.name.lowercase()
        }

        findViewById<EditText>(R.id.chestInput).setText("")
        findViewById<EditText>(R.id.waistInput).setText("")
        setStatus("Saved ${saved.joinToString(" and ")}.")
        renderHistory()
    }

    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        container.removeAllViews()
        val all = store.loadAll().sortedByDescending { it.measuredAt }
        if (all.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No measurements logged yet."
                setPadding(0, 16, 0, 16)
            })
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
        for (m in all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = String.format(
                    java.util.Locale.US, "%s — %s: %.1f cm",
                    fmt.format(m.measuredAt),
                    m.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    m.valueCm,
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener { confirmDelete(m) }
            })
            container.addView(row)
        }
    }

    private fun confirmDelete(m: BodyMeasurement) {
        AlertDialog.Builder(this)
            .setTitle("Delete measurement?")
            .setMessage(
                String.format(
                    java.util.Locale.US, "%s %.1f cm — this cannot be undone (you can re-enter it).",
                    m.type.name.lowercase().replaceFirstChar { it.uppercase() }, m.valueCm,
                )
            )
            .setPositiveButton("Delete") { _, _ ->
                store.delete(m.id)
                setStatus("Deleted.")
                renderHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.bodyLogStatus).text = text
    }
}
