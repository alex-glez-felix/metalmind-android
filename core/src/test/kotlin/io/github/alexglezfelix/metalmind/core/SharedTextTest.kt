package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedTextTest {
    @Test
    fun `un link con título lleva el título en la primera línea`() {
        assertEquals(
            "Cómo hacer pan\nhttps://example.org/pan",
            SharedText.toNote("Cómo hacer pan", "https://example.org/pan"),
        )
        assertEquals("cómo-hacer-pan", NoteName.firstSlug(SharedText.toNote("Cómo hacer pan", "https://example.org/pan")))
    }

    @Test
    fun `sin asunto queda el texto tal cual`() {
        assertEquals("  una idea\ncon dos líneas  ", SharedText.toNote(null, "  una idea\ncon dos líneas  \n"))
        assertEquals("https://example.org", SharedText.toNote("  ", "https://example.org"))
    }

    @Test
    fun `el asunto no se repite si el texto ya lo trae`() {
        assertEquals(
            "Cómo hacer pan https://example.org/pan",
            SharedText.toNote("Cómo hacer pan", "Cómo hacer pan https://example.org/pan"),
        )
    }

    @Test
    fun `solo asunto o nada`() {
        assertEquals("Título", SharedText.toNote(" Título ", null))
        assertEquals("", SharedText.toNote(null, null))
    }
}
