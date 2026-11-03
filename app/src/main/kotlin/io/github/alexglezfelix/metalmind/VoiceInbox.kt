package io.github.alexglezfelix.metalmind

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.github.alexglezfelix.metalmind.core.AudioInbox
import io.github.alexglezfelix.metalmind.core.AudioSink
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/**
 * El buzón de audio en el vault: `buzon/audio/` dentro de la carpeta que dé
 * [folder]. Cada grabación se codifica a m4a en el almacenamiento privado de
 * la app y enseguida se mueve ahí, sin pisar nunca un archivo. Si no se puede
 * (sin permiso, carpeta borrada), se queda en la app y [flush] la mueve la
 * próxima vez: no se pierde.
 */
class VoiceInbox(context: Context, private val folder: () -> Uri?) : AudioSink {
    private val resolver: ContentResolver = context.contentResolver
    private val pendingDir = File(context.filesDir, "voz/pendientes")

    /** Donde esperaban los audios antes, hasta guardar la nota (#16). */
    private val legacyDir = File(context.cacheDir, "voz")

    override fun save(samples: ShortArray): String {
        pendingDir.mkdirs()
        val local = File(pendingDir, AudioInbox.fileName(LocalDateTime.now(), pendingDir.list().orEmpty().toList()))
        try {
            M4aEncoder.encode(samples, local)
        } catch (e: Exception) {
            local.delete()
            throw e
        }
        try {
            val tree = folder() ?: throw IOException("no hay carpeta elegida")
            return move(local, tree)
        } catch (e: Exception) {
            // SecurityException si se perdió el permiso, o lo que relance el proveedor.
            throw IOException("${e.message ?: e.javaClass.simpleName}. El audio quedó en la app y pasará a ${AudioInbox.PATH}/ al volver a abrir la captura", e)
        }
    }

    /**
     * Mueve al buzón los audios que no alcanzaron a llegar (y los que
     * esperaban en la caché desde antes de esta versión). Devuelve cuántos.
     */
    fun flush(): Int {
        val waiting = listOf(pendingDir, legacyDir)
            .flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && AudioInbox.isAudio(f.name) } }
        if (waiting.isEmpty()) return 0
        val tree = folder() ?: return 0
        for (file in waiting) move(file, tree)
        return waiting.size
    }

    /** Copia [local] a `buzon/audio/` con un nombre libre, la borra de la app y devuelve el nombre. */
    private fun move(local: File, tree: Uri): String {
        val dir = inboxFolder(tree)
        val taken = names(tree, dir).toMutableList()
        repeat(MAX_TRIES) {
            val name = AudioInbox.nextFree(local.name, taken)
            val uri = DocumentsContract.createDocument(resolver, dir, MIME_TYPE, name)
                ?: throw IOException("no se pudo crear $name")
            if (displayName(uri) != name) {
                // El proveedor no pisa: si el nombre apareció entretanto, lo
                // cambia (`… (1).m4a`). Ese no es el nuestro: se borra y se
                // prueba el siguiente.
                DocumentsContract.deleteDocument(resolver, uri)
                taken += name
                return@repeat
            }
            try {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("no se pudo abrir $name")
                out.use { o -> local.inputStream().use { it.copyTo(o) } }
            } catch (e: IOException) {
                // Nada a medias en el vault; el audio sigue en la app.
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            local.delete()
            return name
        }
        throw IOException("no se encontró un nombre libre para ${local.name}")
    }

    /** `buzon/audio/` dentro de la carpeta elegida; crea lo que falte. */
    private fun inboxFolder(tree: Uri): Uri {
        var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (segment in AudioInbox.FOLDER) {
            dir = child(tree, dir, segment)
                ?: DocumentsContract.createDocument(resolver, dir, Document.MIME_TYPE_DIR, segment)
                ?: throw IOException("no se pudo crear $segment/")
        }
        return dir
    }

    private fun child(tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        resolver.query(children, columns, null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name && it.getString(2) == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
        }
        return null
    }

    private fun names(tree: Uri, folder: Uri): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val cursor = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("no se pudo listar ${AudioInbox.PATH}/")
        return cursor.use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        val MIME_TYPE: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension("m4a") ?: "audio/mp4"
        const val MAX_TRIES = 100
    }
}
