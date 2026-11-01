package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkCompletionTest {
    @Test
    fun `el link que se está escribiendo`() {
        assertEquals(LinkQuery(4, 8, "ru"), LinkCompletion.at("ver [[ru", 8))
        assertEquals(LinkQuery(0, 2, ""), LinkCompletion.at("[[", 2))
        assertEquals(LinkQuery(0, 13, "proyectos/r"), LinkCompletion.at("[[proyectos/rust y", 13))
        assertEquals(LinkQuery(2, 17, "reunión "), LinkCompletion.at("a [[reunión del]] b", 12))
    }

    @Test
    fun `fuera de un link no hay sugerencias`() {
        assertNull(LinkCompletion.at("idea", 4))
        assertNull(LinkCompletion.at("[x", 2))
        assertNull(LinkCompletion.at("[[rust]] ", 9))
        assertNull(LinkCompletion.at("[[rust|alias", 12))
        assertNull(LinkCompletion.at("[[rust#enc", 10))
        assertNull(LinkCompletion.at("[[uno\ndos", 9))
        assertNull(LinkCompletion.at("[[[x", 4))
        assertNull(LinkCompletion.at("", 0))
    }

    @Test
    fun `aceptar escribe el link cerrado con el cursor detrás`() {
        val text = "ver [[ru"
        val done = LinkCompletion.accept(text, LinkCompletion.at(text, 8)!!, "Rust")
        assertEquals(Completed("ver [[Rust]]", 12), done)
    }

    @Test
    fun `aceptar en medio del texto no se come lo que sigue`() {
        val text = "ver [[ru y mañana"
        val done = LinkCompletion.accept(text, LinkCompletion.at(text, 8)!!, "Rust")
        assertEquals(Completed("ver [[Rust]] y mañana", 12), done)
    }

    @Test
    fun `aceptar en un link ya cerrado no duplica los corchetes`() {
        val text = "ver [[ru]] hoy"
        val done = LinkCompletion.accept(text, LinkCompletion.at(text, 8)!!, "proyectos/rust")
        assertEquals(Completed("ver [[proyectos/rust]] hoy", 22), done)
    }
}
