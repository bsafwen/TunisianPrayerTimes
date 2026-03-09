package com.qaloon.reciter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qaloon.reciter.AudioRecorder
import com.qaloon.reciter.ContributionUploader
import com.qaloon.reciter.QuranTextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ContributeViewModel(app: Application) : AndroidViewModel(app) {

    sealed class UploadState {
        data object Idle : UploadState()
        data object Recording : UploadState()
        data object Uploading : UploadState()
        data object Success : UploadState()
        data class Error(val message: String) : UploadState()
    }

    val audioRecorder = AudioRecorder(app)
    private val quranText = QuranTextProvider(app)
    private val uploader = ContributionUploader(app)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _surahs = MutableStateFlow<List<QuranTextProvider.Surah>>(emptyList())
    val surahs: StateFlow<List<QuranTextProvider.Surah>> = _surahs.asStateFlow()

    private val _currentSurah = MutableStateFlow(1)
    val currentSurah: StateFlow<Int> = _currentSurah.asStateFlow()

    private val _currentAyah = MutableStateFlow(1)
    val currentAyah: StateFlow<Int> = _currentAyah.asStateFlow()

    private val _contributionCount = MutableStateFlow(0)
    val contributionCount: StateFlow<Int> = _contributionCount.asStateFlow()

    private var recordingJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                quranText.load()
                _surahs.value = quranText.getSurahs()
            } catch (_: Exception) { }
        }
    }

    fun selectSurah(number: Int) {
        _currentSurah.value = number
        _currentAyah.value = 1
    }

    fun selectAyah(number: Int) {
        _currentAyah.value = number
    }

    /** Get the text for the current ayah. */
    fun getCurrentText(): String {
        return quranText.getAyahText(_currentSurah.value, _currentAyah.value) ?: ""
    }

    fun startRecording() {
        _state.value = UploadState.Recording
        recordingJob = viewModelScope.launch {
            val samples = audioRecorder.record()
            uploadRecording(samples)
        }
    }

    fun stopRecording() {
        audioRecorder.stop()
    }

    private suspend fun uploadRecording(samples: FloatArray) {
        _state.value = UploadState.Uploading

        try {
            val wavBytes = encodeWav(samples, AudioRecorder.SAMPLE_RATE)
            val text = getCurrentText()

            val success = withContext(Dispatchers.IO) {
                uploader.upload(wavBytes, _currentSurah.value, _currentAyah.value, text)
            }

            if (success) {
                _contributionCount.value++
                _state.value = UploadState.Success
                // Auto-advance to next ayah
                advanceAyah()
            } else {
                _state.value = UploadState.Error("Upload failed — check your connection")
            }
        } catch (e: Exception) {
            _state.value = UploadState.Error(e.message ?: "Upload error")
        }
    }

    fun resetState() {
        _state.value = UploadState.Idle
    }

    private fun advanceAyah() {
        val surah = _surahs.value.find { it.number == _currentSurah.value }
        val maxAyah = surah?.ayahs?.size ?: 0
        if (_currentAyah.value < maxAyah) {
            _currentAyah.value++
        } else if (_currentSurah.value < 114) {
            _currentSurah.value++
            _currentAyah.value = 1
        }
    }

    /** Encode float samples as 16-bit PCM WAV. */
    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val numSamples = samples.size
        val dataSize = numSamples * 2 // 16-bit = 2 bytes per sample
        val fileSize = 36 + dataSize

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(fileSize))
        dos.writeBytes("WAVE")

        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16)) // chunk size
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt()) // PCM format
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt()) // mono
        dos.writeInt(Integer.reverseBytes(sampleRate))
        dos.writeInt(Integer.reverseBytes(sampleRate * 2)) // byte rate
        dos.writeShort(java.lang.Short.reverseBytes(2).toInt()) // block align
        dos.writeShort(java.lang.Short.reverseBytes(16).toInt()) // bits per sample

        // data chunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(dataSize))
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val pcm = (clamped * 32767).toInt().toShort()
            dos.writeShort(java.lang.Short.reverseBytes(pcm).toInt())
        }

        dos.flush()
        return baos.toByteArray()
    }
}
