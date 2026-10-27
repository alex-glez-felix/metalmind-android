package io.github.alexglezfelix.metalmind.core

import java.text.Normalizer

/**
 * Un resultado de búsqueda: la nota, su título y una línea donde aparece lo
 * buscado. [titleHighlights] y [highlights] son los rangos de [title] y
 * [snippet] que coinciden, para resaltarlos.
 */
data class SearchHit(
    val id: String,
    val path: String,
    val title: String,
    val snippet: String,
    val titleHighlights: List<IntRange> = emptyList(),
    val highlights: List<IntRange> = emptyList(),
)

/**
 * La búsqueda en el vault. Cada palabra de la consulta tiene que estar en el
 * título (el nombre del archivo) o en el cuerpo de la nota, sin distinguir
 * mayúsculas ni acentos (`reunion` encuentra `Reunión`), en cualquier orden.
 * Un `#tag` no busca texto: deja solo las notas con ese tag, con las mismas
 * reglas que las sugerencias (`#rust` = `#Rust`, en el cuerpo o en `tags:`).
 * Sin nada que buscar, las notas más recientes.
 *
 * Primero las que tienen todas las palabras en el título, luego las que tienen
 * alguna y al final las demás; dentro de cada grupo, la más reciente primero.
 */
object NoteSearch {
    /** La consulta ya separada: palabras plegadas con [fold] y tags como [TagName.key]. */
    data class Query(val terms: List<String>, val tags: List<String>)

    /** Una nota lista para buscar: el título y el cuerpo plegados, calculados una vez. */
    class Searchable(val path: String, private val text: String, val tags: Set<String>) {
        val title = title(path)
        val foldedTitle = fold(title)
        private val bodyStart = Frontmatter.bodyStart(text)
        val foldedBody = fold(text.substring(bodyStart))
        val body: String get() = text.substring(bodyStart)
    }

    class Candidate(val id: String, val modified: Long, val note: Searchable)

    private const val SNIPPET_CHARS = 160
    private const val SNIPPET_CONTEXT = 40

    fun parse(query: String): Query {
        val terms = mutableListOf<String>()
        val tags = mutableListOf<String>()
        for (token in query.split(Regex("\\s+"))) {
            if (token.isEmpty() || token == "#") continue
            val name = token.removePrefix("#")
            when {
                token.startsWith('#') && TagName.isValid(name) -> tags += TagName.key(name)
                else -> terms += fold(TagName.nfc(token))
            }
        }
        return Query(terms.distinct(), tags.distinct())
    }

    fun search(notes: List<Candidate>, query: Query, limit: Int): List<SearchHit> {
        class Match(val candidate: Candidate, val rank: Int)

        val matches = notes.mapNotNull { candidate ->
            val note = candidate.note
            if (!note.tags.containsAll(query.tags)) return@mapNotNull null
            var inTitle = 0
            for (term in query.terms) {
                when {
                    term in note.foldedTitle -> inTitle++
                    term !in note.foldedBody -> return@mapNotNull null
                }
            }
            val rank = when {
                query.terms.isEmpty() || inTitle == query.terms.size -> 0
                inTitle > 0 -> 1
                else -> 2
            }
            Match(candidate, rank)
        }
        return matches
            .sortedWith(
                compareBy<Match> { it.rank }
                    .thenByDescending { it.candidate.modified }
                    .thenBy(TagName.byteOrder) { it.candidate.note.path },
            )
            .take(limit)
            .map { hit(it.candidate, query.terms) }
    }

    private fun hit(candidate: Candidate, terms: List<String>): SearchHit {
        val note = candidate.note
        val (snippet, highlights) = snippet(note.body, note.foldedBody, terms)
        return SearchHit(
            id = candidate.id,
            path = note.path,
            title = note.title,
            snippet = snippet,
            titleHighlights = occurrences(note.foldedTitle, terms),
            highlights = highlights,
        )
    }

    /**
     * La línea donde aparece primero alguna palabra, recortada alrededor si es
     * larga (`…`); sin palabras, o si solo están en el título, la primera línea
     * con texto.
     */
    internal fun snippet(body: String, folded: String, terms: List<String>): Pair<String, List<IntRange>> {
        val first = terms.mapNotNull { term -> folded.indexOf(term).takeIf { it >= 0 } }.minOrNull()
        val (lineStart, lineEnd) = if (first != null) {
            val start = folded.lastIndexOf('\n', first - 1) + 1
            start to folded.indexOf('\n', first).let { if (it < 0) folded.length else it }
        } else {
            firstTextLine(body) ?: return "" to emptyList()
        }
        // Sin la sangría ni las marcas de cita, encabezado o lista: lo que se lee.
        val textStart = lineStart + (LINE_MARKS.find(body.substring(lineStart, lineEnd))?.value?.length ?: 0)
        var start = textStart
        var end = lineEnd
        while (end > start && body[end - 1].isWhitespace()) end--
        if (end - start > SNIPPET_CHARS) {
            if (first != null && first - start > SNIPPET_CONTEXT) {
                start = minOf(first - SNIPPET_CONTEXT, end - SNIPPET_CHARS)
            }
            end = minOf(end, start + SNIPPET_CHARS)
        }
        if (start < end && body[start].isLowSurrogate()) start++
        if (end > start && body[end - 1].isHighSurrogate()) end--
        val before = if (start > textStart) "…" else ""
        val after = if (body.substring(end, lineEnd).isNotBlank()) "…" else ""
        val highlights = occurrences(folded.substring(start, end), terms)
            .map { it.first + before.length..it.last + before.length }
        return before + body.substring(start, end) + after to highlights
    }

    private val LINE_MARKS = Regex("^[\\s>]*(#{1,6}\\s+|[-*+]\\s+(\\[.]\\s+)?|[0-9]+[.)]\\s+)?")

    private fun firstTextLine(body: String): Pair<Int, Int>? {
        var at = 0
        while (at < body.length) {
            val end = body.indexOf('\n', at).let { if (it < 0) body.length else it }
            if (body.substring(at, end).any { it.isLetterOrDigit() }) return at to end
            at = end + 1
        }
        return null
    }

    /** Dónde aparece cada palabra en [folded], sin rangos encimados. */
    private fun occurrences(folded: String, terms: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var at = folded.indexOf(term)
            while (at >= 0) {
                ranges += at until at + term.length
                at = folded.indexOf(term, at + term.length)
            }
        }
        val merged = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /** El título de una nota: el nombre del archivo sin `.md` ni carpetas. */
    fun title(path: String): String {
        val name = path.substringAfterLast('/')
        return if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
    }

    /**
     * Para comparar sin mayúsculas ni acentos, y del mismo largo que [s], así
     * una posición en lo plegado es la misma en el original: cada letra se
     * cambia por su base (`É` → `e`, `ñ` → `n`). Espera el texto en NFC.
     */
    fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = foldChar(s[i])
        return String(out)
    }

    private fun foldChar(c: Char): Char {
        if (c < '\u0080') return c.lowercaseChar()
        if (c.isSurrogate()) return c
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
        return base.lowercaseChar()
    }
}
