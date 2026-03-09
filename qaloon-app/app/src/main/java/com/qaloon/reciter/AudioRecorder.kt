package com.qaloon.reciter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records 16 kHz mono PCM audio and accumulates float samples.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var recorder: AudioRecord? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    @Volatile private var stopRequested = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Records audio until stop() is called.
     * Returns accumulated float samples normalized to [-1, 1].
     */
    suspend fun record(): FloatArray = withContext(Dispatchers.IO) {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            SAMPLE_RATE * 2 // at least 1 second buffer
        )

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize
        )

        recorder = audioRecord
        stopRequested = false
        val shortBuffer = ShortArray(bufSize / 2)
        val allSamples = mutableListOf<Float>()

        audioRecord.startRecording()
        _isRecording.value = true

        try {
            while (!stopRequested && isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        allSamples.add(shortBuffer[i] / 32768f)
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            recorder = null
            _isRecording.value = false
        }

        allSamples.toFloatArray()
    }

    fun stop() {
        stopRequested = true
    }
}
