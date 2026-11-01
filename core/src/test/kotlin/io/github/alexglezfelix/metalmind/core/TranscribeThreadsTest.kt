package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscribeThreadsTest {
    @Test
    fun `sin los núcleos pequeños de un Snapdragon 8 Gen 3`() {
        // 2× A520 a 2.27 GHz, 3× A720 a 3.15, 2× A720 a 2.96, 1× X4 a 3.3.
        val freqs = listOf(2265600L, 2265600, 3148800, 3148800, 3148800, 2956800, 2956800, 3302400)
        assertEquals(6, TranscribeThreads.choose(freqs, available = 8))
    }

    @Test
    fun `sin los núcleos pequeños de un Exynos 2400`() {
        // 4× A520 a 1.95 GHz, 3× A720 a 2.6, 2× A720 a 2.9, 1× X4 a 3.21.
        val freqs = List(4) { 1950000L } + List(3) { 2600000L } + List(2) { 2900000L } + 3207000L
        assertEquals(6, TranscribeThreads.choose(freqs, available = 10))
    }

    @Test
    fun `un clásico 4 grandes y 4 pequeños usa los 4 grandes`() {
        assertEquals(4, TranscribeThreads.choose(List(4) { 1800000L } + List(4) { 2800000L }, available = 8))
    }

    @Test
    fun `todos iguales cuentan todos, hasta el máximo`() {
        assertEquals(4, TranscribeThreads.choose(List(4) { 2000000L }, available = 4))
        assertEquals(TranscribeThreads.MAX, TranscribeThreads.choose(List(16) { 2000000L }, available = 16))
    }

    @Test
    fun `nunca más hilos que núcleos disponibles`() {
        // La app puede tener menos núcleos que los que lista el sistema (cpuset).
        assertEquals(3, TranscribeThreads.choose(List(2) { 1000000L } + List(6) { 3000000L }, available = 3))
    }

    @Test
    fun `sin poder leer las frecuencias, hasta cuatro`() {
        assertEquals(4, TranscribeThreads.choose(emptyList(), available = 8))
        assertEquals(2, TranscribeThreads.choose(emptyList(), available = 2))
        assertEquals(1, TranscribeThreads.choose(emptyList(), available = 0))
        assertEquals(4, TranscribeThreads.choose(listOf(1000000L, null, 3000000L), available = 8))
    }
}
