package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class TagNameTest {
    companion object {
        // Los casos de `tag.rs` de Metalmind.
        @JvmStatic
        fun names() = listOf(
            "tag" to true, "a/b/c" to false, "y1984" to true, "1984" to true, "1" to true,
            "2024/10" to false, "１２" to true, "" to false, "con espacio" to false,
            "canción" to true, "🔥" to true, "a.b" to false, "-" to false, "_" to false,
            "--" to false, "/" to false, "a-" to true, "2024-10" to true, "xª" to true,
            "x々" to true, "x،" to false, "x।" to false, "x​" to false, "a\\b" to false,
            "a:b" to false, "a*" to false, "a?" to false, "a\"b" to false, "<a>" to false,
            "a|b" to false, "👨‍👩‍👧" to true, "🏴󠁧󠁢󠁳󠁣󠁴󠁿" to true,
        ).map { (name, valid) -> Arguments.of(name, valid) }
    }

    @ParameterizedTest
    @MethodSource("names")
    fun `nombres válidos`(name: String, valid: Boolean) {
        assertEquals(valid, TagName.isValid(name), name)
    }

    @Test
    fun `un nombre cabe en un nombre de archivo`() {
        val longest = "ñ".repeat(TagName.MAX_BYTES / 2)
        assertTrue(TagName.isValid(longest))
        assertEquals(false, TagName.isValid(longest + "a"))
        assertTrue(TagName.isValid("a".repeat(TagName.MAX_BYTES)))
        assertEquals(false, TagName.isValid("a".repeat(TagName.MAX_BYTES + 1)))
    }

    @Test
    fun `la identidad no distingue mayúsculas ni la forma de Unicode`() {
        assertEquals(TagName.key("rust"), TagName.key("Rust"))
        assertEquals(TagName.key("rust"), TagName.key("RUST"))
        assertEquals(TagName.key("canción"), TagName.key("CANCIÓN"))
        assertEquals(TagName.key("straße"), TagName.key("STRASSE"))
        assertEquals(TagName.key("straße"), TagName.key("Straẞe"))
    }

    @Test
    fun `como el case folding de Unicode`() {
        // Los casos de `key.rs` de Metalmind.
        assertNotEquals(TagName.key("ı"), TagName.key("i"))
        assertEquals(TagName.key("ı"), TagName.key("ı"))
        assertEquals(TagName.key("İ"), TagName.key("i\u0307"))
        assertEquals(TagName.key("σίσυφος"), TagName.key("ΣΊΣΥΦΟΣ"))
        assertEquals(TagName.key("σίσ"), TagName.key("ΣΊΣ"))
        assertEquals(TagName.key("ς"), TagName.key("σ"))
        assertEquals(TagName.key("ſ"), TagName.key("s"))
        assertEquals(TagName.key("ﬀ"), TagName.key("FF"))
    }

    @Test
    fun `los acentos distinguen`() {
        assertNotEquals(TagName.key("canción"), TagName.key("cancion"))
        assertNotEquals(TagName.key("año"), TagName.key("ano"))
    }

    @Test
    fun `el desempate es por bytes`() {
        assertEquals(listOf("Rust", "rust", "ñ", "ａ", "😀"), listOf("😀", "rust", "ａ", "ñ", "Rust").sortedWith(TagName.byteOrder))
    }
}
