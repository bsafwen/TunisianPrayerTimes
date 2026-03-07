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
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: BootReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver()
    }

    @Test
    fun onReceive_bootCompleted_doesNotCrash() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_packageReplaced_doesNotCrash() {
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        receiver.onReceive(context, intent)
    }
}
