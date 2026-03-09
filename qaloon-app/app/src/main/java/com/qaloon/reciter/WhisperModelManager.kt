package com.qaloon.reciter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages loading the Whisper GGML model from assets or internal storage.
 */
class WhisperModelManager(private val context: Context) {

    private val whisper = WhisperJni()
    private var contextPtr: Long = 0L

    val isLoaded: Boolean get() = contextPtr != 0L

    /**
     * Copy model from assets to internal storage and load it.
     * Call from a background thread.
     */
    suspend fun loadModel(assetName: String = "ggml-model.bin"): Boolean =
        withContext(Dispatchers.IO) {
            val modelFile = File(context.filesDir, assetName)

            // Copy from assets if not already on disk
            if (!modelFile.exists()) {
                context.assets.open(assetName).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            contextPtr = whisper.initContext(modelFile.absolutePath)
            contextPtr != 0L
        }

    /**
     * Transcribe float PCM samples (16kHz mono) to Arabic text.
     */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        if (contextPtr == 0L) error("Model not loaded")
        whisper.transcribeWithLang(contextPtr, samples, "ar")
    }

    fun release() {
        if (contextPtr != 0L) {
            whisper.freeContext(contextPtr)
            contextPtr = 0L
        }
    }
}
