package io.github.alexglezfelix.metalmind

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.alexglezfelix.metalmind.core.NoteFile
import io.github.alexglezfelix.metalmind.core.VaultIndex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Lo leído de los `.md` de la carpeta elegida (y sus subcarpetas): los tags
 * para el autocompletado y el texto para la búsqueda. El índice se guarda en
 * la caché de la app, nunca en el vault: al abrir se lee de ahí y después
 * solo se releen las notas que cambiaron.
 */
class VaultCache(context: Context, private val tree: Uri) {
    private val resolver = context.contentResolver
    private val cache = File(context.cacheDir, "vault-${hash(tree.toString())}.bin")

    /** El índice de la última vez; vacío si no hay o está roto. */
    fun cached(): VaultIndex {
        // La de antes de la búsqueda, solo con tags: la reemplaza esta.
        cache.resolveSibling(cache.name.replaceFirst("vault-", "tags-")).delete()
        return try {
            cache.inputStream().use(VaultIndex::read)
        } catch (_: IOException) {
            VaultIndex.EMPTY
        }
    }

    /** [index] al día con el vault, y guardado si cambió. */
    fun refresh(index: VaultIndex): VaultIndex {
        val next = index.refresh(listNotes(), ::read)
        if (next !== index) save(next)
        return next
    }

    private fun save(index: VaultIndex) {
        // Entero o nada: otra captura abierta (la de compartir) puede estar leyéndolo.
        val tmp = File.createTempFile("tags", ".tmp", cache.parentFile)
        try {
            tmp.outputStream().use(index::write)
            if (!tmp.renameTo(cache)) throw IOException("no se pudo guardar $cache")
        } finally {
            tmp.delete()
        }
    }

    /** Los `.md` de la carpeta y sus subcarpetas, sin las ocultas (`.git`, `.stversions`, …). */
    private fun listNotes(): List<NoteFile> {
        val notes = mutableListOf<NoteFile>()
        // Cada carpeta pendiente con su ruta dentro de la elegida (`proyectos/`).
        val pending = ArrayDeque<Pair<String, String>>()
        pending += DocumentsContract.getTreeDocumentId(tree) to ""
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty()) {
            val (folder, prefix) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith('.')) continue
                    when {
                        c.getString(2) == Document.MIME_TYPE_DIR -> pending += id to "$prefix$name/"
                        name.endsWith(".md", ignoreCase = true) ->
                            notes += NoteFile(id, c.getLong(3), c.getLong(4), "$prefix$name")
                    }
                }
            }
        }
        return notes
    }

    private fun read(note: NoteFile): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, note.id)
        return try {
            resolver.openInputStream(uri)?.use { input ->
                // Hasta el límite aunque el proveedor no sepa el tamaño: un
                // "markdown" enorme no es una nota, se cuenta vacía.
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (bytes.size() <= MAX_NOTE_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                }
                if (bytes.size() > MAX_NOTE_BYTES) "" else bytes.toString(Charsets.UTF_8.name())
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // SecurityException, o lo que relance el proveedor: se reintenta la próxima vez.
            null
        }
    }

    private companion object {
        const val MAX_NOTE_BYTES = 4 * 1024 * 1024

        fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
