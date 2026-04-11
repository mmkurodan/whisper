#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define RESULT_TIMEOUT   -2
#define RESULT_CANCELLED -3
#define FIXED_LANGUAGE "ja"
#define TINY_AUDIO_CONTEXT 256
#define TINY_TEXT_CONTEXT 96
#define MIN_TINY_THREADS 1
#define MAX_TINY_THREADS 2

static JavaVM *g_java_vm = NULL;
static jclass g_whisper_jni_class = NULL;
static jmethodID g_dispatch_native_log_method = NULL;

static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_cancel_mutex = PTHREAD_MUTEX_INITIALIZER;

struct context_control {
    struct whisper_context *context;
    bool cancel_requested;
    struct context_control *next;
};

struct transcribe_run_state {
    struct whisper_context *context;
    int timeout_ms;
    int last_logged_progress;
    int64_t start_time_ms;
    int chunk_index;
    int64_t chunk_start_ms;
    int audio_samples;
    bool streaming;
    bool timed_out;
    bool cancelled;
};

static struct context_control *g_context_controls = NULL;

static int android_priority_from_level(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG:
            return ANDROID_LOG_DEBUG;
        case GGML_LOG_LEVEL_WARN:
            return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_ERROR:
            return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_INFO:
        case GGML_LOG_LEVEL_CONT:
        case GGML_LOG_LEVEL_NONE:
        default:
            return ANDROID_LOG_INFO;
    }
}

static int64_t now_monotonic_ms(void) {
    struct timespec spec;
    clock_gettime(CLOCK_MONOTONIC, &spec);
    return ((int64_t) spec.tv_sec * 1000LL) + ((int64_t) spec.tv_nsec / 1000000LL);
}

static int clamp_int(int value, int min_value, int max_value) {
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

static int tiny_audio_context(struct whisper_context *context) {
    if (context == NULL) {
        return 0;
    }

    const int max_audio_context = whisper_n_audio_ctx(context);
    if (max_audio_context <= 0) {
        return 0;
    }

    return clamp_int(TINY_AUDIO_CONTEXT, 1, max_audio_context);
}

static int tiny_thread_count(int requested_threads) {
    const int safe_threads = requested_threads <= 0 ? 1 : requested_threads;
    return clamp_int(safe_threads, MIN_TINY_THREADS, MAX_TINY_THREADS);
}

static void apply_tiny_params(
        struct whisper_context *context,
        struct whisper_full_params *params,
        int requested_threads,
        bool include_timestamps,
        bool reuse_text_context) {
    if (params == NULL) {
        return;
    }

    *params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params->n_threads = tiny_thread_count(requested_threads);
    params->n_max_text_ctx = TINY_TEXT_CONTEXT;
    params->print_realtime = false;
    params->print_progress = false;
    params->print_timestamps = include_timestamps;
    params->print_special = false;
    params->translate = false;
    params->no_context = !reuse_text_context;
    params->no_timestamps = !include_timestamps;
    params->single_segment = reuse_text_context;
    params->detect_language = false;
    params->language = FIXED_LANGUAGE;
    params->temperature = 0.0f;
    params->temperature_inc = 0.0f;
    params->greedy.best_of = 1;
    params->audio_ctx = tiny_audio_context(context);
    params->suppress_blank = true;
    params->suppress_nst = true;
}

static void dispatch_native_log(enum ggml_log_level level, const char *text) {
    if (text == NULL || text[0] == '\0') {
        return;
    }

    __android_log_print(android_priority_from_level(level), TAG, "%s", text);

    if (g_java_vm == NULL || g_whisper_jni_class == NULL || g_dispatch_native_log_method == NULL) {
        return;
    }

    JNIEnv *env = NULL;
    bool attached = false;
    jint env_status = (*g_java_vm)->GetEnv(g_java_vm, (void **) &env, JNI_VERSION_1_6);
    if (env_status == JNI_EDETACHED) {
        if ((*g_java_vm)->AttachCurrentThread(g_java_vm, (void **) &env, NULL) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (env_status != JNI_OK) {
        return;
    }

    jstring message = (*env)->NewStringUTF(env, text);
    if (message != NULL) {
        (*env)->CallStaticVoidMethod(
                env,
                g_whisper_jni_class,
                g_dispatch_native_log_method,
                (jint) level,
                message
        );
        (*env)->DeleteLocalRef(env, message);
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (attached) {
        (*g_java_vm)->DetachCurrentThread(g_java_vm);
    }
}

static void dispatch_native_logf(enum ggml_log_level level, const char *format, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    dispatch_native_log(level, buffer);
}

static void whisper_android_log_callback(enum ggml_log_level level, const char *text, void *user_data) {
    (void) user_data;
    dispatch_native_log(level, text);
}

static struct context_control *find_context_control_locked(struct whisper_context *context) {
    for (struct context_control *node = g_context_controls; node != NULL; node = node->next) {
        if (node->context == context) {
            return node;
        }
    }
    return NULL;
}

static void register_context_control(struct whisper_context *context) {
    if (context == NULL) {
        return;
    }

    pthread_mutex_lock(&g_cancel_mutex);
    struct context_control *existing = find_context_control_locked(context);
    if (existing != NULL) {
        existing->cancel_requested = false;
        pthread_mutex_unlock(&g_cancel_mutex);
        return;
    }

    struct context_control *node = (struct context_control *) calloc(1, sizeof(*node));
    if (node != NULL) {
        node->context = context;
        node->cancel_requested = false;
        node->next = g_context_controls;
        g_context_controls = node;
    }
    pthread_mutex_unlock(&g_cancel_mutex);
}

static void unregister_context_control(struct whisper_context *context) {
    if (context == NULL) {
        return;
    }

    pthread_mutex_lock(&g_cancel_mutex);
    struct context_control **cursor = &g_context_controls;
    while (*cursor != NULL) {
        if ((*cursor)->context == context) {
            struct context_control *removed = *cursor;
            *cursor = removed->next;
            free(removed);
            break;
        }
        cursor = &(*cursor)->next;
    }
    pthread_mutex_unlock(&g_cancel_mutex);
}

static void set_cancel_requested(struct whisper_context *context, bool cancel_requested) {
    if (context == NULL) {
        return;
    }

    pthread_mutex_lock(&g_cancel_mutex);
    struct context_control *node = find_context_control_locked(context);
    if (node != NULL) {
        node->cancel_requested = cancel_requested;
    }
    pthread_mutex_unlock(&g_cancel_mutex);
}

static bool is_cancel_requested(struct whisper_context *context) {
    bool cancel_requested = false;

    if (context == NULL) {
        return false;
    }

    pthread_mutex_lock(&g_cancel_mutex);
    struct context_control *node = find_context_control_locked(context);
    if (node != NULL) {
        cancel_requested = node->cancel_requested;
    }
    pthread_mutex_unlock(&g_cancel_mutex);

    return cancel_requested;
}

static bool ensure_bridge_initialized(JNIEnv *env) {
    if (g_whisper_jni_class != NULL && g_dispatch_native_log_method != NULL) {
        return true;
    }

    pthread_mutex_lock(&g_bridge_mutex);
    if (g_whisper_jni_class == NULL || g_dispatch_native_log_method == NULL) {
        jclass local_class = (*env)->FindClass(env, "com/micklab/whisper/WhisperJni");
        if (local_class == NULL) {
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
            pthread_mutex_unlock(&g_bridge_mutex);
            return false;
        }

        g_whisper_jni_class = (*env)->NewGlobalRef(env, local_class);
        (*env)->DeleteLocalRef(env, local_class);
        if (g_whisper_jni_class == NULL) {
            pthread_mutex_unlock(&g_bridge_mutex);
            return false;
        }

        g_dispatch_native_log_method = (*env)->GetStaticMethodID(
                env,
                g_whisper_jni_class,
                "dispatchNativeLog",
                "(ILjava/lang/String;)V"
        );
        if (g_dispatch_native_log_method == NULL) {
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
            (*env)->DeleteGlobalRef(env, g_whisper_jni_class);
            g_whisper_jni_class = NULL;
            pthread_mutex_unlock(&g_bridge_mutex);
            return false;
        }

        whisper_log_set(whisper_android_log_callback, NULL);
    }
    pthread_mutex_unlock(&g_bridge_mutex);
    return true;
}

static bool android_whisper_abort_callback(void *user_data) {
    struct transcribe_run_state *run_state = (struct transcribe_run_state *) user_data;
    if (run_state == NULL) {
        return false;
    }

    if (is_cancel_requested(run_state->context)) {
        run_state->cancelled = true;
        return true;
    }

    if (run_state->timeout_ms > 0
            && (now_monotonic_ms() - run_state->start_time_ms) > run_state->timeout_ms) {
        run_state->timed_out = true;
        return true;
    }

    return false;
}

static bool android_whisper_encoder_begin_callback(
        struct whisper_context *ctx,
        struct whisper_state *state,
        void *user_data) {
    (void) ctx;
    (void) state;

    struct transcribe_run_state *run_state = (struct transcribe_run_state *) user_data;
    if (run_state != NULL) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_INFO,
                "%s chunk=%d encoder started: chunkStartMs=%lld elapsed=%lldms",
                run_state->streaming ? "stream" : "batch",
                run_state->chunk_index,
                (long long) run_state->chunk_start_ms,
                (long long) (now_monotonic_ms() - run_state->start_time_ms)
        );
    }
    return true;
}

static void android_whisper_progress_callback(
        struct whisper_context *ctx,
        struct whisper_state *state,
        int progress,
        void *user_data) {
    (void) ctx;
    (void) state;

    struct transcribe_run_state *run_state = (struct transcribe_run_state *) user_data;
    if (run_state == NULL) {
        return;
    }

    if (progress < 100 && progress < run_state->last_logged_progress + 5) {
        return;
    }

    run_state->last_logged_progress = progress;
    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "%s chunk=%d progress=%d%% elapsed=%lldms",
            run_state->streaming ? "stream" : "batch",
            run_state->chunk_index,
            progress,
            (long long) (now_monotonic_ms() - run_state->start_time_ms)
    );
}

static void android_whisper_new_segment_callback(
        struct whisper_context *ctx,
        struct whisper_state *state,
        int n_new,
        void *user_data) {
    (void) ctx;

    struct transcribe_run_state *run_state = (struct transcribe_run_state *) user_data;
    if (state == NULL || n_new <= 0) {
        return;
    }

    int total_segments = whisper_full_n_segments_from_state(state);
    int start_index = total_segments - n_new;
    for (int i = start_index; i < total_segments; ++i) {
        const char *segment = whisper_full_get_segment_text_from_state(state, i);
        int64_t t0 = whisper_full_get_segment_t0_from_state(state, i) * 10LL;
        int64_t t1 = whisper_full_get_segment_t1_from_state(state, i) * 10LL;
        dispatch_native_logf(
                GGML_LOG_LEVEL_INFO,
                "%s chunk=%d segment[%d]=[%lldms..%lldms] %s",
                run_state != NULL && run_state->streaming ? "stream" : "batch",
                run_state != NULL ? run_state->chunk_index : -1,
                i,
                (long long) t0,
                (long long) t1,
                segment == NULL ? "" : segment
        );
    }
}

static void log_state_timing_breakdown(
        struct whisper_state *state,
        const struct transcribe_run_state *run_state,
        int64_t elapsed_ms) {
    struct whisper_timings timings = {0};
    if (state == NULL || run_state == NULL) {
        return;
    }

    if (whisper_get_timings_from_state(state, &timings) != 0) {
        return;
    }

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "%s chunk=%d timings: mel=%.2fms sample=%.2fms encode=%.2fms decode=%.2fms batch=%.2fms prompt=%.2fms total=%lldms",
            run_state->streaming ? "stream" : "batch",
            run_state->chunk_index,
            timings.mel_ms,
            timings.sample_ms,
            timings.encode_ms,
            timings.decode_ms,
            timings.batchd_ms,
            timings.prompt_ms,
            (long long) elapsed_ms
    );
}

static void log_run_start(
        struct whisper_context *context,
        const struct whisper_full_params *params,
        int audio_length,
        int chunk_index,
        int64_t chunk_start_ms,
        bool streaming) {
    const int audio_frames = (audio_length + WHISPER_HOP_LENGTH - 1) / WHISPER_HOP_LENGTH;
    const int max_audio_context = whisper_n_audio_ctx(context);

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "%s start: chunk=%d chunkStartMs=%lld samples=%d seconds=%.2f frames=%d threads=%d language=%s translate=false no_context=%s single_segment=%s audio_ctx=%d/%d n_max_text_ctx=%d speed_up=unavailable-in-bundled-whisper",
            streaming ? "stream" : "batch",
            chunk_index,
            (long long) chunk_start_ms,
            audio_length,
            (double) audio_length / (double) WHISPER_SAMPLE_RATE,
            audio_frames,
            params->n_threads,
            params->language == NULL ? FIXED_LANGUAGE : params->language,
            params->no_context ? "true" : "false",
            params->single_segment ? "true" : "false",
            params->audio_ctx,
            max_audio_context,
            params->n_max_text_ctx
    );
}

static jint run_with_state(
        JNIEnv *env,
        struct whisper_context *context,
        struct whisper_state *state,
        jint num_threads,
        jfloatArray audio_data,
        jboolean include_timestamps,
        jint timeout_ms,
        jint chunk_index,
        jlong chunk_start_ms) {
    if (context == NULL || state == NULL || audio_data == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Invalid stream context, state, or audio buffer");
        return -1;
    }

    jfloat *audio_data_ptr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (audio_data_ptr == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Failed to access audio buffer");
        return -1;
    }

    const jsize audio_length = (*env)->GetArrayLength(env, audio_data);
    struct whisper_full_params params;
    apply_tiny_params(context, &params, num_threads, include_timestamps == JNI_TRUE, true);

    struct transcribe_run_state run_state = {
            .context = context,
            .timeout_ms = timeout_ms > 0 ? timeout_ms : 0,
            .last_logged_progress = -5,
            .start_time_ms = now_monotonic_ms(),
            .chunk_index = chunk_index,
            .chunk_start_ms = chunk_start_ms,
            .audio_samples = (int) audio_length,
            .streaming = true,
            .timed_out = false,
            .cancelled = false,
    };

    params.progress_callback = android_whisper_progress_callback;
    params.progress_callback_user_data = &run_state;
    params.new_segment_callback = android_whisper_new_segment_callback;
    params.new_segment_callback_user_data = &run_state;
    params.encoder_begin_callback = android_whisper_encoder_begin_callback;
    params.encoder_begin_callback_user_data = &run_state;
    params.abort_callback = android_whisper_abort_callback;
    params.abort_callback_user_data = &run_state;

    set_cancel_requested(context, false);
    whisper_reset_timings_from_state(state);
    log_run_start(context, &params, audio_length, chunk_index, chunk_start_ms, true);

    int result = whisper_full_with_state(context, state, params, audio_data_ptr, (int) audio_length);
    int64_t elapsed_ms = now_monotonic_ms() - run_state.start_time_ms;
    log_state_timing_breakdown(state, &run_state, elapsed_ms);

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_ptr, JNI_ABORT);

    if (run_state.timed_out) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_ERROR,
                "stream chunk=%d timed out after %lldms",
                chunk_index,
                (long long) elapsed_ms
        );
        return RESULT_TIMEOUT;
    }
    if (run_state.cancelled) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_WARN,
                "stream chunk=%d cancelled after %lldms",
                chunk_index,
                (long long) elapsed_ms
        );
        return RESULT_CANCELLED;
    }
    if (result != 0) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_ERROR,
                "stream chunk=%d failed with code %d after %lldms",
                chunk_index,
                result,
                (long long) elapsed_ms
        );
        return result;
    }

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "stream chunk=%d finished successfully: elapsed=%lldms segments=%d",
            chunk_index,
            (long long) elapsed_ms,
            whisper_full_n_segments_from_state(state)
    );
    return 0;
}

static jint run_full_parallel(
        JNIEnv *env,
        struct whisper_context *context,
        jint num_threads,
        jfloatArray audio_data,
        jboolean include_timestamps,
        jint timeout_ms) {
    if (context == NULL || audio_data == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Invalid context or audio buffer");
        return -1;
    }

    jfloat *audio_data_ptr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (audio_data_ptr == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Failed to access audio buffer");
        return -1;
    }

    const jsize audio_length = (*env)->GetArrayLength(env, audio_data);
    struct whisper_full_params params;
    apply_tiny_params(context, &params, num_threads, include_timestamps == JNI_TRUE, false);
    params.single_segment = false;

    struct transcribe_run_state run_state = {
            .context = context,
            .timeout_ms = timeout_ms > 0 ? timeout_ms : 0,
            .last_logged_progress = -5,
            .start_time_ms = now_monotonic_ms(),
            .chunk_index = 0,
            .chunk_start_ms = 0,
            .audio_samples = (int) audio_length,
            .streaming = false,
            .timed_out = false,
            .cancelled = false,
    };

    params.progress_callback = android_whisper_progress_callback;
    params.progress_callback_user_data = &run_state;
    params.new_segment_callback = android_whisper_new_segment_callback;
    params.new_segment_callback_user_data = &run_state;
    params.encoder_begin_callback = android_whisper_encoder_begin_callback;
    params.encoder_begin_callback_user_data = &run_state;
    params.abort_callback = android_whisper_abort_callback;
    params.abort_callback_user_data = &run_state;

    set_cancel_requested(context, false);
    whisper_reset_timings(context);
    log_run_start(context, &params, audio_length, 0, 0, false);

    // Keep n_processors=1 so the app uses the parallel entry point without splitting short
    // mobile utterances across boundaries that would hurt whisper-tiny accuracy.
    int result = whisper_full_parallel(context, params, audio_data_ptr, (int) audio_length, 1);
    int64_t elapsed_ms = now_monotonic_ms() - run_state.start_time_ms;
    whisper_print_timings(context);

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_ptr, JNI_ABORT);

    if (run_state.timed_out) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_ERROR,
                "batch transcription timed out after %lldms",
                (long long) elapsed_ms
        );
        return RESULT_TIMEOUT;
    }
    if (run_state.cancelled) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_WARN,
                "batch transcription cancelled after %lldms",
                (long long) elapsed_ms
        );
        return RESULT_CANCELLED;
    }
    if (result != 0) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_ERROR,
                "batch transcription failed with code %d after %lldms",
                result,
                (long long) elapsed_ms
        );
        return result;
    }

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "batch transcription finished successfully: elapsed=%lldms segments=%d",
            (long long) elapsed_ms,
            whisper_full_n_segments(context)
    );
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_initContext(JNIEnv *env, jclass clazz, jstring model_path_str) {
    (void) clazz;

    ensure_bridge_initialized(env);

    if (model_path_str == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Model path was null");
        return 0;
    }

    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    if (model_path == NULL) {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Failed to access model path");
        return 0;
    }

    dispatch_native_logf(GGML_LOG_LEVEL_INFO, "initContext model=%s", model_path);

    struct whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;
    context_params.dtw_token_timestamps = false;
    context_params.dtw_aheads_preset = WHISPER_AHEADS_NONE;
    context_params.dtw_n_top = 0;
    context_params.dtw_mem_size = 0;

    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, context_params);

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (context != NULL) {
        register_context_control(context);
        dispatch_native_logf(
                GGML_LOG_LEVEL_INFO,
                "context initialized: %p multilingual=%s tiny_audio_ctx=%d",
                (void *) context,
                whisper_is_multilingual(context) ? "true" : "false",
                tiny_audio_context(context)
        );
        dispatch_native_log(
                GGML_LOG_LEVEL_INFO,
                "tiny preset active: language=ja, translate=false, threads<=2, audio_ctx=256, single_segment streaming"
        );
        dispatch_native_log(
                GGML_LOG_LEVEL_INFO,
                "bundled whisper.cpp has no speed_up flag; using the lightweight tiny preset instead"
        );
        dispatch_native_log(GGML_LOG_LEVEL_INFO, whisper_print_system_info());
    } else {
        dispatch_native_log(GGML_LOG_LEVEL_ERROR, "Failed to initialize whisper context");
    }
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_initState(JNIEnv *env, jclass clazz, jlong context_ptr) {
    (void) env;
    (void) clazz;

    if (context_ptr == 0) {
        return 0;
    }

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    struct whisper_state *state = whisper_init_state(context);
    if (state == NULL) {
        dispatch_native_logf(
                GGML_LOG_LEVEL_ERROR,
                "Failed to allocate whisper state for context=%p",
                (void *) context
        );
        return 0;
    }

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "state initialized: context=%p state=%p",
            (void *) context,
            (void *) state
    );
    return (jlong) state;
}

JNIEXPORT void JNICALL
Java_com_micklab_whisper_WhisperJni_freeState(JNIEnv *env, jclass clazz, jlong state_ptr) {
    (void) env;
    (void) clazz;

    if (state_ptr == 0) {
        return;
    }

    dispatch_native_logf(
            GGML_LOG_LEVEL_INFO,
            "freeState state=%p",
            (void *) (intptr_t) state_ptr
    );
    whisper_free_state((struct whisper_state *) state_ptr);
}

JNIEXPORT void JNICALL
Java_com_micklab_whisper_WhisperJni_freeContext(JNIEnv *env, jclass clazz, jlong context_ptr) {
    (void) env;
    (void) clazz;

    if (context_ptr != 0) {
        struct whisper_context *context = (struct whisper_context *) context_ptr;
        dispatch_native_logf(GGML_LOG_LEVEL_INFO, "freeContext context=%p", (void *) context);
        set_cancel_requested(context, true);
        whisper_free(context);
        unregister_context_control(context);
    }
}

JNIEXPORT void JNICALL
Java_com_micklab_whisper_WhisperJni_cancelTranscription(JNIEnv *env, jclass clazz, jlong context_ptr) {
    (void) env;
    (void) clazz;

    if (context_ptr == 0) {
        return;
    }

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    set_cancel_requested(context, true);
    dispatch_native_logf(GGML_LOG_LEVEL_WARN, "cancel requested for context=%p", (void *) context);
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_fullTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint num_threads,
        jfloatArray audio_data,
        jboolean include_timestamps,
        jint timeout_ms) {
    (void) clazz;

    ensure_bridge_initialized(env);
    return run_full_parallel(
            env,
            (struct whisper_context *) context_ptr,
            num_threads,
            audio_data,
            include_timestamps,
            timeout_ms
    );
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_streamTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jlong state_ptr,
        jint num_threads,
        jint chunk_index,
        jlong chunk_start_ms,
        jfloatArray audio_data,
        jboolean include_timestamps,
        jint timeout_ms) {
    (void) clazz;

    ensure_bridge_initialized(env);
    return run_with_state(
            env,
            (struct whisper_context *) context_ptr,
            (struct whisper_state *) state_ptr,
            num_threads,
            audio_data,
            include_timestamps,
            timeout_ms,
            chunk_index,
            chunk_start_ms
    );
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentCount(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr) {
    (void) env;
    (void) clazz;
    if (context_ptr == 0) {
        return 0;
    }
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegment(
        JNIEnv *env,
        jclass clazz,
        jlong context_ptr,
        jint index) {
    (void) clazz;

    const char *segment = context_ptr == 0
            ? ""
            : whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
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
    if (context_ptr == 0) {
        return 0;
    }
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
    if (context_ptr == 0) {
        return 0;
    }
    return whisper_full_get_segment_t1((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jint JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentCountFromState(
        JNIEnv *env,
        jclass clazz,
        jlong state_ptr) {
    (void) env;
    (void) clazz;
    if (state_ptr == 0) {
        return 0;
    }
    return whisper_full_n_segments_from_state((struct whisper_state *) state_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentFromState(
        JNIEnv *env,
        jclass clazz,
        jlong state_ptr,
        jint index) {
    (void) clazz;

    const char *segment = state_ptr == 0
            ? ""
            : whisper_full_get_segment_text_from_state((struct whisper_state *) state_ptr, index);
    if (segment == NULL) {
        segment = "";
    }
    return (*env)->NewStringUTF(env, segment);
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentT0FromState(
        JNIEnv *env,
        jclass clazz,
        jlong state_ptr,
        jint index) {
    (void) env;
    (void) clazz;
    if (state_ptr == 0) {
        return 0;
    }
    return whisper_full_get_segment_t0_from_state((struct whisper_state *) state_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_micklab_whisper_WhisperJni_getTextSegmentT1FromState(
        JNIEnv *env,
        jclass clazz,
        jlong state_ptr,
        jint index) {
    (void) env;
    (void) clazz;
    if (state_ptr == 0) {
        return 0;
    }
    return whisper_full_get_segment_t1_from_state((struct whisper_state *) state_ptr, index);
}
