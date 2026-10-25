package io.github.alexglezfelix.metalmind

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.github.alexglezfelix.metalmind.core.NoteFolder
import java.io.IOException

/**
 * La carpeta del vault que el usuario eligió con el selector del sistema
 * (Storage Access Framework). Solo lista nombres y crea archivos nuevos: nunca
 * abre ni reescribe uno que ya existía.
 */
class SafNoteFolder(private val resolver: ContentResolver, private val tree: Uri) : NoteFolder {
    private val folderId = DocumentsContract.getTreeDocumentId(tree)
    private val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)

    override fun names(): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("no se pudo listar la carpeta")
        return cursor.use {
            buildList(it.count) { while (it.moveToNext()) add(it.getString(0)) }
        }
    }

    override fun create(name: String, content: String): Boolean {
        val uri = DocumentsContract.createDocument(resolver, folder, MIME_TYPE, name)
            ?: throw IOException("no se pudo crear $name")
        val created = displayName(uri)
        if (created != name) {
            // El proveedor no pisa: si el nombre ya estaba, lo cambia (`idea (1).md`).
            // Ese archivo no es el nuestro, así que se borra y se prueba el siguiente.
            DocumentsContract.deleteDocument(resolver, uri)
            if (names().none { it.equals(name, ignoreCase = true) }) {
                throw IOException("la carpeta guardó $name como $created")
            }
            return false
        }
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("no se pudo abrir $name")
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            // Nada a medias: sin contenido, el archivo vacío tampoco se queda.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
        return true
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        /**
         * El tipo que el proveedor asocia a `.md`: con otro, le agregaría su propia
         * extensión al nombre (`idea.md.txt`).
         */
        private val MIME_TYPE: String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension("md") ?: "application/octet-stream"

        /** Un nombre corto para la carpeta: `primary:Notas/Buzón` → `Notas/Buzón`. */
        fun label(tree: Uri): String {
            val id = DocumentsContract.getTreeDocumentId(tree)
            return id.substringAfter(':').ifEmpty { id }
        }
    }
}
