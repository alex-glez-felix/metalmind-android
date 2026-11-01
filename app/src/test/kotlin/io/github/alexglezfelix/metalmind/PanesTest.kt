package io.github.alexglezfelix.metalmind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanesTest {
    @Test
    fun `un teléfono tiene un panel, también acostado`() {
        assertFalse(Panes.two(widthDp = 411, heightDp = 891))
        assertFalse(Panes.two(widthDp = 891, heightDp = 411))
    }

    @Test
    fun `una tablet tiene dos en las dos orientaciones`() {
        assertTrue(Panes.two(widthDp = 1200, heightDp = 780))
        assertTrue(Panes.two(widthDp = 780, heightDp = 1200))
    }

    @Test
    fun `los cortes son los de Material`() {
        assertTrue(Panes.two(widthDp = 600, heightDp = 480))
        assertFalse(Panes.two(widthDp = 599, heightDp = 480))
        assertFalse(Panes.two(widthDp = 600, heightDp = 479))
    }

    @Test
    fun `la lista ocupa dos quintos, entre 280 y 420 dp`() {
        assertEquals(320, Panes.listWidthDp(800))
        assertEquals(280, Panes.listWidthDp(600))
        assertEquals(420, Panes.listWidthDp(1400))
    }

    @Test
    fun `las flechas recorren los resultados y se detienen en los extremos`() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", Panes.step(ids, "a", 1))
        assertEquals("a", Panes.step(ids, "b", -1))
        assertEquals("c", Panes.step(ids, "c", 1))
        assertEquals("a", Panes.step(ids, "a", -1))
    }

    @Test
    fun `sin elegido, o con uno que ya no está, empieza por el primero`() {
        val ids = listOf("a", "b", "c")
        assertEquals("a", Panes.step(ids, null, 1))
        assertEquals("a", Panes.step(ids, null, -1))
        assertEquals("a", Panes.step(ids, "z", 1))
        assertNull(Panes.step(emptyList(), null, 1))
    }
}
