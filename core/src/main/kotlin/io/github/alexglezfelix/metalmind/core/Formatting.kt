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
     * Pone o quita [style] en la selección (sin los espacios de los bordes): si
     * ya lo tiene (las marcas por fuera, o alguna por dentro), lo quita de toda
     * la selección; si no, la envuelve. Código de varias líneas va en un bloque
     * ```` ``` ````, que se quita igual. Sin selección, escribe el par de marcas
     * con el cursor en medio; dentro de uno que sigue abierto en el renglón, el
     * cursor salta la marca que lo cierra, así se sigue escribiendo sin formato;
     * entre un par vacío, lo quita.
     */
    fun inline(text: String, start: Int, end: Int, style: Inline): Formatted {
        val marker = style.marker
        if (start == end) {
            val before = run(text, start, style, backwards = true)
            val after = run(text, start, style, backwards = false)
            val open = insideSpan(text, start, style)
            return when {
                open && closes(style, before) && closes(style, after) ->
                    Formatted(text.removeRange(start, start + marker.length).removeRange(start - marker.length, start), start - marker.length, start - marker.length)
                open && closes(style, after) -> Formatted(text, start + marker.length, start + marker.length)
                else -> Formatted(text.substring(0, start) + marker + marker + text.substring(start), start + marker.length, start + marker.length)
            }
        }
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s == e) return Formatted(text, start, end)
        if (style == Inline.CODE) unfence(text, s, e)?.let { return it }
        // Las marcas fuera de la selección, `**|texto|**`, si son un par que abre y cierra.
        val before = run(text, s, style, backwards = true)
        if (closes(style, before) && closes(style, run(text, e, style, backwards = false)) &&
            !insideSpan(text, s - before, style)
        ) {
            val out = text.substring(0, s - marker.length) + text.substring(s, e) + text.substring(e + marker.length)
            return Formatted(out, s - marker.length, e - marker.length)
        }
        // Marcas dentro de la selección (`|**texto**|`, `|**a** y **b**|`): se quitan todas.
        val inner = text.substring(s, e)
        val stripped = strip(inner, style)
        if (stripped != inner) return Formatted(text.substring(0, s) + stripped + text.substring(e), s, s + stripped.length)
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
     * Tocar otra vez en ese elemento nuevo, vacío, lo borra con su salto.
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
            if (line.kind == block && end == line.end) {
                if (line.end > line.contentStart) {
                    val item = "\n" + line.indent + line.next()
                    return Formatted(text.substring(0, end) + item + text.substring(end), end + item.length, end + item.length)
                }
                if (line.start > 0 && Line.parse(text, lineStart(text, line.start - 1)).kind != null) {
                    val out = text.substring(0, line.start - 1) + text.substring(line.end)
                    return Formatted(out, line.start - 1, line.start - 1)
                }
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
     * empieza con la misma marca (`-`, `*` o `+`; un checkbox, sin marcar); si el elemento
     * estaba vacío (y no queda nada tras el cursor), se quita su marca y el
     * salto, y la lista termina ahí.
     * `null` si no hay nada que hacer.
     */
    fun newline(text: String, cursor: Int): Formatted? {
        if (cursor < 1 || cursor > text.length || text[cursor - 1] != '\n') return null
        val previous = Line.parse(text, lineStart(text, cursor - 1))
        previous.kind ?: return null
        if (previous.contentStart == previous.end && lineEnd(text, cursor) == cursor) {
            val out = text.substring(0, previous.start) + text.substring(cursor)
            return Formatted(out, previous.start, previous.start)
        }
        val item = previous.indent + previous.next()
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

    /**
     * Un renglón: su sangría, dónde empieza la marca de lista y dónde el
     * contenido. [end] no incluye el `\r` de un fin de línea `\r\n`.
     */
    private class Line(val start: Int, val end: Int, val indent: String, val markerStart: Int, val contentStart: Int, val kind: Block?, val bullet: Char) {
        val blank get() = contentStart == end && kind == null

        /** La marca de un elemento nuevo como este: misma viñeta, y un checkbox sin marcar. */
        fun next() = if (kind == Block.CHECKBOX) "$bullet [ ] " else "$bullet "

        companion object {
            private val TASK = Regex("[-*+] \\[[ xX]\\] ")
            private val BULLET = Regex("[-*+] ")
            /** Una línea horizontal (`* * *`, `- - -`) no es una lista. */
            private val RULE = Regex("([-*_])( *\\1){2,} *")

            fun parse(text: String, start: Int): Line {
                var end = lineEnd(text, start)
                if (end > start && text[end - 1] == '\r') end--
                var i = start
                while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
                val indent = text.substring(start, i)
                val rest = text.substring(i, end)
                // Un `- [ ]` al final del renglón, sin espacio detrás, también es un checkbox vacío.
                val task = TASK.matchAt(rest + " ", 0)?.takeIf { it.range.last <= rest.length }
                val bullet = BULLET.matchAt(rest, 0)?.takeIf { !RULE.matches(rest) }
                return when {
                    task != null -> Line(start, end, indent, i, minOf(i + task.value.length, end), Block.CHECKBOX, rest[0])
                    bullet != null -> Line(start, end, indent, i, i + bullet.value.length, Block.LIST, rest[0])
                    else -> Line(start, end, indent, i, i, null, '-')
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

    /**
     * Quita el bloque ```` ``` ```` de la selección: la de su contenido, con las
     * vallas en los renglones de antes y después, o la que incluye las vallas.
     */
    private fun unfence(text: String, s: Int, e: Int): Formatted? {
        fun isFence(line: String, opening: Boolean) = line.trimEnd('\r').let { if (opening) it.startsWith("```") else it == "```" }
        if (s == lineStart(text, s) && s > 0 && e == lineEnd(text, e) && e < text.length) {
            val openStart = lineStart(text, s - 1)
            val closeEnd = lineEnd(text, e + 1)
            if (isFence(text.substring(openStart, s - 1), true) && isFence(text.substring(e + 1, closeEnd), false)) {
                val out = text.substring(0, openStart) + text.substring(s, e) + text.substring(closeEnd)
                return Formatted(out, openStart, openStart + e - s)
            }
        }
        val inner = text.substring(s, e)
        val firstEnd = inner.indexOf('\n')
        val lastStart = inner.lastIndexOf('\n')
        if (firstEnd in 0 until lastStart && isFence(inner.substring(0, firstEnd), true) && isFence(inner.substring(lastStart + 1), false)) {
            val content = inner.substring(firstEnd + 1, lastStart)
            return Formatted(text.substring(0, s) + content + text.substring(e), s, s + content.length)
        }
        return null
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
     * Si en el renglón de [at], antes de [at], queda abierto un [style]: un
     * número impar de rachas que lo abren o cierran.
     */
    private fun insideSpan(text: String, at: Int, style: Inline): Boolean {
        var i = lineStart(text, at)
        var count = 0
        while (i < at) {
            val n = run(text, i, style, backwards = false)
            if (n == 0) {
                i++
            } else {
                if (closes(style, minOf(n, at - i))) count++
                i += n
            }
        }
        return count % 2 == 1
    }

    /** [s] sin las marcas de [style]: de cada racha que lo abre o cierra, las suyas. */
    private fun strip(s: String, style: Inline): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val n = run(s, i, style, backwards = false)
            if (n == 0) {
                out.append(s[i++])
            } else {
                out.append(style.marker[0].toString().repeat(if (closes(style, n)) n - style.marker.length else n))
                i += n
            }
        }
        return out.toString()
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
