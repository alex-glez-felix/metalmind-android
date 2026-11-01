package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * El audio de una nota de voz, guardado junto a la nota en `adjuntos/` y
 * enlazado como en Metalmind: `![[2026-11-01-093005.m4a]]`.
 */
object AudioAttachment {
    const val FOLDER = "adjuntos"
    private const val EXTENSION = ".m4a"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun fileName(now: LocalDateTime): String = STAMP.format(now) + EXTENSION

    fun embed(name: String): String = "![[$name]]"

    /** Qué audio pendiente se escribe y con qué nombre. */
    data class Write(val pending: String, val name: String)

    data class Plan(val text: String, val writes: List<Write>)

    /**
     * Decide qué audios de [pending] se guardan con la nota: solo los que
     * siguen enlazados en [text] (si se borró el enlace, se descarta). Si el
     * nombre ya está en `adjuntos/` ([taken], sin distinguir mayúsculas), usa
     * `-2`, `-3`, … y cambia el enlace en el texto.
     */
    fun plan(text: String, pending: Collection<String>, taken: Collection<String>): Plan {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        var result = text
        val writes = mutableListOf<Write>()
        for (name in pending) {
            if (embed(name) !in result) continue
            val stem = name.removeSuffix(EXTENSION)
            val final = generateSequence(1) { it + 1 }
                .map { if (it == 1) name else "$stem-$it$EXTENSION" }
                .first { it.lowercase() !in used }
            used += final.lowercase()
            if (final != name) result = result.replace(embed(name), embed(final))
            writes += Write(name, final)
        }
        return Plan(result, writes)
    }
}
