package io.github.alexglezfelix.metalmind

/** Las funciones de `whisper_jni.cpp`. Úsalas a través de [WhisperTranscriber]. */
internal object WhisperNative {
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    /** Carga las variantes de CPU de ggml que haya en [dir] y se queda con la mejor. */
    @JvmStatic external fun loadBackends(dir: String)

    /** El modelo en [path], o 0 si no se pudo cargar. */
    @JvmStatic external fun open(path: String): Long

    @JvmStatic external fun close(handle: Long)

    /** Corta la transcripción en curso de [handle]; se puede llamar desde otro hilo. */
    @JvmStatic external fun cancel(handle: Long)

    /** El texto en UTF-8, un segmento por línea, o `null` si se canceló o falló. */
    @JvmStatic external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        listener: ProgressListener?,
    ): ByteArray?
}
