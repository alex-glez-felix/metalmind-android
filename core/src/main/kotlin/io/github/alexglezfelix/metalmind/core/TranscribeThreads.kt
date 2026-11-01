package io.github.alexglezfelix.metalmind.core

/**
 * Cuántos hilos darle a whisper. Cada paso de ggml reparte el trabajo entre
 * los hilos y espera al más lento: un hilo en un núcleo pequeño (Cortex-A5xx)
 * frena a todos los que van en los grandes. Por eso se usan solo los núcleos
 * que no son los más lentos del procesador.
 */
object TranscribeThreads {
    /** Más hilos ya no aceleran: se estorban por la memoria. */
    const val MAX = 8

    /** Sin saber qué núcleos hay: lo que andaba bien antes en un teléfono. */
    const val FALLBACK_MAX = 4

    /**
     * [maxFreqsKHz]: la frecuencia máxima de cada núcleo
     * (`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`), `null` si no
     * se pudo leer. [available]: los núcleos que da la JVM.
     */
    fun choose(maxFreqsKHz: List<Long?>, available: Int): Int {
        val freqs = maxFreqsKHz.filterNotNull().filter { it > 0 }
        // Sin los datos de todos, no se sabe cuáles son los pequeños.
        if (freqs.isEmpty() || freqs.size != maxFreqsKHz.size) return available.coerceIn(1, FALLBACK_MAX)
        val slowest = freqs.min()
        val fast = freqs.count { it > slowest }
        // Todos iguales (un emulador, una CPU sin núcleos pequeños): todos cuentan.
        val usable = if (fast == 0) freqs.size else fast
        return usable.coerceIn(1, minOf(MAX, available.coerceAtLeast(1)))
    }
}
