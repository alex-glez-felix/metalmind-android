package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class NoteNameTest {
    companion object {
        // Los mismos casos que `capture.rs` de Metalmind, más los caracteres que
        // fallan en otros sistemas de archivos.
        @JvmStatic
        fun slugs() = listOf(
            Arguments.of("Comprar leche", "comprar-leche"),
            Arguments.of("  # Idea: probar GPUI!  ", "idea-probar-gpui"),
            Arguments.of("- [ ] llamar a la imprenta", "llamar-a-la-imprenta"),
            Arguments.of("Canción de AÑO nuevo", "canción-de-año-nuevo"),
            Arguments.of("日本語のメモ", "日本語のメモ"),
            Arguments.of("canción de año", "canción-de-año"),
            Arguments.of("नमस्ते दुनिया", "नमस्ते-दुनिया"),
            Arguments.of("#rust y #gpui", "rust-y-gpui"),
            Arguments.of("a/b\\c:d*e?f\"g<h>i|j", "a-b-c-d-e-f-g-h-i-j"),
            Arguments.of(".oculta", "oculta"),
            Arguments.of("2024 ½ Ⅻ", "2024-½-ⅻ"),
            Arguments.of("🔥 --- ...", ""),
            Arguments.of("", ""),
        )
    }

    @ParameterizedTest
    @MethodSource("slugs")
    fun `slug de una línea`(line: String, expected: String) {
        assertEquals(expected, NoteName.slug(line))
    }

    @Test
    fun `el slug corta entre palabras`() {
        val slug = NoteName.slug("palabra ".repeat(20))
        assertEquals(List(6) { "palabra" }.joinToString("-"), slug)
        assertTrue(slug.length <= NoteName.MAX_SLUG_CHARS)
    }

    @Test
    fun `una palabra enorme se corta`() {
        assertEquals("ñ".repeat(NoteName.MAX_SLUG_CHARS), NoteName.slug("ñ".repeat(80)))
    }

    @Test
    fun `el corte cuenta caracteres y no parte un par sustituto`() {
        val slug = NoteName.slug("𝒜".repeat(80))
        assertEquals("𝒜".repeat(NoteName.MAX_SLUG_CHARS), slug)
    }

    @Test
    fun `el slug sale de la primera línea con letras`() {
        assertEquals("hola-mundo", NoteName.firstSlug("\n---\n\n  Hola mundo\notra"))
        assertEquals("hola", NoteName.firstSlug("hola\r\nmundo"))
        assertEquals("", NoteName.firstSlug("🔥\n"))
    }

    @Test
    fun `sin slug el nombre es la fecha y la hora`() {
        val now = LocalDateTime.of(2026, 10, 18, 9, 5, 7)
        assertEquals("2026-10-18-090507", NoteName.stem("🔥🔥", now))
        assertEquals("idea", NoteName.stem("Idea", now))
    }

    @Test
    fun `los sufijos empiezan en 2`() {
        assertEquals("idea.md", NoteName.fileName("idea", 1))
        assertEquals("idea-2.md", NoteName.fileName("idea", 2))
        assertEquals("idea-17.md", NoteName.fileName("idea", 17))
    }
}
