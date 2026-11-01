package io.github.alexglezfelix.metalmind.core

import java.io.IOException
import kotlin.math.sqrt

/** El micrófono: PCM de 16 bits, mono, a [VoiceRecorder.SAMPLE_RATE]. */
interface PcmSource {
    fun start()

    /** Llena [buffer] y devuelve cuántas muestras leyó, o un número negativo si falló. */
    fun read(buffer: ShortArray): Int

    fun stop()

    /** Suelta el micrófono. Después no se vuelve a usar. */
    fun release()
}

/**
 * Graba de [source] hasta [stop], [cancel] o [maxSeconds]. [record] bloquea el
 * hilo que la llama; [pause], [resume], [stop] y [cancel] se llaman desde otro.
 * En pausa el micrófono se detiene (se apaga el indicador del sistema) y no se
 * guarda nada.
 */
class VoiceRecorder(private val source: PcmSource, maxSeconds: Int = MAX_SECONDS) {
    private val maxSamples = SAMPLE_RATE * maxSeconds
    private val lock = Object()
    private var paused = false
    private var stopped = false
    private var cancelled = false

    fun pause() = update { paused = true }

    fun resume() = update { paused = false }

    fun stop() = update { stopped = true }

    fun cancel() = update { cancelled = true }

    private inline fun update(change: () -> Unit) = synchronized(lock) {
        change()
        lock.notifyAll()
    }

    /**
     * Graba y devuelve las muestras, o `null` si se canceló. [onChunk] recibe,
     * tras cada lectura, las muestras grabadas hasta ahora y el nivel (0–1) del
     * último trozo.
     */
    fun record(onChunk: (samples: Int, level: Float) -> Unit = { _, _ -> }): ShortArray? {
        var out = ShortArray(SAMPLE_RATE * 10)
        var size = 0
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var running = false
        try {
            while (size < maxSamples) {
                synchronized(lock) {
                    while (paused && !stopped && !cancelled) {
                        if (running) {
                            source.stop()
                            running = false
                        }
                        lock.wait()
                    }
                }
                if (synchronized(lock) { stopped || cancelled }) break
                if (!running) {
                    source.start()
                    running = true
                }
                val n = source.read(buffer)
                if (n < 0) throw IOException("el micrófono falló ($n)")
                val take = minOf(n, maxSamples - size)
                if (size + take > out.size) out = out.copyOf(maxOf(out.size * 2, size + take))
                buffer.copyInto(out, size, 0, take)
                size += take
                onChunk(size, Pcm.level(buffer, take))
            }
        } finally {
            if (running) source.stop()
            source.release()
        }
        return if (synchronized(lock) { cancelled }) null else out.copyOf(size)
    }

    companion object {
        /** Lo que espera whisper. */
        const val SAMPLE_RATE = 16_000

        /** Diez minutos: unos 20 MB en memoria, y la transcripción no se eterniza. */
        const val MAX_SECONDS = 600
    }
}

/** Cuentas sobre audio PCM de 16 bits. */
object Pcm {
    /** De -1 a 1, como lo pide whisper. */
    fun toFloats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }

    /** La raíz cuadrática media de [count] muestras desde [from], de 0 a 1. */
    fun level(samples: ShortArray, count: Int = samples.size, from: Int = 0): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in from until from + count) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / count).toFloat()
    }

    /**
     * Si en ningún tramo de 100 ms hay algo más fuerte que un susurro lejano
     * (−40 dBFS). Así no se le pasa silencio a whisper, que con silencio inventa
     * frases ("Subtítulos realizados por la comunidad de Amara.org").
     */
    fun isSilent(samples: ShortArray, sampleRate: Int = VoiceRecorder.SAMPLE_RATE): Boolean {
        val window = sampleRate / 10
        var start = 0
        while (start < samples.size) {
            val count = minOf(window, samples.size - start)
            if (level(samples, count, start) > SILENCE) return false
            start += window
        }
        return true
    }

    private const val SILENCE = 0.01f
}
