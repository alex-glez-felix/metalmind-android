package io.github.alexglezfelix.metalmind.core

/**
 * Dónde está el frontmatter de una nota, como `frontmatter.rs` de Metalmind:
 * una línea `---` al principio (tras un BOM, si hay) y otra que lo cierra.
 * Sin la de cierre, no hay frontmatter.
 */
object Frontmatter {
    /** El YAML entre los `---`, y dónde empieza el cuerpo. */
    data class Split(val yaml: String, val body: Int)

    fun split(text: String): Split? {
        val start = bomLength(text)
        val firstEnd = lineEnd(text, start)
        if (!isDelimiter(text, start, firstEnd) || firstEnd == text.length) return null
        val yamlStart = firstEnd + 1
        var line = yamlStart
        while (true) {
            val end = lineEnd(text, line)
            if (isDelimiter(text, line, end)) {
                return Split(text.substring(yamlStart, line), minOf(end + 1, text.length))
            }
            if (end == text.length) return null
            line = end + 1
        }
    }

    /** Dónde empieza el cuerpo: tras el frontmatter, o tras el BOM si no hay. */
    fun bodyStart(text: String): Int = split(text)?.body ?: bomLength(text)

    private fun bomLength(text: String) = if (text.startsWith('﻿')) 1 else 0

    private fun isDelimiter(text: String, start: Int, end: Int) =
        text.substring(start, end).trimEnd() == "---"

    private fun lineEnd(text: String, start: Int): Int =
        text.indexOf('\n', start).let { if (it < 0) text.length else it }
}
