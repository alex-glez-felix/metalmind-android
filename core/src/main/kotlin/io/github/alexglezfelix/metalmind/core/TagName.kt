package io.github.alexglezfelix.metalmind.core

import java.text.Normalizer
import java.util.Locale

/**
 * Qué es un nombre de tag y cuándo dos son el mismo, como `metalmind-syntax`
 * (`tag.rs`) y `TagKey` de Metalmind (05-tags §1 y §6).
 */
object TagName {
    /** El más largo cuya página `<nombre>.md` cabe en los 255 bytes de un nombre de archivo. */
    const val MAX_BYTES = 250

    /**
     * Letras, números, `_` y `-` y, fuera de ASCII, letras, marcas, números y
     * símbolos (emoji incluidos). La puntuación (la `/` también: `#2024/10` es
     * `#2024`) y los espacios lo cortan. De los de formato solo caben el ZWJ y
     * los de etiqueta de las banderas.
     */
    fun isTagChar(cp: Int): Boolean {
        if (cp < 0x80) {
            return cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code ||
                cp in '0'.code..'9'.code || cp == '_'.code || cp == '-'.code
        }
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true
            Character.FORMAT -> cp == 0x200D || cp in 0xE0020..0xE007F
            else -> false
        }
    }

    /** Solo caracteres de tag, no solo separadores, y de [MAX_BYTES] como mucho. Los números solos valen. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() &&
            name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES &&
            name.codePoints().allMatch(::isTagChar) &&
            name.any { it != '-' && it != '_' }

    /**
     * La identidad de un tag: sin distinguir mayúsculas y en NFC, así `#Rust`,
     * `#rust` y `#RUST` son el mismo, y una `é` compuesta o descompuesta
     * también. Los acentos sí distinguen: `#canción` no es `#cancion`.
     */
    fun key(name: String): String = nfc(fold(nfc(name)))

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /**
     * Como el case folding completo de Unicode, que Java no trae: carácter por
     * carácter (así la sigma final no depende de lo que la rodea, `ς` = `σ`),
     * minúsculas, mayúsculas y otra vez minúsculas junta lo que el folding
     * junta (`ß`, `ẞ` y `SS` → `ss`; `ſ` → `s`). La `ı` sin punto es la
     * excepción: no tiene folding, y por mayúsculas acabaría en `i`.
     */
    private fun fold(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp == DOTLESS_I) {
                out.appendCodePoint(cp)
            } else {
                out.append(String(Character.toChars(cp)).lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT))
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private const val DOTLESS_I = 0x0131

    /** El orden de bytes de UTF-8, el del desempate de Metalmind (`Rust` antes que `rust`). */
    val byteOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i).compareTo(b.length - j)
    }
}
