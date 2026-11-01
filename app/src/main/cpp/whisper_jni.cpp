// El puente entre WhisperNative.kt y whisper.cpp.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

/** Un modelo cargado y la bandera para cortar la transcripción en curso. */
struct Handle {
    whisper_context * ctx;
    std::atomic<bool> cancel{false};
};

struct ProgressSink {
    JNIEnv * env;
    jobject listener;
    jmethodID method;
};

std::string utf8(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/** Lo que ggml y whisper escriben en stderr, a logcat (stderr no llega a ningún lado). */
void log_to_logcat(ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, "whisper", text);
}

}  // namespace

/**
 * Carga la mejor variante de CPU de ggml y devuelve, para el log, si el código
 * nativo se compiló optimizado (sin optimizar, whisper va decenas de veces más
 * lento: issue #17) y qué instrucciones usa la variante elegida. El nombre del
 * archivo de la variante lo escribe ggml en el log ("load_backend: loaded CPU
 * backend from …").
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_alexglezfelix_metalmind_WhisperNative_loadBackends(JNIEnv * env, jclass, jstring dir) {
    ggml_log_set(log_to_logcat, nullptr);
    whisper_log_set(log_to_logcat, nullptr);
    ggml_backend_load_all_from_path(utf8(env, dir).c_str());

#if defined(__OPTIMIZE__)
    std::string info = "optimizado";
#else
    std::string info = "SIN OPTIMIZAR";
#endif
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu == nullptr) return env->NewStringUTF((info + ", sin CPU de ggml").c_str());
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(cpu);
    info += ", ";
    info += ggml_backend_dev_description(cpu);
    auto features = reinterpret_cast<ggml_backend_get_features_t>(
        ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features"));
    if (features != nullptr) {
        info += ":";
        for (const ggml_backend_feature * f = features(reg); f->name != nullptr; ++f) {
            info += ' ';
            info += f->name;
            if (std::string(f->value) != "1") {
                info += '=';
                info += f->value;
            }
        }
    }
    // La descripción viene del sistema: con algo que no sea ASCII,
    // NewStringUTF podría abortar (ver transcribe).
    for (char & c : info) {
        if (static_cast<unsigned char>(c) >= 0x80) c = '?';
    }
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_alexglezfelix_metalmind_WhisperNative_open(JNIEnv * env, jclass, jstring path) {
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(utf8(env, path).c_str(), params);
    if (ctx == nullptr) return 0;
    return reinterpret_cast<jlong>(new Handle{ctx});
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_alexglezfelix_metalmind_WhisperNative_close(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_alexglezfelix_metalmind_WhisperNative_cancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Handle *>(handle)->cancel.store(true);
}

/**
 * Transcribe [samples] (16 kHz, mono, de -1 a 1). Devuelve el texto en UTF-8,
 * un segmento por línea, o `null` si se canceló o falló. Van bytes y no un
 * jstring: NewStringUTF espera UTF-8 "modificado" y aborta con un emoji o con
 * un carácter que whisper haya partido entre dos segmentos. [listener] recibe el avance (0–100)
 * en este mismo hilo.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_alexglezfelix_metalmind_WhisperNative_transcribe(
    JNIEnv * env, jclass, jlong handle, jfloatArray samples, jstring language, jint threads, jobject listener) {
    auto * h = reinterpret_cast<Handle *>(handle);
    h->cancel.store(false);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    std::string lang = utf8(env, language);
    params.language = lang.c_str();
    params.n_threads = std::max(1, static_cast<int>(threads));
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    // Sin "[Música]", "(risas)" ni otros tokens que no son habla.
    params.suppress_nst = true;

    ProgressSink sink{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        sink.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        params.progress_callback = [](whisper_context *, whisper_state *, int progress, void * data) {
            auto * s = static_cast<ProgressSink *>(data);
            if (s->env->ExceptionCheck()) return;
            s->env->CallVoidMethod(s->listener, s->method, static_cast<jint>(progress));
        };
        params.progress_callback_user_data = &sink;
    }
    params.abort_callback = [](void * data) { return static_cast<Handle *>(data)->cancel.load(); };
    params.abort_callback_user_data = h;

    jsize n = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(h->ctx, params, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (env->ExceptionCheck()) return nullptr;
    if (rc != 0 || h->cancel.load()) return nullptr;

    std::string text;
    int segments = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < segments; ++i) {
        if (i > 0) text += '\n';
        text += whisper_full_get_segment_text(h->ctx, i);
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}
