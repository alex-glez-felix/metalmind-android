package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Las grabaciones: cada nota de voz deja su audio en `recordings/`, dentro
 * de la carpeta elegida, haya o no transcripción. El audio es la fuente de
 * verdad; el texto, un derivado que se puede regenerar.
 *
 * El contrato con quien transcribe fuera del teléfono (README, "Audios
 * pendientes"): un audio está transcrito si alguna nota lo nombra, como lo
 * hace el enlace `![[2026-11-01-093005.m4a]]` que escribe la app. Si ninguna
 * lo nombra, está pendiente.
 */
object AudioRecordings {
    /** La carpeta de las grabaciones, dentro de la carpeta elegida. */
    const val FOLDER = "recordings"

    const val EXTENSION = ".m4a"

    /**
     * Se le añade al nombre mientras se codifica: si el proceso muere a mitad,
     * el archivo no es un audio ([isAudio]) y nunca llega al vault.
     */
    const val PARTIAL_SUFFIX = ".part"

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    /**
     * El nombre para un audio grabado en [now]: la fecha y la hora, o `-2`,
     * `-3`, … si ya está en [taken] (sin distinguir mayúsculas). Nunca uno
     * que ya exista.
     */
    fun fileName(now: LocalDateTime, taken: Collection<String> = emptyList()): String =
        nextFree(STAMP.format(now) + EXTENSION, taken)

    /** [name] o, si ya está en [taken], el siguiente libre: `-2`, `-3`, … */
    fun nextFree(name: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        val stem = name.removeSuffix(EXTENSION)
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) name else "$stem-$it$EXTENSION" }
            .first { it.lowercase() !in used }
    }

    /** El enlace como lo escribe Metalmind: solo el nombre, que es único. */
    fun embed(name: String): String = "![[$name]]"

    /**
     * Si [name] es una grabación. No lo son los ocultos (Syncthing
     * escribe `.syncthing.<nombre>.tmp` mientras copia) ni otros formatos.
     */
    fun isAudio(name: String): Boolean = !name.startsWith(".") && name.endsWith(EXTENSION, ignoreCase = true)

    /**
     * Si [note] nombra el audio [name]: el nombre entero, sin distinguir
     * mayúsculas, en cualquier forma de enlace (`![[…]]`, `[[…|alias]]`,
     * `[…](recordings/…)`) o suelto. `…-093005.m4a` no cuenta como
     * `…-093005-2.m4a`, ni al revés.
     */
    fun names(note: String, name: String): Boolean = mention(name).containsMatchIn(note)

    /** Si alguna de [notes] nombra el audio [name]: entonces ya está transcrito. */
    fun isTranscribed(name: String, notes: Sequence<String>): Boolean {
        val pattern = mention(name)
        return notes.any { pattern.containsMatchIn(it) }
    }

    /** Los audios de [files] (nombres de `recordings/`) que ninguna de [notes] nombra. */
    fun pending(files: Collection<String>, notes: Sequence<String>): List<String> {
        val audios = files.filter(::isAudio)
        val named = HashSet<String>()
        val patterns = audios.associateWith(::mention)
        for (note in notes) {
            for ((audio, pattern) in patterns) {
                if (audio !in named && pattern.containsMatchIn(note)) named += audio
            }
            if (named.size == audios.size) break
        }
        return audios.filter { it !in named }
    }

    /** El nombre sin letra, número, `.`, `_` ni `-` pegados delante, ni letra, número, `_` o `-` detrás. */
    private fun mention(name: String) =
        Regex("""(?<![\p{L}\p{N}._-])${Regex.escape(name)}(?![\p{L}\p{N}_-])""", RegexOption.IGNORE_CASE)
}
