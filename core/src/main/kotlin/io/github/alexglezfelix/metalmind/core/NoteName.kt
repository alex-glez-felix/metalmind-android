package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El nombre de archivo de una captura, con las mismas reglas que `metalmind add`
 * (docs/cli.md de Metalmind): `slug.md` sin fecha, para poder enlazarla
 * (`[[idea-nueva]]`).
 */
object NoteName {
    /**
     * Cuántos caracteres de la primera línea entran en el nombre: con 4 bytes
     * por carácter en el peor caso, el nombre cabe en los 255 de ext4.
     */
    const val MAX_SLUG_CHARS = 50

    const val EXTENSION = ".md"

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT)

    /**
     * El nombre sin extensión: el slug de la primera línea con letras o números
     * o, si no hay ninguna (solo emoji o puntuación), la fecha y la hora de [now].
     */
    fun stem(text: String, now: LocalDateTime): String =
        firstSlug(text).ifEmpty { now.format(TIMESTAMP) }

    /** `stem.md` en el primer intento; después `stem-2.md`, `stem-3.md`, … */
    fun fileName(stem: String, attempt: Int): String {
        require(attempt >= 1) { "attempt empieza en 1" }
        return if (attempt == 1) "$stem$EXTENSION" else "$stem-$attempt$EXTENSION"
    }

    /** El slug de la primera línea que tenga letras o números. */
    fun firstSlug(text: String): String =
        text.lineSequence().map(::slug).firstOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * Las palabras de [line] en minúsculas y unidas por `-`, sin cortar ninguna
     * salvo que la primera sola ya no quepa. Acentos (también descompuestos) y
     * otros alfabetos se quedan; todo lo demás separa palabras, así que nunca
     * queda un carácter que falle en otro sistema de archivos (`/ \ : * ? " < > |`).
     */
    fun slug(line: String): String {
        val slug = StringBuilder()
        var chars = 0
        for (word in words(line)) {
            val lower = word.lowercase(Locale.ROOT)
            val len = lower.codePointCount(0, lower.length)
            if (slug.isEmpty()) {
                val take = minOf(len, MAX_SLUG_CHARS)
                slug.append(lower, 0, lower.offsetByCodePoints(0, take))
                chars = take
            } else if (chars + 1 + len <= MAX_SLUG_CHARS) {
                slug.append('-').append(lower)
                chars += 1 + len
            } else {
                break
            }
        }
        return slug.toString()
    }

    private fun words(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            if (isWordChar(cp)) {
                word.appendCodePoint(cp)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
            i += Character.charCount(cp)
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    /** Letras, números (de cualquier tipo) y marcas, como `is_word_char` de Metalmind. */
    private fun isWordChar(cp: Int): Boolean {
        if (Character.isAlphabetic(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK,
            -> true
            else -> false
        }
    }
}
