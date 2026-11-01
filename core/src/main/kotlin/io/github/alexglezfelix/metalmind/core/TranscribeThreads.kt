package io.github.alexglezfelix.metalmind.core

/**
 * Cuántos hilos darle a whisper. Cada paso de ggml reparte el trabajo entre
 * los hilos y espera al más lento: un hilo en un núcleo pequeño (Cortex-A5xx)
 * frena a todos los que van en los grandes. Por eso se dejan fuera los núcleos
 * mucho más lentos que el más rápido. Por frecuencia relativa, no "todo lo que
 * no sea el más lento": un Snapdragon 8 Elite no tiene núcleos pequeños, solo
 * dos grupos de grandes a 4.32 y 3.53 GHz, y los ocho sirven.
 */
object TranscribeThreads {
    /** Más hilos ya no aceleran: se estorban por la memoria. */
    const val MAX = 8

    /** Sin saber qué núcleos hay: lo que andaba bien antes en un teléfono. */
    const val FALLBACK_MAX = 4

    /**
     * Un núcleo cuenta si llega al menos a esta fracción del más rápido. Los
     * pequeños (A5xx) andan por 0.6–0.7 del grande; los medianos (A7xx), por
     * 0.8–0.9.
     */
    const val MIN_RELATIVE_FREQ = 0.75

    /**
     * [maxFreqsKHz]: la frecuencia máxima de cada núcleo
     * (`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`), `null` si no
     * se pudo leer. [available]: los núcleos que da la JVM.
     */
    fun choose(maxFreqsKHz: List<Long?>, available: Int): Int {
        val freqs = maxFreqsKHz.filterNotNull().filter { it > 0 }
        // Sin los datos de todos, no se sabe cuáles son los pequeños.
        if (freqs.isEmpty() || freqs.size != maxFreqsKHz.size) return available.coerceIn(1, FALLBACK_MAX)
        val fastest = freqs.max()
        val usable = freqs.count { it >= fastest * MIN_RELATIVE_FREQ }
        return usable.coerceIn(1, minOf(MAX, available.coerceAtLeast(1)))
    }
}
