package com.qaloon.reciter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qaloon.reciter.AudioRecorder
import com.qaloon.reciter.QuranTextProvider
import com.qaloon.reciter.RecitationDiffer
import com.qaloon.reciter.WhisperModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReciterViewModel(app: Application) : AndroidViewModel(app) {

    sealed class ModelState {
        data object NotLoaded : ModelState()
        data object Loading : ModelState()
        data object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    sealed class RecordingState {
        data object Idle : RecordingState()
        data object Recording : RecordingState()
        data object Transcribing : RecordingState()
    }

    data class RecitationResult(
        val transcription: String,
        val referenceText: String,
        val diff: List<RecitationDiffer.DiffWord>,
        val accuracy: Float,
        val detectedSurah: Int? = null,
        val detectedAyah: Int? = null
    )

    private val modelManager = WhisperModelManager(app)
    val audioRecorder = AudioRecorder(app)
    private val quranText = QuranTextProvider(app)

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _result = MutableStateFlow<RecitationResult?>(null)
    val result: StateFlow<RecitationResult?> = _result.asStateFlow()

    private val _surahs = MutableStateFlow<List<QuranTextProvider.Surah>>(emptyList())
    val surahs: StateFlow<List<QuranTextProvider.Surah>> = _surahs.asStateFlow()

    private val _selectedSurah = MutableStateFlow<Int>(1)
    val selectedSurah: StateFlow<Int> = _selectedSurah.asStateFlow()

    private val _selectedAyah = MutableStateFlow<Int>(1)
    val selectedAyah: StateFlow<Int> = _selectedAyah.asStateFlow()

    private val _autoDetect = MutableStateFlow(true)
    val autoDetect: StateFlow<Boolean> = _autoDetect.asStateFlow()

    private var recordingJob: Job? = null

    init {
        loadQuranText()
    }

    private fun loadQuranText() {
        viewModelScope.launch {
            try {
                quranText.load()
                _surahs.value = quranText.getSurahs()
            } catch (e: Exception) {
                // Quran text file not yet bundled — will show empty
            }
        }
    }

    fun loadModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            try {
                val success = modelManager.loadModel()
                _modelState.value = if (success) ModelState.Ready else ModelState.Error("Failed to load model")
            } catch (e: Exception) {
                _modelState.value = ModelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun selectSurah(number: Int) {
        _selectedSurah.value = number
        _selectedAyah.value = 1
        _result.value = null
    }

    fun selectAyah(number: Int) {
        _selectedAyah.value = number
        _result.value = null
    }

    fun setAutoDetect(enabled: Boolean) {
        _autoDetect.value = enabled
        _result.value = null
    }

    fun startRecording() {
        _result.value = null
        _recordingState.value = RecordingState.Recording
        recordingJob = viewModelScope.launch {
            val samples = audioRecorder.record()
            processRecording(samples)
        }
    }

    fun stopRecording() {
        audioRecorder.stop()
    }

    private suspend fun processRecording(samples: FloatArray) {
        _recordingState.value = RecordingState.Transcribing

        try {
            val transcription = modelManager.transcribe(samples)

            val reference: String
            var detectedSurah: Int? = null
            var detectedAyah: Int? = null

            if (_autoDetect.value) {
                val match = quranText.findAyah(transcription)
                if (match != null) {
                    reference = match.text
                    detectedSurah = match.surah
                    detectedAyah = match.ayah
                    _selectedSurah.value = match.surah
                    _selectedAyah.value = match.ayah
                } else {
                    reference = ""
                }
            } else {
                reference = quranText.getAyahText(_selectedSurah.value, _selectedAyah.value) ?: ""
            }

            val diff = if (reference.isNotBlank()) {
                RecitationDiffer.diff(reference, transcription)
            } else {
                transcription.split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
                    .map { RecitationDiffer.DiffWord(it, RecitationDiffer.WordStatus.CORRECT) }
            }

            val correctCount = diff.count { it.status == RecitationDiffer.WordStatus.CORRECT }
            val totalRef = diff.count { it.status != RecitationDiffer.WordStatus.EXTRA }
            val accuracy = if (totalRef > 0) correctCount.toFloat() / totalRef else 0f

            _result.value = RecitationResult(
                transcription = transcription,
                referenceText = reference,
                diff = diff,
                accuracy = accuracy,
                detectedSurah = detectedSurah,
                detectedAyah = detectedAyah
            )
        } catch (e: Exception) {
            _result.value = RecitationResult(
                transcription = "Error: ${e.message}",
                referenceText = "",
                diff = emptyList(),
                accuracy = 0f
            )
        }

        _recordingState.value = RecordingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        modelManager.release()
    }
}
