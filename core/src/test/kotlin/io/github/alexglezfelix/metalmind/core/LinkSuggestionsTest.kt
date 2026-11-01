package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkSuggestionsTest {
    /** Un índice con notas vacías en [paths]; la primera es la más reciente. */
    private fun index(vararg paths: String): VaultIndex =
        VaultIndex.EMPTY.refresh(paths.mapIndexed { i, path -> NoteFile("n$i", (paths.size - i).toLong(), 1, path) }) { "" }

    @Test
    fun `sin nada escrito, las más recientes`() {
        val index = index("b.md", "a.md", "c.md")
        assertEquals(listOf("b", "a", "c"), index.linkSuggestions("").map { it.target })
        assertEquals(listOf("b"), index.linkSuggestions("", limit = 1).map { it.target })
    }

    @Test
    fun `primero las que empiezan así, luego por nombre y al final por ruta`() {
        val index = index("cosas de rust.md", "proyectos/zig.md", "Rust async.md", "rústico.md")
        assertEquals(
            listOf("Rust async", "rústico", "cosas de rust"),
            index.linkSuggestions("RUS").map { it.title },
        )
        assertEquals(listOf("zig"), index.linkSuggestions("proy").map { it.target })
    }

    @Test
    fun `el nombre solo si no empata`() {
        val index = index("proyectos/rust.md", "rust.md", "notas/Reunión del lunes.md")
        val byPath = index.linkSuggestions("").associate { it.title + "@" + it.folder to it.target }
        assertEquals(
            mapOf("rust@proyectos" to "proyectos/rust", "rust@" to "rust", "Reunión del lunes@notas" to "Reunión del lunes"),
            byPath,
        )
    }

    @Test
    fun `a la misma profundidad hace falta la carpeta, salvo si se escribe distinto`() {
        val index = index("a/x/rust.md", "b/x/rust.md", "c/Rust.md", "d/RUST.md")
        assertEquals(
            listOf("a/x/rust", "b/x/rust", "Rust", "RUST"),
            index.linkSuggestions("").map { it.target },
        )
    }

    @Test
    fun `entre las igual de hondas gana la escrita igual`() {
        val index = index("a/rust.md", "b/Rust.md", "c/x/rust.md")
        assertEquals(listOf("rust", "Rust", "x/rust"), index.linkSuggestions("").map { it.target })
    }

    @Test
    fun `lo sugerido lleva a la nota`() {
        val index = index("a/x/rust.md", "b/x/rust.md", "c/Rust.md", "d/RUST.md", "a/rust.md", "Notas.md")
        for (s in index.linkSuggestions("")) {
            val path = if (s.folder.isEmpty()) s.title else "${s.folder}/${s.title}"
            assertEquals("$path.md", index.resolve(s.target)?.path, s.target)
        }
    }

    @Test
    fun `sin md, en NFC y sin las que no caben en un link`() {
        val index = index("canción.MD", "a#b.md", "x|y.md", "[viejo].md")
        assertEquals(listOf(LinkSuggestion("canción", "", "canción")), index.linkSuggestions(""))
    }
}
