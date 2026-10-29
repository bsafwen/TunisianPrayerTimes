package com.tunisianprayertimes.stage0.dnd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

/** Manifest must protect this exported, explicit-command-only receiver with android.permission.DUMP. */
class DndSessionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        sessionExecutor.execute {
            try {
                val probe = DndSessionProbe(context)
                val report = when (intent.action) {
                    DndSessionProbe.ACTION_PREPARE -> probe.prepare(intent.getLongExtra("duration_ms", 300_000L))
                    DndSessionProbe.ACTION_OBSERVE -> probe.observe()
                    DndSessionProbe.ACTION_REPEAT_TRUE -> probe.repeatTrue()
                    DndSessionProbe.ACTION_FINISH -> probe.finish("explicit_debug_receiver_finish")
                    else -> error("Unknown explicit session command")
                }
                pending.resultData = report.toString()
            } catch (failure: Throwable) { Log.e("Stage0DndSession", "Command failed", failure) }
            finally { pending.finish() }
        }
    }
}

class DndSessionExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        sessionExecutor.execute {
            try { DndSessionProbe(context).onDeadlineOrBoot(intent) }
            catch (failure: Throwable) { Log.e("Stage0DndSession", "Expiry/reboot cleanup failed", failure) }
            finally { pending.finish() }
        }
    }
}

/** Status is observational; untrusted or unavailable sender identity never authorizes a mutation. */
class DndSessionStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
        val pending = goAsync()
        sessionExecutor.execute {
            try { DndSessionProbe(context).noteStatus(intent, sender) }
            catch (failure: Throwable) { Log.e("Stage0DndSession", "Status capture failed", failure) }
            finally { pending.finish() }
        }
    }
}

private val sessionExecutor = Executors.newSingleThreadExecutor()
