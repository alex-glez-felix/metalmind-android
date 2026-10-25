package io.github.alexglezfelix.metalmind.core

/** El `#tag` que se está escribiendo: de `#` (en [start]) a [end], y lo que va tras el `#`. */
data class TagQuery(val start: Int, val end: Int, val prefix: String)

/** El texto tras aceptar una sugerencia, con el cursor en [cursor]. */
data class Completed(val text: String, val cursor: Int)

object TagCompletion {
    /**
     * El tag sobre el que está el cursor, si lo hay: un `#` al inicio de línea
     * o tras un espacio, seguido de caracteres de tag hasta el cursor. Solo `#`
     * también cuenta: sugiere los más usados.
     */
    fun at(text: String, cursor: Int): TagQuery? {
        if (cursor < 0 || cursor > text.length) return null
        var start = cursor
        while (start > 0) {
            val cp = Character.codePointBefore(text, start)
            if (!TagName.isTagChar(cp)) break
            start -= Character.charCount(cp)
        }
        val hash = start - 1
        if (hash < 0 || text[hash] != '#') return null
        if (hash > 0) {
            val before = Character.codePointBefore(text, hash)
            if (!Character.isWhitespace(before) && !Character.isSpaceChar(before)) return null
        }
        var end = cursor
        while (end < text.length) {
            val cp = text.codePointAt(end)
            if (!TagName.isTagChar(cp)) break
            end += Character.charCount(cp)
        }
        return TagQuery(hash, end, text.substring(start, cursor))
    }

    /** Reemplaza el tag de [query] por `#`[name] y deja un espacio detrás para seguir escribiendo. */
    fun accept(text: String, query: TagQuery, name: String): Completed {
        val after = text.substring(query.end)
        val space = if (after.firstOrNull()?.isWhitespace() == true) "" else " "
        // El cursor queda tras el espacio, el agregado o el que ya estaba.
        return Completed(text.substring(0, query.start) + "#$name$space" + after, query.start + name.length + 2)
    }
}
