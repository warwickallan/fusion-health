package com.fusionhealth.diagnostic

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * WP1/PR1 scaffold only: proves the cloud build → signed APK → sideload chain.
 * Deliberately requests no permissions and reads no health data.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val status = buildString {
            appendLine("Fusion Health — Diagnostic Scaffold")
            appendLine()
            @Suppress("DEPRECATION") // versionCode (not longVersionCode) is the correct call at minSdk 26 — getLongVersionCode requires API 28+
            appendLine("Version: ${packageInfo.versionName} (${packageInfo.versionCode})")
            appendLine("Package: $packageName")
            appendLine()
            appendLine("WP1 / PR1 — cloud build pipeline check.")
            appendLine("No health permissions requested by this build.")
        }

        findViewById<TextView>(R.id.statusText).text = status
    }
}
