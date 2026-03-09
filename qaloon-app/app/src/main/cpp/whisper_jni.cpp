#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static void whisper_android_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr || text[0] == '\0') return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default:                   LOGI("%s", text); break;
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_qaloon_reciter_WhisperJni_initContext(JNIEnv *env, jobject /* this */, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    whisper_log_set(whisper_android_log_callback, nullptr);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU-only for maximum compatibility

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return 0;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_qaloon_reciter_WhisperJni_freeContext(JNIEnv *env, jobject /* this */, jlong context_ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Model context freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_qaloon_reciter_WhisperJni_transcribe(JNIEnv *env, jobject /* this */,
                                               jlong context_ptr, jfloatArray samples) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize n_samples = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "ar";
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.n_threads = 4;

    LOGI("Transcribing %d samples...", n_samples);
    int ret = whisper_full(ctx, params, data, n_samples);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return env->NewStringUTF("");
    }

    // Concatenate all segments
    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }

    LOGI("Transcription: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_qaloon_reciter_WhisperJni_transcribeWithLang(JNIEnv *env, jobject thiz,
                                                       jlong context_ptr, jfloatArray samples,
                                                       jstring language) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);
    jsize n_samples = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang;
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.n_threads = 4;

    LOGI("Transcribing %d samples (lang=%s)...", n_samples, lang);
    int ret = whisper_full(ctx, params, data, n_samples);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }

    LOGI("Transcription: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

}
