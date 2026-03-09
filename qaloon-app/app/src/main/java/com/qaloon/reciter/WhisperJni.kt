package com.qaloon.reciter

/**
 * JNI bridge to whisper.cpp for on-device speech recognition.
 * The native library "whisper_jni" is built from src/main/cpp/.
 */
class WhisperJni {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    /** Load a GGML model file. Returns a native context pointer (0 = failure). */
    external fun initContext(modelPath: String): Long

    /** Free a previously loaded model context. */
    external fun freeContext(contextPtr: Long)

    /**
     * Run full transcription on 16kHz mono float PCM samples.
     * Returns the transcribed text.
     */
    external fun transcribe(contextPtr: Long, samples: FloatArray): String

    /**
     * Run transcription with language hint.
     * [language] is an ISO 639-1 code like "ar".
     */
    external fun transcribeWithLang(
        contextPtr: Long,
        samples: FloatArray,
        language: String
    ): String
}
