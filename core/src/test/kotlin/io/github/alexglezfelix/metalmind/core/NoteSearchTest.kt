package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteSearchTest {
    /** Notas `ruta` → `texto`; la primera es la más reciente. */
    private fun index(vararg notes: Pair<String, String>): VaultIndex {
        val listing = notes.mapIndexed { i, (path, _) -> NoteFile("id-$path", (notes.size - i).toLong(), 1, path) }
        return VaultIndex.EMPTY.refresh(listing) { file -> notes.first { it.first == file.path }.second }
    }

    private fun VaultIndex.titles(query: String) = search(query).map { it.title }

    @Test
    fun `cada palabra en el título o el cuerpo, en cualquier orden`() {
        val index = index(
            "reunión-del-lunes.md" to "Hablamos del presupuesto.",
            "ideas.md" to "Una idea sobre el presupuesto del lunes.",
            "otra.md" to "Nada que ver.",
        )
        assertEquals(listOf("reunión-del-lunes", "ideas"), index.titles("lunes presupuesto"))
        assertEquals(listOf("ideas"), index.titles("idea SOBRE"))
        assertEquals(emptyList(), index.titles("lunes martes"))
    }

    @Test
    fun `sin mayúsculas ni acentos`() {
        val index = index("a.md" to "La Canción de CUNA", "b.md" to "cancion", "c.md" to "Canción descompuesta")
        assertEquals(listOf("a", "b", "c"), index.titles("canción"))
        assertEquals(listOf("a", "b", "c"), index.titles("CANCION"))
        assertEquals(listOf("a"), index.titles("cuna"))
    }

    @Test
    fun `primero las del título, después por fecha`() {
        val index = index(
            "nuevo.md" to "algo de rust",
            "rust-async.md" to "futuros",
            "viejo.md" to "más rust",
            "rust.md" to "el lenguaje",
        )
        assertEquals(listOf("rust-async", "rust", "nuevo", "viejo"), index.titles("rust"))
        // Con alguna palabra en el título va antes que sin ninguna, aunque sea más vieja.
        val two = index("a.md" to "rust y zig", "rust.md" to "zig")
        assertEquals(listOf("rust", "a"), two.titles("rust zig"))
    }

    @Test
    fun `sin consulta, las más recientes`() {
        val index = index("b.md" to "uno", "a.md" to "dos", "c.md" to "tres")
        assertEquals(listOf("b", "a", "c"), index.titles(""))
        assertEquals(listOf("b", "a", "c"), index.titles("   # "))
        assertEquals(2, index.search("", limit = 2).size)
    }

    @Test
    fun `el filtro por tag usa las reglas de los tags`() {
        val index = index(
            "a.md" to "#Rust y más",
            "b.md" to "---\ntags: [rust, zig]\n---\ncuerpo",
            "c.md" to "`#rust` en código no cuenta",
            "d.md" to "#rustacean no es #rust-async",
            "e.md" to "#zig solo",
        )
        assertEquals(listOf("a", "b"), index.titles("#rust"))
        assertEquals(listOf("a", "b"), index.titles("#RUST"))
        assertEquals(listOf("b"), index.titles("#rust #zig"))
        assertEquals(listOf("b"), index.titles("#rust cuerpo"))
        assertEquals(emptyList(), index.titles("#nada"))
    }

    @Test
    fun `un # que no es tag se busca como texto`() {
        val index = index("a.md" to "Usé C# hoy", "b.md" to "nada")
        assertEquals(listOf("a"), index.titles("c#"))
        // `#-` no es un tag válido: se busca como texto, y no está.
        assertEquals(emptyList(), index.titles("#-"))
    }

    @Test
    fun `el frontmatter no se busca`() {
        val index = index("a.md" to "---\nfuente: libro\n---\ntexto", "b.md" to "un libro")
        assertEquals(listOf("b"), index.titles("libro"))
    }

    @Test
    fun `el fragmento es la línea de la primera coincidencia, resaltada`() {
        val index = index("a.md" to "# Título\n\nprimera línea\n- segunda con Café y café\n")
        val hit = index.search("cafe").single()
        assertEquals("segunda con Café y café", hit.snippet)
        assertEquals(listOf(12..15, 19..22), hit.highlights)
        assertEquals("Café", hit.snippet.substring(12, 16))
    }

    @Test
    fun `sin coincidencia en el cuerpo, la primera línea con texto`() {
        val index = index("rust.md" to "---\ntags: [x]\n---\n\n## Notas sobre Rust\nmás\n")
        val hit = index.search("rust").single()
        assertEquals("Notas sobre Rust", hit.snippet)
        assertEquals(listOf(0..3), hit.titleHighlights)
    }

    @Test
    fun `una línea larga se recorta alrededor de la coincidencia`() {
        val long = "a".repeat(300) + " aguja " + "b".repeat(300)
        val hit = index("a.md" to long).search("aguja").single()
        assert(hit.snippet.startsWith("…") && hit.snippet.endsWith("…")) { hit.snippet }
        val range = hit.highlights.single()
        assertEquals("aguja", hit.snippet.substring(range.first, range.last + 1))
        assert(hit.snippet.length <= 162) { "${hit.snippet.length}" }
    }

    @Test
    fun `el plegado conserva el largo`() {
        val text = "Ñandú ÉPICO 😀 straße"
        assertEquals(text.length, NoteSearch.fold(text).length)
        assertEquals("nandu epico 😀 straße", NoteSearch.fold(text))
    }

    @Test
    fun `el título es el nombre sin carpeta ni md`() {
        assertEquals("rust", NoteSearch.title("proyectos/rust.md"))
        assertEquals("Notas", NoteSearch.title("Notas.MD"))
    }

    @Test
    fun `los links llevan a la nota por nombre o ruta`() {
        val index = index(
            "rust.md" to "raíz",
            "proyectos/rust.md" to "proyecto",
            "proyectos/Zig Notas.md" to "zig",
        )
        assertEquals("rust.md", index.resolve("rust")?.path)
        assertEquals("rust.md", index.resolve("Rust.md#Sección")?.path)
        assertEquals("proyectos/rust.md", index.resolve("proyectos/rust")?.path)
        assertEquals("proyectos/Zig Notas.md", index.resolve("zig notas")?.path)
        assertNull(index.resolve("nada"))
        assertNull(index.resolve("#solo-encabezado"))
        assertEquals("proyecto", index.note("id-proyectos/rust.md")?.text)
    }

    @Test
    fun `la caché guarda el texto y la ruta, notas largas incluidas`() {
        val big = "x".repeat(100_000) + " aguja"
        val index = index("sub/grande.md" to big, "chica.md" to "#tag hola")
        val bytes = java.io.ByteArrayOutputStream().also(index::write).toByteArray()
        val read = VaultIndex.read(java.io.ByteArrayInputStream(bytes))
        assertEquals(listOf("grande"), read.titles("aguja"))
        assertEquals(big, read.note("id-sub/grande.md")?.text)
        assertEquals(listOf("chica"), read.titles("#tag"))
    }

    @Test
    fun `una nota movida se relee con su ruta nueva`() {
        val first = VaultIndex.EMPTY.refresh(listOf(NoteFile("x", 1, 1, "a.md"))) { "texto" }
        val moved = first.refresh(listOf(NoteFile("x", 1, 1, "sub/b.md"))) { "texto" }
        assertEquals(listOf("b"), moved.titles("texto"))
    }

    @Test
    fun `buscar en miles de notas es rápido`() {
        val listing = (0 until 5000).map { NoteFile("n$it", it.toLong(), 1, "nota-$it.md") }
        val index = VaultIndex.EMPTY.refresh(listing) { "# Nota ${it.id}\nAlgo de texto con acentos: canción, reunión. #tag${it.id.drop(1).toInt() % 50}\n".repeat(20) }
        index.search("calentar")
        val started = System.nanoTime()
        for (query in listOf("c", "ca", "can", "canc", "cancion", "cancion #tag7", "nota-49")) index.search(query)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(100, index.search("#tag7", limit = 1000).size)
        assert(millis < 3000) { "tardó $millis ms" }
    }
}
