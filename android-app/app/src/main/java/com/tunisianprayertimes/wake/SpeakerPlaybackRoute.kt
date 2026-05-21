package com.tunisianprayertimes.wake

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class SpeakerPlaybackRoute(
    context: Context,
    private val player: MediaPlayer,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var keepSpeakerJob: Job? = null
    private var callbackRegistered = false
    private var released = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            enforceSoon()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            enforceSoon()
        }
    }

    fun applyNow() {
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return

        runCatching { player.setPreferredDevice(speaker) }
    }

    fun start() {
        released = false
        applyNow()
        if (!callbackRegistered) {
            runCatching {
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
                callbackRegistered = true
            }
        }

        keepSpeakerJob?.cancel()
        keepSpeakerJob = scope.launch {
            repeat(40) {
                applyNow()
                delay(250L)
            }

            while (isActive) {
                delay(1_000L)
                applyNow()
            }
        }
    }

    fun release() {
        released = true
        keepSpeakerJob?.cancel()
        keepSpeakerJob = null
        if (callbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            callbackRegistered = false
        }
        runCatching { player.setPreferredDevice(null) }
    }

    private fun enforceSoon() {
        if (released) return
        scope.launch { applyNow() }
    }
}
