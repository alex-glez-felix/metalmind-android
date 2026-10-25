package io.github.alexglezfelix.metalmind.core

/**
 * Los tags de una nota, como los ve Metalmind (`scanner.rs`, `code.rs` y
 * `frontmatter.rs`): los de `tags:` en el frontmatter y los `#tag` del cuerpo
 * que van al inicio de línea o tras un espacio, sin contar los de bloques de
 * código, código en línea, comentarios `%% %%`, fórmulas `$…$` y `$$…$$`,
 * links (`[[…]]` y `[texto](destino)`) ni los escapados (`\#`).
 *
 * Del YAML solo lee `tags:`: lista en bloque, entre corchetes o un solo valor.
 * Un YAML roto que Metalmind descartaría entero aquí se lee hasta donde se
 * entiende; para sugerir tags, sobra.
 */
object TagScanner {
    /** Cada tag tal como está escrito, sin `#`, una vez por aparición. */
    fun tags(text: String): List<String> {
        val out = mutableListOf<String>()
        val body = frontmatter(text, out)
        Body(text, body, out).run()
        return out
    }

    // --- Frontmatter ---

    /** Lee `tags:` del frontmatter, si hay, y devuelve dónde empieza el cuerpo. */
    private fun frontmatter(text: String, out: MutableList<String>): Int {
        val split = Frontmatter.split(text) ?: return Frontmatter.bodyStart(text)
        readTags(split.yaml, out)
        return split.body
    }

    private fun readTags(yaml: String, out: MutableList<String>) {
        val lines = yaml.split('\n').map { it.trimEnd('\r') }
        val at = lines.indexOfFirst { it.startsWith("tags:") }
        if (at < 0) return
        val rest = withoutComment(lines[at].removePrefix("tags:")).trim()
        val values = when {
            rest.startsWith("[") -> flowList(lines.drop(at).joinToString("\n").substringAfter('['))
            rest.isEmpty() -> blockList(lines.drop(at + 1))
            else -> listOfNotNull(scalar(rest))
        }
        for (value in values) {
            val name = value.removePrefix("#")
            if (TagName.isValid(name)) out += name
        }
    }

    /** Los `- valor` que siguen a `tags:`, hasta la siguiente clave. */
    private fun blockList(lines: List<String>): List<String> {
        val values = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!line[0].isWhitespace() && !trimmed.startsWith("-")) break
            if (trimmed == "-" || trimmed.startsWith("- ")) {
                scalar(withoutComment(trimmed.removePrefix("-")).trim())?.let(values::add)
            } else {
                break
            }
        }
        return values
    }

    /** `[a, "b", 'c']`, aunque ocupe varias líneas. */
    private fun flowList(text: String): List<String> {
        val values = mutableListOf<String>()
        val item = StringBuilder()
        var quote: Char? = null
        var i = 0
        fun flush() {
            scalar(withoutComment(item.toString()).trim())?.let(values::add)
            item.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> {
                    item.append(c)
                    if (c == '\\' && quote == '"' && i + 1 < text.length) {
                        item.append(text[++i])
                    } else if (c == quote) {
                        quote = null
                    }
                }
                c == '"' || c == '\'' -> { quote = c; item.append(c) }
                c == '#' && (item.isEmpty() || item.last().isWhitespace()) -> {
                    // Un comentario hasta el final de la línea.
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                c == ',' -> flush()
                c == ']' -> { flush(); return values }
                else -> item.append(c)
            }
            i++
        }
        return values
    }

    /**
     * Un valor de YAML como texto, como lo lee Metalmind (YAML 1.2): sin
     * comillas ni espacios alrededor, y los números y booleanos como su valor
     * (`007` → `7`, `0x1F` → `31`, `1.0` → `1`, `True` → `true`).
     */
    private fun scalar(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
        }
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
            return raw.substring(1, raw.length - 1).replace("''", "'").trim()
        }
        if (raw in NULLS) return null
        if (raw in TRUE) return "true"
        if (raw in FALSE) return "false"
        when {
            INT.matches(raw) -> raw.toLongOrNull()?.let { return it.toString() }
            HEX.matches(raw) -> raw.drop(2).toLongOrNull(16)?.let { return it.toString() }
            OCT.matches(raw) -> raw.drop(2).toLongOrNull(8)?.let { return it.toString() }
            FLOAT.matches(raw) -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                // Como `f64` de Rust: sin exponente ni `.0` (`1e3` → `1000`).
                return java.math.BigDecimal(it.toString()).stripTrailingZeros().toPlainString()
            }
        }
        return raw
    }

    private val NULLS = setOf("~", "null", "Null", "NULL")
    private val TRUE = setOf("true", "True", "TRUE")
    private val FALSE = setOf("false", "False", "FALSE")
    private val INT = Regex("[-+]?[0-9]+")
    private val HEX = Regex("0x[0-9a-fA-F]+")
    private val OCT = Regex("0o[0-7]+")
    private val FLOAT = Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?")

    /** Sin el comentario: un `#` al principio o tras un espacio, fuera de comillas. */
    private fun withoutComment(s: String): String {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> if (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '-') quote = c
                c == '#' && (i == 0 || s[i - 1].isWhitespace()) -> return s.substring(0, i)
            }
        }
        return s
    }

    // --- Cuerpo ---

    private class Body(val text: String, val body: Int, val out: MutableList<String>) {
        fun run() {
            var at = body
            while (at < text.length) {
                at = (if (lineStart(at)) fencedBlockEnd(at) else null) ?: step(at)
            }
        }

        private fun lineStart(at: Int) = at == body || (at > body && text[at - 1] == '\n')

        private fun step(at: Int): Int {
            val next = text.getOrNull(at + 1)
            val c = text[at]
            return when {
                c == '\\' && next != null && isAsciiPunctuation(next) -> at + 2
                c == '`' -> codeSpan(at)
                c == '%' && next == '%' -> commentEnd(at)
                c == '$' -> math(at, display = next == '$')
                c == '!' && next == '[' -> link(at + 1) ?: (at + 1)
                c == '[' -> link(at) ?: (at + 1)
                c == '#' && afterSpace(at) -> tag(at)
                else -> at + 1
            }
        }

        private fun afterSpace(at: Int): Boolean {
            if (at == body) return true
            val before = Character.codePointBefore(text, at)
            return Character.isWhitespace(before) || Character.isSpaceChar(before)
        }

        private fun tag(at: Int): Int {
            var end = at + 1
            while (end < text.length) {
                val cp = text.codePointAt(end)
                if (!TagName.isTagChar(cp)) break
                end += Character.charCount(cp)
            }
            val name = text.substring(at + 1, end)
            if (!TagName.isValid(name)) return at + 1
            out += name
            return end
        }

        private fun commentEnd(at: Int): Int = text.indexOf("%%", at + 2).let { if (it < 0) text.length else it + 2 }

        private fun isEscape(i: Int): Boolean =
            text[i] == '\\' && i + 1 < text.length && isAsciiPunctuation(text[i + 1])

        /**
         * Una fórmula: `$$…$$` llega hasta el cierre o, sin él, al final del
         * texto. `$…$` no lleva espacio tras el que abre ni antes del que cierra,
         * cierra en la misma línea y no con un `$` seguido de un dígito (`$5`).
         */
        private fun math(at: Int, display: Boolean): Int {
            if (display) {
                var i = at + 2
                while (i + 1 < text.length) {
                    when {
                        isEscape(i) -> i += 2
                        text[i] == '$' && text[i + 1] == '$' -> return i + 2
                        else -> i++
                    }
                }
                return text.length
            }
            val content = at + 1
            if (content >= text.length || Character.isWhitespace(text.codePointAt(content))) return at + 1
            val lineStart = text.lastIndexOf('\n', at) + 1
            val lineEnd = lineEnd(text, at)
            var i = lineStart
            while (i < lineEnd) {
                when {
                    isEscape(i) -> i += 2
                    text[i] == '$' -> {
                        val closes = i > lineStart && !Character.isWhitespace(Character.codePointBefore(text, i)) &&
                            text.getOrNull(i + 1)?.let { it in '0'..'9' } != true
                        if (closes && i > content) return i + 1
                        i++
                    }
                    else -> i++
                }
            }
            return at + 1
        }

        /** Dónde termina el link cuyo `[` está en [open], o `null` si no es un link. */
        private fun link(open: Int): Int? {
            if (text.getOrNull(open + 1) == '[') wikilink(open)?.let { return it }
            return markdownLink(open)
        }

        /** `[[destino|alias]]` en una línea, sin otro `[[` dentro. */
        private fun wikilink(open: Int): Int? {
            val body = open + 2
            var i = body
            while (i + 1 < text.length) {
                val (a, b) = text[i] to text[i + 1]
                if (a == ']' && b == ']') break
                if ((a == '[' && b == '[') || a == '\n') return null
                i++
            }
            if (i + 1 >= text.length) return null
            val inner = text.substring(body, i)
            val destination = inner.substringBefore('|').removeSuffix("\\")
            return if (destination.isEmpty()) null else i + 2
        }

        /** `[texto](destino "título")`, con los corchetes emparejados en la línea. */
        private fun markdownLink(open: Int): Int? {
            val labelClose = labelClose(open) ?: return null
            val paren = labelClose + 1
            if (text.getOrNull(paren) != '(') return null
            val destEnd = destinationEnd(skipSpaces(paren + 1)) ?: return null
            return closingParen(destEnd)
        }

        private fun labelClose(open: Int): Int? {
            val lineEnd = lineEnd(text, open)
            val opens = ArrayDeque<Int>()
            var i = open
            while (i < lineEnd) {
                when {
                    isEscape(i) -> i += 2
                    text[i] == '`' -> i = codeSpan(i)
                    text[i] == '%' && text.getOrNull(i + 1) == '%' -> i = commentEnd(i)
                    text[i] == '[' -> { opens.addLast(i); i++ }
                    text[i] == ']' -> {
                        if (opens.removeLastOrNull() == open) return i
                        i++
                    }
                    else -> i++
                }
            }
            return null
        }

        private fun destinationEnd(from: Int): Int? {
            if (text.getOrNull(from) == '<') {
                var i = from + 1
                while (i < text.length) {
                    when {
                        isEscape(i) -> i += 2
                        text[i] == '>' -> return i + 1
                        text[i] == '<' || text[i] == '\n' -> return null
                        else -> i++
                    }
                }
                return null
            }
            var depth = 0
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    isEscape(i) -> i++
                    c == '(' && depth == MAX_PAREN_DEPTH -> return null
                    c == '(' -> depth++
                    c == ')' && depth == 0 -> break
                    c == ')' -> depth--
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' -> break
                }
                i++
            }
            return minOf(i, text.length)
        }

        private fun closingParen(from: Int): Int? {
            var at = skipSpaces(from)
            val titleClose = when (text.getOrNull(at)) {
                '"' -> '"'
                '\'' -> '\''
                '(' -> ')'
                else -> null
            }
            if (titleClose != null) at = skipSpaces(titleEnd(at + 1, titleClose) ?: return null)
            return if (text.getOrNull(at) == ')') at + 1 else null
        }

        private fun titleEnd(from: Int, close: Char): Int? {
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    isEscape(i) -> i++
                    c == '\n' -> return null
                    c == '(' && close == ')' -> return null
                    c == close -> return i + 1
                }
                i++
            }
            return null
        }

        private fun skipSpaces(from: Int): Int {
            var i = from
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            return i
        }

        /** Un `` `código` `` cierra con una racha de backticks igual de larga, dentro del párrafo. */
        private fun codeSpan(at: Int): Int {
            val len = backtickRun(at)
            val runEnd = at + len
            val paragraphEnd = paragraphEnd(at)
            var i = runEnd
            while (i < paragraphEnd) {
                if (text[i] == '`') {
                    val run = minOf(backtickRun(i), paragraphEnd - i)
                    if (run == len) return i + run
                    i += run
                } else {
                    i++
                }
            }
            return runEnd
        }

        private fun backtickRun(at: Int): Int {
            var i = at
            while (i < text.length && text[i] == '`') i++
            return i - at
        }

        private fun paragraphEnd(from: Int): Int {
            var at = from
            while (true) {
                val nl = text.indexOf('\n', at)
                if (nl < 0) return text.length
                at = nl + 1
                val line = text.substring(at, lineEnd(text, at))
                if (line.isBlank() || openingFence(line) != null) return at
            }
        }

        /** Si en `at` abre un bloque ```` ``` ```` o `~~~`, dónde termina (sin cerrar, al final del texto). */
        private fun fencedBlockEnd(at: Int): Int? {
            val open = openingFence(text.substring(at, lineEnd(text, at))) ?: return null
            var line = nextLine(at)
            while (line < text.length) {
                if (closes(open, text.substring(line, lineEnd(text, line)))) return nextLine(line)
                line = nextLine(line)
            }
            return text.length
        }

        private fun nextLine(at: Int): Int = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
    }

    private const val MAX_PAREN_DEPTH = 32

    private data class Fence(val marker: Char, val len: Int)

    /** Una racha de 3 o más ``` ` ``` o `~`, tras sangría, `>` de cita o marcas de lista. */
    private fun fence(line: String): Pair<Fence, String>? {
        var rest = line.trimStart(' ', '\t', '>')
        while (true) {
            rest = stripListMarker(rest)?.trimStart(' ', '\t', '>') ?: break
        }
        val marker = rest.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val len = rest.takeWhile { it == marker }.length
        return if (len >= 3) Fence(marker, len) to rest.substring(len) else null
    }

    private fun stripListMarker(line: String): String? {
        val digits = line.takeWhile { it in '0'..'9' }.length
        val marker = when (line.getOrNull(digits)) {
            '.', ')' -> if (digits in 1..9) digits + 1 else return null
            '-', '*', '+' -> if (digits == 0) 1 else return null
            else -> return null
        }
        val after = line.substring(marker)
        return if (after.startsWith(' ') || after.startsWith('\t')) after else null
    }

    private fun openingFence(line: String): Fence? {
        val (fence, info) = fence(line) ?: return null
        return if (fence.marker != '`' || '`' !in info) fence else null
    }

    private fun closes(open: Fence, line: String): Boolean {
        val (close, rest) = fence(line) ?: return false
        return close.marker == open.marker && close.len >= open.len && rest.isBlank()
    }

    private fun lineEnd(text: String, start: Int): Int =
        text.indexOf('\n', start).let { if (it < 0) text.length else it }

    private fun isAsciiPunctuation(c: Char) = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'
}
