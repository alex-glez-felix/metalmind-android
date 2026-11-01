package io.github.alexglezfelix.metalmind.core

/** El texto tras dar formato, con la selección de [start] a [end] (iguales: solo el cursor). */
data class Formatted(val text: String, val start: Int, val end: Int)

/**
 * El formato ligero de la captura: negrita, cursiva y código en línea, y
 * listas y checkboxes por línea. Todo es markdown plano y cada acción se
 * deshace repitiéndola.
 */
object Formatting {
    enum class Inline(val marker: String) { BOLD("**"), ITALIC("*"), CODE("`") }

    enum class Block(val marker: String) { LIST("- "), CHECKBOX("- [ ] ") }

    /**
     * Pone o quita [style] en la selección (sin los espacios de los bordes). Sin
     * selección, escribe el par de marcas con el cursor en medio; si el cursor
     * ya está justo antes de la marca que cierra, la salta, así se sigue
     * escribiendo sin formato; entre un par vacío, lo quita.
     */
    fun inline(text: String, start: Int, end: Int, style: Inline): Formatted {
        val marker = style.marker
        if (start == end) {
            val before = run(text, start, style, backwards = true)
            val after = run(text, start, style, backwards = false)
            return when {
                closes(style, before) && closes(style, after) ->
                    Formatted(text.removeRange(start, start + marker.length).removeRange(start - marker.length, start), start - marker.length, start - marker.length)
                closes(style, after) -> Formatted(text, start + marker.length, start + marker.length)
                else -> Formatted(text.substring(0, start) + marker + marker + text.substring(start), start + marker.length, start + marker.length)
            }
        }
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s == e) return Formatted(text, start, end)
        // Las marcas fuera de la selección: `**|texto|**`.
        if (closes(style, run(text, s, style, backwards = true)) && closes(style, run(text, e, style, backwards = false))) {
            val out = text.substring(0, s - marker.length) + text.substring(s, e) + text.substring(e + marker.length)
            return Formatted(out, s - marker.length, e - marker.length)
        }
        // Las marcas dentro de la selección: `|**texto**|`.
        val inner = text.substring(s, e)
        if (inner.length >= 2 * marker.length + 1 &&
            closes(style, run(inner, 0, style, backwards = false)) &&
            closes(style, run(inner, inner.length, style, backwards = true))
        ) {
            val out = text.substring(0, s) + inner.substring(marker.length, inner.length - marker.length) + text.substring(e)
            return Formatted(out, s, e - 2 * marker.length)
        }
        if (style == Inline.CODE && '\n' in inner) return fence(text, s, e)
        val out = text.substring(0, s) + marker + inner + marker + text.substring(e)
        return Formatted(out, s + marker.length, e + marker.length)
    }

    /**
     * Pone o quita [block] en las líneas de la selección. Si todas (sin contar
     * las vacías) ya lo tienen, lo quita; si no, se lo pone a todas, y una
     * lista pasa a checkbox o al revés sin duplicar el guion. Con el cursor al
     * final de un elemento que ya lo tiene, empieza otro debajo: en el
     * teléfono la tecla de acción guarda, y así se escribe una lista sin Enter.
     */
    fun block(text: String, start: Int, end: Int, block: Block): Formatted {
        val first = lineStart(text, start)
        val last = if (end > start && end == lineStart(text, end)) end - 1 else end
        val lines = generateSequence(first) { at -> lineEnd(text, at).let { if (it < text.length && it < last) it + 1 else null } }
            .map { Line.parse(text, it) }
            .toList()
        val counted = lines.filter { !it.blank }.ifEmpty { lines }

        if (start == end && lines.size == 1) {
            val line = lines[0]
            if (line.kind == block && end == line.end && line.end > line.contentStart) {
                val item = "\n" + line.indent + block.marker
                return Formatted(text.substring(0, end) + item + text.substring(end), end + item.length, end + item.length)
            }
        }

        val remove = counted.all { it.kind == block }
        val edits = counted.map { line ->
            val marker = when {
                remove -> ""
                // Un checkbox marcado sigue marcado al ponerle checkbox otra vez.
                block == Block.CHECKBOX && line.kind == Block.CHECKBOX -> text.substring(line.markerStart, line.contentStart)
                else -> block.marker
            }
            Edit(line.markerStart, line.contentStart - line.markerStart, marker)
        }
        return apply(text, start, end, edits)
    }

    /**
     * Lo que hace Enter en una lista (con teclado físico): si [cursor] quedó
     * justo tras un salto de línea que sigue a un elemento, el nuevo renglón
     * empieza con la misma marca (un checkbox, sin marcar); si el elemento
     * estaba vacío (y no queda nada tras el cursor), se quita su marca y el
     * salto, y la lista termina ahí.
     * `null` si no hay nada que hacer.
     */
    fun newline(text: String, cursor: Int): Formatted? {
        if (cursor < 1 || cursor > text.length || text[cursor - 1] != '\n') return null
        val previous = Line.parse(text, lineStart(text, cursor - 1))
        val kind = previous.kind ?: return null
        if (previous.contentStart == previous.end && lineEnd(text, cursor) == cursor) {
            val out = text.substring(0, previous.start) + text.substring(cursor)
            return Formatted(out, previous.start, previous.start)
        }
        val item = previous.indent + kind.marker
        return Formatted(text.substring(0, cursor) + item + text.substring(cursor), cursor + item.length, cursor + item.length)
    }

    /**
     * [newline] si de [old] (con la selección de [start] a [end]) a [new] (con
     * el cursor en [cursor]) solo se escribió un salto de línea en lugar de la
     * selección, como hace Enter; `null` si fue cualquier otra cosa (pegar,
     * borrar, el teclado en pantalla corrigiendo una palabra).
     */
    fun typedNewline(old: String, start: Int, end: Int, new: String, cursor: Int): Formatted? {
        val typed = start in 0..end && end <= old.length &&
            cursor == start + 1 &&
            new.length == old.length - (end - start) + 1 &&
            new[start] == '\n' &&
            new.regionMatches(0, old, 0, start) &&
            new.regionMatches(cursor, old, end, old.length - end)
        return if (typed) newline(new, cursor) else null
    }

    /** Un renglón: su sangría, dónde empieza la marca de lista y dónde el contenido. */
    private class Line(val start: Int, val end: Int, val indent: String, val markerStart: Int, val contentStart: Int, val kind: Block?) {
        val blank get() = contentStart == end && kind == null

        companion object {
            private val TASK = Regex("[-*+] \\[[ xX]\\] ")
            private val BULLET = Regex("[-*+] ")

            fun parse(text: String, start: Int): Line {
                val end = lineEnd(text, start)
                var i = start
                while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
                val indent = text.substring(start, i)
                val rest = text.substring(i, end)
                // Un `- [ ]` al final del renglón, sin espacio detrás, también es un checkbox vacío.
                val task = TASK.matchAt(rest + " ", 0)?.takeIf { it.range.last <= rest.length }
                val bullet = BULLET.matchAt(rest, 0)
                return when {
                    task != null -> Line(start, end, indent, i, minOf(i + task.value.length, end), Block.CHECKBOX)
                    bullet != null -> Line(start, end, indent, i, i + bullet.value.length, Block.LIST)
                    else -> Line(start, end, indent, i, i, null)
                }
            }
        }
    }

    /** Cambiar [removed] caracteres desde [at] por [inserted]. */
    private class Edit(val at: Int, val removed: Int, val inserted: String)

    /** Aplica [edits] (en orden y sin pisarse) y mueve la selección con el texto. */
    private fun apply(text: String, start: Int, end: Int, edits: List<Edit>): Formatted {
        val out = StringBuilder()
        var from = 0
        for (edit in edits) {
            out.append(text, from, edit.at).append(edit.inserted)
            from = edit.at + edit.removed
        }
        out.append(text, from, text.length)
        fun map(p: Int): Int {
            var shift = 0
            for (edit in edits) {
                when {
                    edit.at + edit.removed <= p -> shift += edit.inserted.length - edit.removed
                    edit.at <= p -> return edit.at + shift + edit.inserted.length
                }
            }
            return p + shift
        }
        return Formatted(out.toString(), map(start), map(end))
    }

    /** Un bloque de código con ``` en renglones aparte, para una selección de varias líneas. */
    private fun fence(text: String, s: Int, e: Int): Formatted {
        val open = (if (s > 0 && text[s - 1] != '\n') "\n" else "") + "```\n"
        val close = "\n```" + if (e < text.length && text[e] != '\n') "\n" else ""
        val out = text.substring(0, s) + open + text.substring(s, e) + close + text.substring(e)
        return Formatted(out, s + open.length, e + open.length)
    }

    /** Cuántas veces se repite el carácter de la marca de [style] justo antes (o después) de [at]. */
    private fun run(text: String, at: Int, style: Inline, backwards: Boolean): Int {
        val c = style.marker[0]
        var n = 0
        if (backwards) while (at - n - 1 >= 0 && text[at - n - 1] == c) n++
        else while (at + n < text.length && text[at + n] == c) n++
        return n
    }

    /**
     * Si una racha de [n] marcas de un lado cierra [style]: la negrita con dos
     * o más `*`, la cursiva con una o tres (`***` es las dos), el código con una.
     */
    private fun closes(style: Inline, n: Int) = when (style) {
        Inline.BOLD -> n >= 2
        Inline.ITALIC -> n == 1 || n == 3
        Inline.CODE -> n == 1
    }

    private fun lineStart(text: String, at: Int) = text.lastIndexOf('\n', at - 1) + 1

    private fun lineEnd(text: String, at: Int) = text.indexOf('\n', at).let { if (it < 0) text.length else it }
}
