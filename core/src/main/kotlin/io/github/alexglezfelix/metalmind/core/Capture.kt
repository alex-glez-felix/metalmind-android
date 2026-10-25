package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime

/** La carpeta del vault donde caen las capturas. */
interface NoteFolder {
    /** Los nombres de archivo que ya hay en la carpeta. */
    fun names(): Collection<String>

    /**
     * Crea el archivo [name] con [content] sin pisar nada: si ya existe,
     * devuelve `false` y no toca nada.
     */
    fun create(name: String, content: String): Boolean
}

sealed interface CaptureResult {
    /** La nota quedó en disco como [name]. */
    data class Saved(val name: String) : CaptureResult

    /** No hay nada que guardar: el texto está vacío o solo tiene espacios. */
    data object Empty : CaptureResult

    /** Ya están tomados `stem.md` y todos sus sufijos hasta [Capture.MAX_ATTEMPT]. */
    data class NameTaken(val stem: String) : CaptureResult
}

object Capture {
    /** `nombre.md`, `nombre-2.md`, … hasta aquí. */
    const val MAX_ATTEMPT = 999

    /**
     * Escribe [text] en [folder] como un archivo nuevo. Si el nombre está
     * tomado, prueba `-2`, `-3`, … sin pisar nunca una nota. Los nombres se
     * comparan sin distinguir mayúsculas: el almacenamiento compartido de
     * Android no las distingue, y los wikilinks de Metalmind tampoco.
     */
    fun save(folder: NoteFolder, text: String, now: LocalDateTime): CaptureResult {
        if (text.isBlank()) return CaptureResult.Empty
        val stem = NoteName.stem(text, now)
        val content = NoteContent.of(text)
        val taken = folder.names().mapTo(HashSet()) { it.lowercase() }
        for (attempt in 1..MAX_ATTEMPT) {
            val name = NoteName.fileName(stem, attempt)
            if (name.lowercase() in taken) continue
            if (folder.create(name, content)) return CaptureResult.Saved(name)
            // Alguien lo creó entre la lista y la escritura: al siguiente sufijo.
        }
        return CaptureResult.NameTaken(stem)
    }
}
