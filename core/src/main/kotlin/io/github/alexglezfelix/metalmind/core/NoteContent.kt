package io.github.alexglezfelix.metalmind.core

/**
 * Lo que se escribe en el archivo: el texto tal cual, sin frontmatter ni tags
 * inventados (sin tags, la nota cae al buzón). Como `metalmind add`, solo se
 * juntan los saltos de línea del final en uno, y el archivo siempre termina
 * en `\n`.
 */
object NoteContent {
    fun of(text: String): String {
        // Solo los saltos: dos espacios antes de `\n` son un salto de línea de markdown.
        val body = text.trimEnd('\n', '\r')
        return "$body\n"
    }
}
