package io.github.alexglezfelix.metalmind.core

/** Lo que devuelve whisper, listo para el campo de captura. */
object Transcript {
    /** `[Música]`, `(risas)`, `[BLANK_AUDIO]`, `♪`: no es habla. */
    private val NOT_SPEECH = Regex("""^[\[(（*♪].*[\])）*♪]$|^[♪\s]+$""")

    /**
     * Frases que whisper inventa con ruido o silencio, aprendidas de subtítulos
     * de YouTube. Solo las que nadie dictaría en una nota.
     */
    private val HALLUCINATIONS = listOf(
        "amara.org",
        "subtítulos realizados por",
        "subtitulado por",
    )

    /**
     * Junta los segmentos (uno por línea) en un párrafo, sin los que no son
     * habla. Vacío si no queda nada.
     */
    fun clean(raw: String): String = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !NOT_SPEECH.matches(it) }
        .filter { segment -> HALLUCINATIONS.none { segment.lowercase().contains(it) } }
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** El texto tras poner [transcript] en lugar de la selección, y dónde queda el cursor. */
    data class Inserted(val text: String, val cursor: Int)

    /**
     * Pone [transcript] donde está el cursor (o en lugar de lo seleccionado),
     * con un espacio a cada lado si hace falta para no pegarlo a una palabra.
     */
    fun insert(text: String, selectionStart: Int, selectionEnd: Int, transcript: String): Inserted {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
        val inserted = lead + transcript + trail
        return Inserted(before + inserted + after, before.length + lead.length + transcript.length)
    }
}
