package com.fusionhealth.diagnostic

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dedicated permissions-rationale / privacy screen required by Health Connect so the app is
 * discoverable in the system's Health Connect permissions UI. Handles both the pre-Android-14
 * intent (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`, declared directly on this
 * activity) and the Android 14+ mechanism (`android.intent.action.VIEW_PERMISSION_USAGE` +
 * `android.intent.category.HEALTH_PERMISSIONS`, declared on an activity-alias targeting this
 * activity — see AndroidManifest.xml). No health data is read or displayed here.
 */
class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = "Fusion Health — Health Connect data use\n\n" +
                "Fusion Health reads six Health Connect record types (steps, sleep, heart " +
                "rate, nutrition, weight, body fat) for read-only diagnostic purposes.\n\n" +
                "No health data is written to Health Connect. No health data is uploaded, " +
                "transmitted over the network, or stored beyond the diagnostic screen for " +
                "the duration this app is open."
        }
        setContentView(text)
    }
}
