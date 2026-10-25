package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagCompletionTest {
    @Test
    fun `el tag que se está escribiendo`() {
        assertEquals(TagQuery(5, 8, "ru"), TagCompletion.at("idea #ru", 8))
        assertEquals(TagQuery(0, 1, ""), TagCompletion.at("#", 1))
        assertEquals(TagQuery(0, 5, "r"), TagCompletion.at("#rust y", 2))
        assertEquals(TagQuery(3, 7, "añ"), TagCompletion.at("x\n #año.", 6))
    }

    @Test
    fun `fuera de un tag no hay sugerencias`() {
        assertNull(TagCompletion.at("idea", 4))
        assertNull(TagCompletion.at("texto#algo", 10))
        assertNull(TagCompletion.at("#rust ", 6))
        assertNull(TagCompletion.at("#a/b", 4))
        assertNull(TagCompletion.at("", 0))
    }

    @Test
    fun `aceptar escribe la forma establecida y un espacio`() {
        val text = "idea #ru"
        val done = TagCompletion.accept(text, TagCompletion.at(text, 8)!!, "Rust")
        assertEquals(Completed("idea #Rust ", 11), done)
    }

    @Test
    fun `aceptar en medio reemplaza el tag entero`() {
        val text = "#ru más"
        val done = TagCompletion.accept(text, TagCompletion.at(text, 2)!!, "Rust")
        assertEquals(Completed("#Rust más", 6), done)
    }
}
