package io.github.alexglezfelix.metalmind.core

/**
 * El cuerpo de una nota listo para leer: el markdown de todos los días sin
 * sus marcas, como la vista de lectura de Obsidian, pero en un solo texto que
 * se puede seleccionar.
 *
 * - Encabezados, negrita, cursiva, tachado, `==resaltado==`, código en línea
 *   y bloques de código (sin las líneas de ```` ``` ````).
 * - Listas con viñeta, numeradas y de tareas (`☐`/`☑`), y citas (también los
 *   callouts `> [!nota]`), con su sangría.
 * - `#tag` y `[[link|alias]]` tocables, con las reglas de [TagScanner]; un
 *   `[texto](otra-nota.md)` también abre la nota. `[texto](https://…)`,
 *   `<https://…>` y las URL sueltas abren el navegador.
 * - Comentarios `%% %%` ocultos. Líneas horizontales como una raya.
 * - Las tablas, las fórmulas y el HTML quedan tal cual (las tablas, en
 *   monoespaciada para que las columnas se alineen).
 *
 * Cada línea del cuerpo es una línea del resultado (salvo las de las vallas
 * de código y los comentarios), sin juntar las de un mismo párrafo.
 */
object NoteMarkdown {
    enum class Style { Bold, Italic, Strike, Highlight, Code, CodeBlock, Table, Heading1, Heading2, Heading3, Heading4, Heading5, Heading6, Quote, Marker, Rule }

    sealed interface Span {
        val start: Int
        val end: Int
    }

    /** Un estilo de [start] a [end] (sin incluir) en [Rendered.text]. */
    data class Styled(override val start: Int, override val end: Int, val style: Style) : Span

    /** Un `#tag`; [name] sin `#`. */
    data class Tag(override val start: Int, override val end: Int, val name: String) : Span

    /** Un link a otra nota del vault; [target] como lo resuelve [VaultIndex.resolve]. */
    data class Link(override val start: Int, override val end: Int, val target: String) : Span

    /** Un link a fuera del vault (`https:`, `mailto:`…). */
    data class Url(override val start: Int, override val end: Int, val url: String) : Span

    /**
     * Una línea con sangría: [level] niveles de lista, y [hanging] caracteres
     * de viñeta o de barra de cita (`• `, `12. `, `▎ `) que las líneas que
     * siguen al ajustarse no repiten.
     */
    data class Indent(val start: Int, val end: Int, val level: Int, val hanging: Int)

    data class Rendered(val text: String, val spans: List<Span>, val indents: List<Indent>)

    fun render(text: String): Rendered = Renderer(text).run()

    private class Fence(val marker: Char, val len: Int)

    private class Renderer(val src: String) {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        val indents = mutableListOf<Indent>()

        /** Los `#tag` y `[[links]]` de [TagScanner], por dónde empiezan en [src]. */
        val markup: Map<Int, TagScanner.Markup> = TagScanner.markup(src).associateBy { it.start }

        fun run(): Rendered {
            var at = Frontmatter.bodyStart(src)
            // Sin las líneas en blanco del principio.
            while (true) {
                val end = lineEnd(at)
                if (end >= src.length || src.substring(at, end).isNotBlank()) break
                at = end + 1
            }
            while (at <= src.length) {
                val end = lineEnd(at)
                val line = src.substring(at, end).removeSuffix("\r")
                line(at, at + line.length)
                at = end + 1
            }
            while (out.endsWith("\n")) out.setLength(out.length - 1)
            val n = out.length
            return Rendered(
                out.toString(),
                spans.mapNotNull { clip(it, n) },
                indents.filter { it.start < n }.map { if (it.end > n) it.copy(end = n) else it },
            )
        }

        private fun lineEnd(at: Int) = src.indexOf('\n', at).let { if (it < 0) src.length else it }

        /** Un bloque de código abierto: su valla, y en cuántas citas y con qué sangría empezó. */
        private inner class OpenFence(val fence: Fence, val quotes: Int, val indent: Int, val level: Int)

        private var fence: OpenFence? = null

        /** Dentro de un comentario `%%` que viene de una línea anterior. */
        private var comment = false

        /** Dentro de una fórmula `$$` de varias líneas. */
        private var mathBlock = false

        /** Un `%%` de esta línea que no cierra en ella. */
        private var commentOpened = false

        /** Las columnas de las viñetas de las listas abiertas, de fuera hacia dentro. */
        private val listColumns = mutableListOf<Int>()

        private fun line(from: Int, to: Int) {
            if (comment) {
                val close = src.indexOf("%%", from)
                if (close < 0 || close >= to) return
                comment = false
                // Lo que sigue al cierre se ve, como texto.
                if (src.substring(close + 2, to).isNotBlank()) {
                    val mark = out.length
                    commentOpened = false
                    inline(indentation(close + 2, to).second, to)
                    finishLine(mark, 0, 0, quote = false)
                    comment = commentOpened
                    dropIfCommentOnly(mark)
                }
                return
            }
            if (mathBlock) {
                out.append(src, from, to).append('\n')
                if (src.substring(from, to).contains("$$")) mathBlock = false
                return
            }
            val (quotes, afterQuotes) = quotes(from, to)
            val (indent, content) = indentation(afterQuotes, to)
            fence?.let { open ->
                if (quotes >= open.quotes) {
                    val stripped = src.substring(skipColumns(afterQuotes, to, open.indent), to)
                    if (closes(open.fence, stripped)) {
                        fence = null
                    } else {
                        codeLine(skipColumns(afterQuotes, to, open.indent), to, open.quotes, open.level)
                    }
                    return
                }
                // Una cita que se acaba cierra también el bloque que estaba en ella.
                fence = null
            }
            openingFence(src.substring(content, to))?.let {
                val level = if (indent > 0) listColumns.count { c -> c < indent } else 0
                fence = OpenFence(it, quotes, indent, level)
                return
            }
            val trimmed = src.substring(content, to)
            if (trimmed.startsWith("$$") && trimmed.indexOf("$$", 2) < 0) {
                mathBlock = true
                out.append(src, from, to).append('\n')
                return
            }
            val mark = out.length
            commentOpened = false
            block(from, to)
            if (commentOpened) {
                comment = true
                dropIfCommentOnly(mark)
            }
        }

        /** Quita la línea de [mark] en adelante si no quedó nada más que un comentario. */
        private fun dropIfCommentOnly(mark: Int) {
            if (out.substring(mark).replace("▎", "").isNotBlank()) return
            out.setLength(mark)
            spans.removeAll { it.start >= mark }
            indents.removeAll { it.start >= mark }
        }

        /** Cuántos `>` abren la línea, y dónde sigue. */
        private fun quotes(from: Int, to: Int): Pair<Int, Int> {
            var at = from
            var quotes = 0
            while (true) {
                var i = at
                while (i < to && i - at < 4 && src[i] == ' ') i++
                if (i < to && src[i] == '>') {
                    quotes++
                    at = i + 1
                    if (at < to && src[at] == ' ') at++
                } else {
                    return quotes to at
                }
            }
        }

        /** Las columnas de espacio al principio (un tab cuenta 4), y dónde termina. */
        private fun indentation(from: Int, to: Int): Pair<Int, Int> {
            var cols = 0
            var i = from
            while (i < to && (src[i] == ' ' || src[i] == '\t')) {
                cols += if (src[i] == '\t') 4 else 1
                i++
            }
            return cols to i
        }

        /** Salta hasta [cols] columnas de espacio. */
        private fun skipColumns(from: Int, to: Int, cols: Int): Int {
            var left = cols
            var i = from
            while (i < to && left > 0 && (src[i] == ' ' || src[i] == '\t')) {
                left -= if (src[i] == '\t') 4 else 1
                i++
            }
            return i
        }

        private fun codeLine(from: Int, to: Int, quotes: Int, level: Int) {
            val lineStart = out.length
            if (quotes > 0) {
                out.append("▎ ".repeat(quotes))
                styled(lineStart, out.length, Style.Marker)
            }
            val start = out.length
            out.append(src, from, to)
            styled(start, out.length, Style.CodeBlock)
            if (level > 0 || quotes > 0) indents += Indent(lineStart, out.length, level, 2 * quotes)
            out.append('\n')
        }

        private fun block(from: Int, to: Int) {
            val line = src.substring(from, to)
            if (line.isBlank()) {
                out.append('\n')
                return
            }
            if (RULE.matches(line)) {
                val start = out.length
                out.append("─".repeat(RULE_WIDTH))
                styled(start, out.length, Style.Rule)
                out.append('\n')
                return
            }
            val lineStart = out.length
            var at = from
            var level = 0
            var hanging = 0

            // Citas: `>` y `> >`, con o sin espacio.
            val (quotes, afterQuotes) = quotes(from, to)
            at = afterQuotes
            if (quotes > 0) {
                val start = out.length
                out.append("▎ ".repeat(quotes))
                styled(start, out.length, Style.Marker)
                hanging += 2 * quotes
                CALLOUT.matchAt(src.substring(at, to), 0)?.let { m ->
                    val s = out.length
                    val title = m.groups[2]!!
                    if (title.value.isBlank()) {
                        out.append(m.groupValues[1].replaceFirstChar { it.uppercase() })
                    } else {
                        inline(at + title.range.first, at + title.range.last + 1)
                    }
                    styled(s, out.length, Style.Bold)
                    finishLine(lineStart, level, hanging, quote = true)
                    return
                }
            }

            val rest = src.substring(at, to)
            HEADING.matchAt(rest, 0)?.let { m ->
                val hashes = m.groupValues[1].length
                val contentStart = at + m.range.last + 1
                var contentEnd = to
                // Los `#` del final (`## Título ##`) no se muestran.
                val trailing = Regex("[ \\t]+#+[ \\t]*$").find(src.substring(contentStart, to))
                if (trailing != null) contentEnd = contentStart + trailing.range.first
                val s = out.length
                inline(contentStart, contentEnd.coerceAtLeast(contentStart))
                styled(s, out.length, HEADINGS[hashes - 1])
                finishLine(lineStart, level, hanging, quote = quotes > 0)
                return
            }

            if (rest.trimStart().startsWith("|")) {
                val s = out.length
                raw(at, to)
                styled(s, out.length, Style.Table)
                finishLine(lineStart, level, hanging, quote = quotes > 0)
                return
            }

            val item = LIST_ITEM.matchAt(rest, 0)
            // Un párrafo sin sangría termina las listas.
            if (item == null && rest.isNotEmpty() && !rest[0].isWhitespace()) listColumns.clear()
            item?.let { m ->
                val column = m.groupValues[1].fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }
                while (listColumns.isNotEmpty() && listColumns.last() > column) listColumns.removeAt(listColumns.lastIndex)
                if (listColumns.isEmpty() || listColumns.last() < column) listColumns += column
                val depth = listColumns.size - 1
                val bullet = m.groupValues[2]
                val task = m.groupValues[3]
                level += depth + 1
                val marker = when {
                    task.isNotEmpty() -> if (task[1] == ' ') "☐ " else "☑ "
                    bullet[0].isDigit() -> "$bullet "
                    depth % 2 == 0 -> "• "
                    else -> "◦ "
                }
                val s = out.length
                out.append(marker)
                styled(s, out.length, Style.Marker)
                hanging += marker.length
                at += m.range.last + 1
            }
            inline(at, to)
            finishLine(lineStart, level, hanging, quote = quotes > 0)
        }

        private fun finishLine(lineStart: Int, level: Int, hanging: Int, quote: Boolean) {
            if (quote) styled(lineStart, out.length, Style.Quote)
            if (level > 0 || hanging > 0) indents += Indent(lineStart, out.length, level, hanging)
            out.append('\n')
        }

        /** El texto tal cual, con los tags y links tocables pero sin quitarles nada. */
        private fun raw(from: Int, to: Int) {
            var i = from
            while (i < to) {
                val m = markup[i]
                if (m != null && m.end <= to) {
                    val s = out.length
                    out.append(src, i, m.end)
                    spans += markupSpan(m, s, out.length)
                    i = m.end
                } else {
                    out.append(src[i])
                    i++
                }
            }
        }

        private fun markupSpan(m: TagScanner.Markup, start: Int, end: Int): Span = when (m) {
            is TagScanner.Tag -> Tag(start, end, m.name)
            is TagScanner.Link -> Link(start, end, m.target)
        }

        /** Cuántos `inline` hay abiertos uno dentro de otro (énfasis y links anidados). */
        private var nesting = 0

        /**
         * El texto de [from] a [to] sin las marcas en línea. Pasados
         * [MAX_NESTING] niveles, tal cual: miles de `[` seguidos agotaban la pila.
         */
        private fun inline(from: Int, to: Int) {
            if (nesting >= MAX_NESTING) {
                out.append(src, from, to)
                return
            }
            nesting++
            try {
                var i = from
                while (i < to) {
                    i = inlineStep(i, to)
                }
            } finally {
                nesting--
            }
        }

        private fun inlineStep(i: Int, to: Int): Int {
            val c = src[i]
            val next = if (i + 1 < to) src[i + 1] else null
            markup[i]?.let { m ->
                if (m.end <= to) {
                    val s = out.length
                    when (m) {
                        is TagScanner.Tag -> out.append(src, i, m.end)
                        is TagScanner.Link -> out.append(wikilinkLabel(i, m))
                    }
                    spans += markupSpan(m, s, out.length)
                    return m.end
                }
            }
            when {
                c == '\\' && next != null && isAsciiPunctuation(next) -> {
                    out.append(next)
                    return i + 2
                }
                c == '`' -> codeSpan(i, to)?.let { return it }
                c == '%' && next == '%' -> {
                    val close = src.indexOf("%%", i + 2)
                    if (close in 0 until to) return close + 2
                    // Sigue en las líneas de abajo.
                    commentOpened = true
                    return to
                }
                c == '$' -> math(i, to)?.let { return it }
                c == '<' -> autolink(i, to)?.let { return it }
                c == '!' && next == '[' -> markdownLink(i + 1, to, image = true)?.let { return it }
                c == '[' -> markdownLink(i, to, image = false)?.let { return it }
                c == 'h' || c == 'H' -> bareUrl(i, to)?.let { return it }
                c == '*' || c == '_' || c == '~' || c == '=' -> emphasis(i, to)?.let { return it }
            }
            out.append(c)
            return i + 1
        }

        /**
         * Una fórmula en la línea, tal cual: `$$…$$`, o `$…$` sin espacio tras
         * el que abre ni antes del que cierra, que no cierra con `$` y un
         * dígito (`$5`), como en [TagScanner].
         */
        private fun math(at: Int, to: Int): Int? {
            if (at + 1 < to && src[at + 1] == '$') {
                val close = src.indexOf("$$", at + 2)
                if (close < 0 || close + 2 > to) return null
                out.append(src, at, close + 2)
                return close + 2
            }
            val content = at + 1
            if (content >= to || src[content].isWhitespace()) return null
            var i = content
            while (i < to) {
                when {
                    src[i] == '\\' && i + 1 < to -> i += 2
                    src[i] == '$' -> {
                        val closes = i > content && !src[i - 1].isWhitespace() &&
                            !(i + 1 < src.length && src[i + 1] in '0'..'9')
                        if (closes) {
                            out.append(src, at, i + 1)
                            return i + 1
                        }
                        i++
                    }
                    else -> i++
                }
            }
            return null
        }

        /** Lo que se ve de `[[destino|alias]]`: el alias o, sin él, el destino. */
        private fun wikilinkLabel(at: Int, m: TagScanner.Link): String {
            val open = src.indexOf("[[", at)
            val inner = src.substring(open + 2, m.end - 2)
            val bar = inner.indexOf('|')
            return if (bar >= 0 && inner.substring(bar + 1).isNotBlank()) inner.substring(bar + 1) else m.target
        }

        /** `` `código` ``: tantos backticks al cerrar como al abrir, en la misma línea. */
        private fun codeSpan(at: Int, to: Int): Int? {
            var n = 0
            while (at + n < to && src[at + n] == '`') n++
            var i = at + n
            while (i < to) {
                if (src[i] == '`') {
                    var m = 0
                    while (i + m < to && src[i + m] == '`') m++
                    if (m == n) {
                        var content = src.substring(at + n, i)
                        if (content.length >= 2 && content.startsWith(' ') && content.endsWith(' ') && content.isNotBlank()) {
                            content = content.substring(1, content.length - 1)
                        }
                        val s = out.length
                        out.append(content)
                        styled(s, out.length, Style.Code)
                        return i + m
                    }
                    i += m
                } else {
                    i++
                }
            }
            // Sin cierre: los backticks son texto.
            out.append(src, at, at + n)
            return at + n
        }

        /** `<https://…>` o `<correo@dominio>`. */
        private fun autolink(at: Int, to: Int): Int? {
            val close = src.indexOf('>', at)
            if (close < 0 || close >= to) return null
            val inner = src.substring(at + 1, close)
            val url = when {
                SCHEME.matchAt(inner, 0) != null && inner.none { it.isWhitespace() || it == '<' } -> inner
                EMAIL.matches(inner) -> "mailto:$inner"
                else -> return null
            }
            val s = out.length
            out.append(inner)
            spans += Url(s, out.length, url)
            return close + 1
        }

        /** Una URL `http(s)://` suelta, sin la puntuación del final. */
        private fun bareUrl(at: Int, to: Int): Int? {
            if (at > 0 && (src[at - 1].isLetterOrDigit() || src[at - 1] == '/')) return null
            val m = BARE_URL.matchAt(src.substring(at, to), 0) ?: return null
            var url = m.value
            while (url.isNotEmpty() && url.last() in ".,;:!?*_~'\"") url = url.dropLast(1)
            // Un `)` del final es de la URL solo si abre uno dentro (Wikipedia).
            while (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) url = url.dropLast(1)
            if (url.length <= url.indexOf("://") + 3) return null
            val s = out.length
            out.append(url)
            spans += Url(s, out.length, url)
            return at + url.length
        }

        /**
         * `[texto](destino)` y `![alt](destino)`. Un destino relativo que no es
         * una URL es otra nota del vault (`otra.md`, `carpeta/otra.md`).
         */
        private fun markdownLink(open: Int, to: Int, image: Boolean): Int? {
            var depth = 0
            var i = open
            var labelClose = -1
            while (i < to) {
                when (src[i]) {
                    '\\' -> i++
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            labelClose = i
                            break
                        }
                    }
                }
                i++
            }
            if (labelClose < 0 || labelClose + 1 >= to || src[labelClose + 1] != '(') return null
            var d = 0
            var j = labelClose + 1
            var parenClose = -1
            while (j < to) {
                when (src[j]) {
                    '\\' -> j++
                    '(' -> d++
                    ')' -> {
                        d--
                        if (d == 0) {
                            parenClose = j
                            break
                        }
                    }
                }
                j++
            }
            if (parenClose < 0) return null
            val inside = src.substring(labelClose + 2, parenClose).trim()
            val destination = if (inside.startsWith("<")) {
                inside.substringAfter('<').substringBefore('>')
            } else {
                // Tras el destino solo puede ir un título: `(destino "título")`.
                val title = inside.substringAfter(' ', "").trimStart()
                if (title.isNotEmpty() && title[0] !in "\"'(") return null
                inside.substringBefore(' ')
            }
            val s = out.length
            if (image) {
                val alt = src.substring(open + 1, labelClose)
                out.append(alt.ifBlank { destination.substringAfterLast('/') })
                styled(s, out.length, Style.Italic)
            } else {
                val before = spans.size
                inline(open + 1, labelClose)
                // El link entero lleva al destino: una URL dentro del texto no cuenta aparte.
                spans.subList(before, spans.size).removeAll { it !is Styled }
            }
            if (out.length > s) {
                when {
                    SCHEME.matchAt(destination, 0) != null -> spans += Url(s, out.length, destination)
                    destination.isNotEmpty() && !destination.startsWith("#") && !image ->
                        noteTarget(destination)?.let { spans += Link(s, out.length, it) }
                }
            }
            return parenClose + 1
        }

        /** `carpeta/Otra%20nota.md#sección` → `carpeta/Otra nota`. */
        private fun noteTarget(destination: String): String? {
            val path = destination.substringBefore('#').substringBefore('?')
            val decoded = try {
                java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                path
            }
            // Sin `../`: la nota se busca por su nombre o el final de su ruta.
            var target = decoded
            while (true) {
                val shorter = target.removePrefix("./").removePrefix("../").removePrefix("/")
                if (shorter == target) break
                target = shorter
            }
            return target.ifBlank { null }
        }

        /**
         * `*cursiva*`, `**negrita**`, `***las dos***` (también con `_`),
         * `~~tachado~~` y `==resaltado==`. Cierra con una tira igual en el
         * mismo tramo; `_` no abre ni cierra a media palabra.
         */
        private fun emphasis(at: Int, to: Int): Int? {
            val c = src[at]
            var n = 0
            while (at + n < to && src[at + n] == c) n++
            val styles = when (c) {
                '~', '=' -> if (n == 2) listOf(if (c == '~') Style.Strike else Style.Highlight) else return null
                else -> when (n) {
                    1 -> listOf(Style.Italic)
                    2 -> listOf(Style.Bold)
                    3 -> listOf(Style.Bold, Style.Italic)
                    else -> return null
                }
            }
            val after = at + n
            if (after >= to || src[after].isWhitespace()) return null
            if (c == '_' && at > 0 && src[at - 1].isLetterOrDigit()) return null
            val close = closer(after, to, c, n) ?: return null
            val s = out.length
            inline(after, close)
            for (style in styles) styled(s, out.length, style)
            return close + n
        }

        /** Dónde está la tira de [n] veces [c] que cierra, saltando código, tags y links. */
        private fun closer(from: Int, to: Int, c: Char, n: Int): Int? {
            var i = from
            while (i < to) {
                val ch = src[i]
                val m = markup[i]
                when {
                    m != null && m.end <= to -> i = m.end
                    ch == '\\' -> i += 2
                    ch == '`' -> i = codeEnd(i, to)
                    ch == c -> {
                        var k = 0
                        while (i + k < to && src[i + k] == c) k++
                        val closes = k == n && i > from && !src[i - 1].isWhitespace() &&
                            !(c == '_' && i + k < to && src[i + k].isLetterOrDigit())
                        if (closes) return i
                        i += k
                    }
                    else -> i++
                }
            }
            return null
        }

        private fun codeEnd(at: Int, to: Int): Int {
            var n = 0
            while (at + n < to && src[at + n] == '`') n++
            var i = at + n
            while (i < to) {
                if (src[i] == '`') {
                    var m = 0
                    while (i + m < to && src[i + m] == '`') m++
                    if (m == n) return i + m
                    i += m
                } else {
                    i++
                }
            }
            return at + n
        }

        private fun styled(start: Int, end: Int, style: Style) {
            if (end > start) spans += Styled(start, end, style)
        }

        private fun clip(span: Span, n: Int): Span? {
            if (span.start >= n) return null
            if (span.end <= n) return span
            return when (span) {
                is Styled -> span.copy(end = n)
                is Tag -> span.copy(end = n)
                is Link -> span.copy(end = n)
                is Url -> span.copy(end = n)
            }
        }

        private fun openingFence(line: String): Fence? {
            val t = line.trimStart(' ')
            if (line.length - t.length > 3) return null
            val c = t.firstOrNull() ?: return null
            if (c != '`' && c != '~') return null
            val len = t.takeWhile { it == c }.length
            if (len < 3) return null
            if (c == '`' && t.substring(len).contains('`')) return null
            return Fence(c, len)
        }

        private fun closes(open: Fence, line: String): Boolean {
            val t = line.trim()
            return t.length >= open.len && t.all { it == open.marker }
        }
    }

    private const val RULE_WIDTH = 24
    private const val MAX_NESTING = 32
    private val HEADINGS = listOf(Style.Heading1, Style.Heading2, Style.Heading3, Style.Heading4, Style.Heading5, Style.Heading6)
    private val HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
    private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val LIST_ITEM = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(\\[[ xX]\\](?:[ \\t]+|$))?")
    private val CALLOUT = Regex("^\\[!([^\\]]+)\\][+-]?[ \\t]*(.*)$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]{1,31}:(?://)?[^\\s]")
    private val EMAIL = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")
    private val BARE_URL = Regex("^https?://[^\\s<>\\[\\]]+", RegexOption.IGNORE_CASE)

    private fun isAsciiPunctuation(c: Char) = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'
}
