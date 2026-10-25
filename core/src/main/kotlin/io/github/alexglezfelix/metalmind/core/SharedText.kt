package io.github.alexglezfelix.metalmind.core

/**
 * La captura que sale de "Compartir con Metalmind". Al compartir un link, el
 * navegador suele mandar el título de la página como asunto y la URL como
 * texto: el título va en la primera línea, así da el nombre del archivo
 * (`título-de-la-página.md`) en vez de `https-…`.
 */
object SharedText {
    fun toNote(subject: CharSequence?, text: CharSequence?): String {
        val subject = subject?.toString()?.trim().orEmpty()
        val text = text?.toString()?.trim('\n', '\r').orEmpty()
        return when {
            text.isBlank() -> subject
            subject.isEmpty() || text.contains(subject) -> text
            else -> "$subject\n$text"
        }
    }
}
