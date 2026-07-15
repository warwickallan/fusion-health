package com.fusionhealth.diagnostic

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * BUILD-005 — Fusion Health home screen. The single launcher icon: instead of several identical
 * "Fusion Health" icons in the app drawer (one per screen, which was easy to confuse), there is now
 * one icon that opens this menu, and each tool is a labelled button. Every underlying screen keeps
 * its exact behaviour — only how they're reached changed.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.openSnapshotButton).setOnClickListener {
            startActivity(Intent(this, SamsungSnapshotActivity::class.java))
        }
        findViewById<Button>(R.id.openBodyLogButton).setOnClickListener {
            startActivity(Intent(this, BodyLogActivity::class.java))
        }
        findViewById<Button>(R.id.openDiagnosticButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.openSyncSpikeButton).setOnClickListener {
            startActivity(Intent(this, SyncSpikeActivity::class.java))
        }
    }
}
