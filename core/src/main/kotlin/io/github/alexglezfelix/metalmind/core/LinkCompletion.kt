package io.github.alexglezfelix.metalmind.core

/**
 * El `[[link]]` que se está escribiendo: de `[[` (en [start]) a [end], y lo
 * que va tras el `[[` hasta el cursor. Si ya está cerrado, [end] va tras el
 * `]]`; si no, es el cursor.
 */
data class LinkQuery(val start: Int, val end: Int, val prefix: String)

object LinkCompletion {
    /**
     * El link sobre el que está el cursor, si lo hay: un `[[` en la misma línea
     * seguido de lo que puede ser un destino (sin `]`, `[`, `|` ni `#`) hasta el
     * cursor. Solo `[[` también cuenta: sugiere las notas más recientes.
     */
    fun at(text: String, cursor: Int): LinkQuery? {
        if (cursor < 2 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && isTargetChar(text[start - 1])) start--
        if (start < 2 || text[start - 1] != '[' || text[start - 2] != '[') return null
        // `[[[x`: el tercer `[` no abre otro link.
        if (start > 2 && text[start - 3] == '[') return null
        // Si el link ya está cerrado, se reemplaza entero; si no, lo que sigue al cursor se queda.
        var close = cursor
        while (close < text.length && isTargetChar(text[close])) close++
        val end = if (text.startsWith("]]", close)) close + 2 else cursor
        return LinkQuery(start - 2, end, text.substring(start, cursor))
    }

    /** Reemplaza el link de [query] por `[[`[target]`]]`, con el cursor detrás. */
    fun accept(text: String, query: LinkQuery, target: String): Completed {
        val link = "[[$target]]"
        return Completed(text.substring(0, query.start) + link + text.substring(query.end), query.start + link.length)
    }

    private fun isTargetChar(c: Char) = c != '[' && c != ']' && c != '|' && c != '#' && c != '\n' && c != '\r'
}
