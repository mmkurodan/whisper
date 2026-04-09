#include <android/log.h>
#include <jni.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_initContext(JNIEnv *env, jclass clazz, jstring model_path_str) {
    (void) clazz;

    if (model_path_str == NULL) {
        LOGE("Model path was null");
        return 0;
    }

    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    if (model_path == NULL) {
        LOGE("Failed to access model path");
        return 0;
    }

    struct whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, context_params);

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_micklab_whisper_WhisperJni_freeContext(JNIEnv *env, jclass clazz, jlong context_ptr) {
    (void) env;
    (void) clazz;

    if (context_ptr != 0) {
        whisper_free((struct whisper_context *) context_ptr);
    }
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_fullTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint num_threads,
        jfloatArray audio_data,
        jstring language_str,
        jboolean include_timestamps) {
    (void) clazz;

    if (context_ptr == 0 || audio_data == NULL) {
        LOGE("Invalid context or audio buffer");
        return -1;
    }

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_ptr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (audio_data_ptr == NULL) {
        LOGE("Failed to access audio buffer");
        return -1;
    }

    const jsize audio_length = (*env)->GetArrayLength(env, audio_data);
    const char *language_chars = NULL;
    const char *language = "ja";
    if (language_str != NULL) {
        language_chars = (*env)->GetStringUTFChars(env, language_str, NULL);
        if (language_chars != NULL && language_chars[0] != '\0') {
            language = language_chars;
        }
    }

    struct whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = num_threads > 0 ? num_threads : 1;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = include_timestamps == JNI_TRUE;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = include_timestamps == JNI_FALSE;
    params.single_segment = false;
    params.detect_language = false;
    params.language = language;

    whisper_reset_timings(context);
    int result = whisper_full(context, params, audio_data_ptr, audio_length);

    if (language_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language_chars);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_ptr, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentCount(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr) {
    (void) env;
    (void) clazz;
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegment(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint index) {
    (void) clazz;

    const char *segment = whisper_full_get_segment_text(
            (struct whisper_context *) context_ptr,
            index
    );
    if (segment == NULL) {
        segment = "";
    }
    return (*env)->NewStringUTF(env, segment);
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentT0(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint index) {
    (void) env;
    (void) clazz;
    return whisper_full_get_segment_t0((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentT1(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint index) {
    (void) env;
    (void) clazz;
    return whisper_full_get_segment_t1((struct whisper_context *) context_ptr, index);
}
