package io.github.alexglezfelix.metalmind

import android.util.Log
import io.github.alexglezfelix.metalmind.core.TranscribeThreads
import io.github.alexglezfelix.metalmind.core.Transcriber
import io.github.alexglezfelix.metalmind.core.VoiceRecorder
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Transcribe con whisper.cpp y el modelo que dé [model] en ese momento. Lo
 * carga en cada transcripción y lo suelta al terminar: una captura rápida no
 * debe quedarse con cientos de MB de memoria mientras no se usa.
 */
class WhisperTranscriber(
    private val model: () -> File?,
    private val nativeLibraryDir: String,
    /** Para medir con otro número de hilos (WhisperDeviceTest); `null`, los de [threads]. */
    private val threadsOverride: Int? = null,
) : Transcriber {
    @Volatile private var handle = 0L

    @Volatile private var cancelled = false

    override fun transcribe(samples: FloatArray, onProgress: (Int) -> Unit): String? {
        cancelled = false
        val file = model() ?: throw IOException("no hay modelo")
        loadNative(nativeLibraryDir)
        val h = WhisperNative.open(file.path)
        if (h == 0L) throw IOException("no se pudo cargar el modelo ${file.name}")
        handle = h
        try {
            // Se pudo cancelar mientras cargaba: whisper ya no lo vería.
            if (cancelled) return null
            val count = threadsOverride ?: threads
            val start = System.nanoTime()
            val bytes = WhisperNative.transcribe(h, samples, LANGUAGE, count) { onProgress(it) } ?: return null
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "%.1f s de audio en %.1f s, %d hilos, %s",
                    samples.size.toDouble() / VoiceRecorder.SAMPLE_RATE,
                    (System.nanoTime() - start) / 1e9,
                    count,
                    file.name,
                ),
            )
            return String(bytes, Charsets.UTF_8)
        } finally {
            handle = 0L
            WhisperNative.close(h)
        }
    }

    override fun cancel() {
        cancelled = true
        val h = handle
        if (h != 0L) WhisperNative.cancel(h)
    }

    companion object {
        /**
         * Español fijo: con detección automática, una nota que empieza con una
         * palabra en inglés sale traducida o en inglés. Fijo en español, whisper
         * deja las palabras en inglés tal cual se dicen.
         */
        private const val LANGUAGE = "es"

        private const val TAG = "Whisper"

        /** Uno por núcleo, sin los pequeños: ver [TranscribeThreads]. */
        val threads: Int by lazy {
            val cores = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            val freqs = cores.map { core ->
                try {
                    File(core, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                } catch (_: IOException) {
                    null
                }
            }
            TranscribeThreads.choose(freqs, Runtime.getRuntime().availableProcessors()).also {
                Log.i(TAG, "$it hilos; frecuencias máximas (kHz): $freqs")
            }
        }

        /**
         * Cómo se compiló ggml y qué instrucciones tiene la variante de CPU que
         * eligió (en arm64, DOTPROD, MATMUL_INT8 = i8mm, SVE…); vacío hasta la
         * primera transcripción.
         */
        @Volatile var backend: String = ""
            private set

        @Volatile private var loaded = false

        @Synchronized
        private fun loadNative(dir: String) {
            if (loaded) return
            try {
                System.loadLibrary("metalmind_voz")
            } catch (e: UnsatisfiedLinkError) {
                // Un Error, no una Exception: sin convertirlo, cerraría la app.
                throw IOException("whisper no está disponible en este procesador", e)
            }
            backend = WhisperNative.loadBackends(dir)
            Log.i(TAG, "ggml: $backend")
            loaded = true
        }
    }
}
