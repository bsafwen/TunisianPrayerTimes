package com.tunisianprayertimes.stage0

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tunisianprayertimes.stage0.dnd.DndRuleProbe
import com.tunisianprayertimes.stage0.execution.ExecutionProbe
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** User-launched diagnostic shell; no production UI, automatic startup, audio or vibration. */
class ProbeActivity : Activity() {
    private lateinit var output: TextView
    private var backgroundFenceRequested = false
    private var backgroundFenceGeneration = 0
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            setPadding(24, 24, 24, 24)
        }
        output = TextView(this).apply {
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            text = "Stage 0 feasibility probe\nSeparate test app. No sound or vibration is generated.\nTemporary location circles are not mosque boundaries."
            setTextIsSelectable(true)
        }
        column.addView(output)
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                isSoundEffectsEnabled = false
                isHapticFeedbackEnabled = false
                setOnClickListener { action() }
            })
        }
        button("Grant precise location") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
        button("Background location settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("DND access settings") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        button("Exact alarm settings") {
            if (android.os.Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        button("Observe location for 60 seconds") { runAction("location", Intent()) }
        button("Start through an alarm in 8 seconds") { runAction("alarm", Intent()) }
        button("Temporary initial-inside geofence") { runAction("geofence-current", Intent()) }
        button("Geofence after leaving this screen") { runAction("geofence-background", Intent()) }
        button("Run scoped DND checks") { runAction("dnd", Intent()) }
        button("Show redacted diagnostics") { runAction("status", Intent()) }
        button("Stop and clean up probe") { runAction("cleanup", Intent()) }
        setContentView(ScrollView(this).apply { addView(column) })
        ExecutionProbe.recoverExpired(this)
        lifecycle("created")
        val initialIntent = intent
        background.execute {
            File(filesDir, "startup-dnd-recovery.json").writeText(DndRuleProbe.recoverJournal(this).toString(2))
            runOnUiThread { runAction(initialIntent.getStringExtra("command") ?: "status", initialIntent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runAction(intent.getStringExtra("command") ?: "status", intent)
    }

    override fun onStop() {
        visible = false
        lifecycle("stopped")
        if (backgroundFenceRequested) {
            backgroundFenceRequested = false
            val generation = backgroundFenceGeneration
            lifecycle("background_geofence_delay_started")
            Handler(Looper.getMainLooper()).postDelayed({
                if (generation == backgroundFenceGeneration && !visible && !isDestroyed) {
                    lifecycle("background_geofence_registration_requested")
                    ExecutionProbe.registerAtCurrentPosition(applicationContext) { show(it.toString()) }
                }
            }, 30_000L)
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    private fun lifecycle(event: String) {
        File(filesDir, "activity-lifecycle.jsonl").appendText(
            JSONObject().put("event", event).put("elapsed_ms", SystemClock.elapsedRealtime()).toString() + "\n"
        )
    }

    private fun runAction(command: String, options: Intent) {
        val duration = options.getLongExtra("duration_ms", 60_000L)
        when (command) {
            "location" -> show(ExecutionProbe.startFromVisible(this, duration).toString())
            "alarm" -> show(ExecutionProbe.armAlarm(this, options.getLongExtra("delay_ms", 8_000L), duration).toString())
            "geofence-current" -> ExecutionProbe.registerAtCurrentPosition(this) { show(it.toString()) }
            "geofence-background" -> {
                backgroundFenceGeneration++
                backgroundFenceRequested = true
                show("Return to Home. A temporary geofence will be registered after this screen has been stopped for 30 seconds.")
            }
            "stop" -> {
                backgroundFenceGeneration++
                backgroundFenceRequested = false
                ExecutionProbe.stop(this, "explicit_probe_stop")
                exportDiagnostics()
            }
            "recover" -> {
                ExecutionProbe.recoverExpired(this)
                background.execute {
                    val recovered = DndRuleProbe.recoverJournal(this).toString(2)
                    File(filesDir, "explicit-dnd-recovery.json").writeText(recovered)
                    show(recovered)
                    exportDiagnostics()
                }
            }
            "dnd" -> background.execute {
                val result = runCatching { DndRuleProbe(this).runChecks(options.getBooleanExtra("synthetic_user_origin", false)).toString(2) }
                    .getOrElse { JSONObject().put("error", it.javaClass.simpleName).put("message", it.message).toString(2) }
                File(filesDir, "dnd-activity-result.json").writeText(result)
                show(result)
            }
            "cleanup" -> {
                backgroundFenceGeneration++
                backgroundFenceRequested = false
                ExecutionProbe.stop(this, "explicit_final_cleanup")
                exportDiagnostics()
                background.execute { show(DndRuleProbe.recoverJournal(this).toString(2)) }
                ExecutionProbe.wipe(this)
            }
            else -> exportDiagnostics()
        }
    }

    private fun exportDiagnostics() {
        val diagnostic = ExecutionProbe.diagnostics(this)
        File(filesDir, "execution-diagnostics.json").writeText(diagnostic)
        show(diagnostic)
    }

    private fun show(value: String) = runOnUiThread { output.text = value }

    companion object {
        private val background = Executors.newSingleThreadExecutor()
    }
}
