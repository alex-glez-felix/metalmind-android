package io.github.alexglezfelix.metalmind.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class NoteContentTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '\'',
        value = [
            "idea|idea\\n",
            "idea\\n\\n\\n|idea\\n",
            "'línea  \\nsigue  \\n\\n'|'línea  \\nsigue  \\n'",
            "a\\r\\n\\r\\n|a\\n",
            "'  sangría\\n'|'  sangría\\n'",
            "'# Título\\n\\n- [ ] algo'|'# Título\\n\\n- [ ] algo\\n'",
        ],
    )
    fun `el texto queda tal cual con un solo salto al final`(text: String, expected: String) {
        assertEquals(unescape(expected), NoteContent.of(unescape(text)))
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\r", "\r")
}
