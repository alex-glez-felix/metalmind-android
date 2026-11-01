package io.github.alexglezfelix.metalmind.core

/**
 * Un micrófono de mentira: cada lectura devuelve el siguiente trozo de
 * [chunks] y después corre [afterRead] con su número (desde 1), para pausar o
 * detener a mitad. Si se acaban los trozos, devuelve silencio. [events] anota
 * lo que la grabadora le pidió.
 */
class FakePcmSource(
    private val chunks: List<ShortArray>,
    private val afterRead: (read: Int) -> Unit = {},
) : PcmSource {
    val events = mutableListOf<String>()
    var onStop: () -> Unit = {}
    private var reads = 0

    override fun start() {
        events += "start"
    }

    override fun read(buffer: ShortArray): Int {
        val chunk = chunks.getOrNull(reads) ?: ShortArray(buffer.size)
        reads++
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        afterRead(reads)
        return n
    }

    override fun stop() {
        events += "stop"
        onStop()
    }

    override fun release() {
        events += "release"
    }

    companion object {
        /** 100 ms de un tono a [amplitude] (0–1), con el valor [mark] en la primera muestra. */
        fun tone(amplitude: Float = 0.3f, mark: Short? = null): ShortArray {
            val n = VoiceRecorder.SAMPLE_RATE / 10
            return ShortArray(n) { i ->
                if (i == 0 && mark != null) mark else ((if (i % 2 == 0) 1 else -1) * amplitude * 32767).toInt().toShort()
            }
        }
    }
}
