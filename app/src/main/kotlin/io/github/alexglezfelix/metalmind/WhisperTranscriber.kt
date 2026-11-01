package io.github.alexglezfelix.metalmind

import io.github.alexglezfelix.metalmind.core.Transcriber
import java.io.File
import java.io.IOException

/**
 * Transcribe con whisper.cpp y el modelo que dé [model] en ese momento. Lo
 * carga en cada transcripción y lo suelta al terminar: una captura rápida no
 * debe quedarse con cientos de MB de memoria mientras no se usa.
 */
class WhisperTranscriber(private val model: () -> File?, private val nativeLibraryDir: String) : Transcriber {
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
            val bytes = WhisperNative.transcribe(h, samples, LANGUAGE, threads()) { onProgress(it) } ?: return null
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

        /** Más de cuatro hilos no acelera whisper en un teléfono: se estorban. */
        private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

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
            WhisperNative.loadBackends(dir)
            loaded = true
        }
    }
}
