package com.tunisianprayertimes.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.tunisianprayertimes.RingtonePreset

class WakeRingtonePreviewPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(preset: RingtonePreset, customRingtoneUri: String? = null): Boolean {
        stop()

        if (preset == RingtonePreset.CUSTOM && customRingtoneUri != null) {
            return playUri(Uri.parse(customRingtoneUri))
        }

        WakeRingtoneCatalog.rawResIdFor(preset)?.let { rawResId ->
            val player = MediaPlayer.create(context, rawResId, previewAudioAttributes(), 0)
            if (player != null) {
                player.isLooping = true
                player.start()
                mediaPlayer = player
                return true
            }
        }

        WakeRingtoneCatalog.systemTypeFor(preset)
            ?.let { systemType -> RingtoneManager.getDefaultUri(systemType) }
            ?.let { systemUri ->
                if (playUri(systemUri)) {
                    return true
                }
            }

        return false
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    fun release() {
        stop()
    }

    private fun playUri(uri: Uri): Boolean {
        val player = MediaPlayer()
        return try {
            player.setDataSource(context, uri)
            player.setAudioAttributes(previewAudioAttributes())
            player.isLooping = true
            player.prepare()
            player.start()
            mediaPlayer = player
            true
        } catch (_: Exception) {
            runCatching { player.release() }
            false
        }
    }

    private fun previewAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}