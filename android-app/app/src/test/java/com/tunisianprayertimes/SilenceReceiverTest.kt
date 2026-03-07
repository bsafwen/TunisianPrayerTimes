package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34])
class SilenceReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: SilenceReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = SilenceReceiver()
    }

    @Test
    fun onReceive_silenceAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        }
        // Should not throw — DND permission won't be granted in test, so it just skips
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_unsilenceAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "DHUHR")
        }
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_rescheduleAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_RESCHEDULE")
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_unknownAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.UNKNOWN_ACTION")
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_nullAction_doesNotCrash() {
        val intent = Intent()
        receiver.onReceive(context, intent)
    }
}
