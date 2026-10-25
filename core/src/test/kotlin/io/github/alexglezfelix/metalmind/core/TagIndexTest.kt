package io.github.alexglezfelix.metalmind.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TagIndexTest {
    private fun index(vararg notes: String): TagIndex =
        TagIndex.EMPTY.refresh(notes.indices.map { NoteFile("n$it", 1, 1) }) { notes[it.id.drop(1).toInt()] }

    @Test
    fun `de más notas a menos con la forma más usada`() {
        val index = index("#Rust #Rust #rust", "#rust #gpui", "#Rust", "#gpui #zig")
        assertEquals(
            listOf(TagSuggestion("Rust", 3), TagSuggestion("gpui", 2), TagSuggestion("zig", 1)),
            index.suggestions(""),
        )
    }

    @Test
    fun `con las mismas apariciones gana el primero en bytes`() {
        assertEquals(listOf(TagSuggestion("Rust", 2)), index("#rust", "#Rust").suggestions(""))
    }

    @Test
    fun `NFC y mayúsculas son el mismo tag, los acentos no`() {
        val index = index("#canción", "#canción", "#CANCIO\u0301N", "#cancion")
        assertEquals(listOf(TagSuggestion("canción", 3), TagSuggestion("cancion", 1)), index.suggestions("CAN"))
    }

    @Test
    fun `con las mismas notas, por nombre sin mayúsculas`() {
        val index = index("#Zig #apple #Banana")
        assertEquals(listOf("apple", "Banana", "Zig"), index.suggestions("").map { it.name })
    }

    @Test
    fun `primero los que empiezan así y después los que lo contienen`() {
        val index = index("#trust", "#rust #rust-async", "#rust", "#pruebas")
        assertEquals(listOf("rust", "rust-async", "pruebas", "trust"), index.suggestions("ru").map { it.name })
        assertEquals(emptyList(), index.suggestions("xyz"))
    }

    @Test
    fun `los tags solo de números cuentan`() {
        assertEquals(listOf(TagSuggestion("2024", 2)), index("#2024", "---\ntags: [2024]\n---\n").suggestions("20"))
    }

    @Test
    fun `solo relee lo que cambió`() {
        val read = mutableListOf<String>()
        val texts = mutableMapOf("a" to "#uno", "b" to "#dos")
        fun reader(file: NoteFile): String? = texts[file.id].also { read += file.id }

        val first = TagIndex.EMPTY.refresh(listOf(NoteFile("a", 1, 4), NoteFile("b", 1, 4)), ::reader)
        assertEquals(listOf("a", "b"), read)

        read.clear()
        assertSame(first, first.refresh(listOf(NoteFile("a", 1, 4), NoteFile("b", 1, 4)), ::reader))
        assertEquals(emptyList(), read)

        texts["b"] = "#tres"
        val second = first.refresh(listOf(NoteFile("a", 1, 4), NoteFile("b", 2, 5)), ::reader)
        assertEquals(listOf("b"), read)
        assertEquals(listOf("tres", "uno"), second.suggestions("").map { it.name })

        read.clear()
        val third = second.refresh(listOf(NoteFile("b", 2, 5)), ::reader)
        assertEquals(emptyList(), read)
        assertEquals(listOf("tres"), third.suggestions("").map { it.name })
    }

    @Test
    fun `lo que no se pudo leer se reintenta`() {
        val first = TagIndex.EMPTY.refresh(listOf(NoteFile("a", 1, 1))) { null }
        assertEquals(0, first.noteCount)
        val second = first.refresh(listOf(NoteFile("a", 1, 1))) { "#ok" }
        assertEquals(listOf("ok"), second.suggestions("").map { it.name })
    }

    @Test
    fun `la caché guarda y lee el mismo índice`() {
        val index = index("#Rust #año", "#rust", "---\ntags: [2024]\n---\n")
        val bytes = ByteArrayOutputStream().also(index::write).toByteArray()
        val read = TagIndex.read(ByteArrayInputStream(bytes))
        assertEquals(index.suggestions(""), read.suggestions(""))
        assertEquals(3, read.noteCount)
        // Leída, no relee nada que no haya cambiado.
        assertSame(read, read.refresh(listOf(NoteFile("n0", 1, 1), NoteFile("n1", 1, 1), NoteFile("n2", 1, 1))) { error("no") })
    }

    @Test
    fun `una caché rota o de otro formato queda vacía`() {
        assertEquals(0, TagIndex.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, 0))).noteCount)
        // Conteo negativo y conteo enorme.
        assertEquals(0, TagIndex.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, -1, -1, -1, -1))).noteCount)
        assertEquals(0, TagIndex.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, 127, -1, -1, -1))).noteCount)
        assertEquals(0, TagIndex.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 99))).noteCount)
        assertEquals(0, TagIndex.read(ByteArrayInputStream(byteArrayOf())).noteCount)
    }

    @Test
    fun `miles de notas`() {
        val listing = (0 until 5000).map { NoteFile("n$it", 1, 1) }
        val started = System.nanoTime()
        val index = TagIndex.EMPTY.refresh(listing) { "# Nota ${it.id}\n#tag${it.id.drop(1).toInt() % 300} #común\ntexto ".repeat(3) }
        val suggestions = index.suggestions("tag1")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(TagSuggestion("común", 5000), index.suggestions("").first())
        assertEquals(20, suggestions.size)
        assert(millis < 5000) { "tardó $millis ms" }
    }
}
