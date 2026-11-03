package io.github.alexglezfelix.metalmind

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.github.alexglezfelix.metalmind.core.AudioAttachment
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/**
 * Los audios de las notas de voz que todavía no se guardaron. Esperan en la
 * caché de la app hasta que se guarda la nota; entonces van a `adjuntos/`
 * dentro de la carpeta elegida, solo los que la nota sigue enlazando.
 */
class VoiceAttachments(context: Context) {
    private val dir = File(context.cacheDir, "voz")

    /** Codifica [samples] a m4a y devuelve el nombre con el que se enlaza. */
    fun add(samples: ShortArray, now: LocalDateTime): String {
        dir.mkdirs()
        val name = AudioAttachment.fileName(now)
        val file = File(dir, name)
        try {
            M4aEncoder.encode(samples, file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return name
    }

    private fun pending(): List<String> = dir.list()?.filter { it.endsWith(".m4a") }.orEmpty()

    /**
     * Guarda en el vault los audios que [text] enlaza y devuelve el texto con
     * los enlaces al día (cambian si el nombre ya estaba tomado). Un audio
     * guardado sale de la caché: si después falla la nota, no se duplica.
     */
    fun saveFor(resolver: ContentResolver, tree: Uri, text: String): String {
        val pending = pending()
        if (pending.none { AudioAttachment.embed(it) in text }) return text
        val folder = attachmentFolder(resolver, tree)
        val plan = AudioAttachment.plan(text, pending, names(resolver, tree, folder))
        for (write in plan.writes) {
            val source = File(dir, write.pending)
            val uri = DocumentsContract.createDocument(resolver, folder, MIME_TYPE, write.name)
                ?: throw IOException("no se pudo crear ${write.name}")
            try {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("no se pudo abrir ${write.name}")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
            } catch (e: IOException) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            source.delete()
        }
        return plan.text
    }

    /** Borra los audios de notas que nunca se guardaron, de más de un día. */
    fun prune(now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.filter { now - it.lastModified() > DAY_MS }?.forEach { it.delete() }
    }

    /** `adjuntos/` dentro de la carpeta elegida; la crea si no está. */
    private fun attachmentFolder(resolver: ContentResolver, tree: Uri): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        resolver.query(children, columns, null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == AudioAttachment.FOLDER && it.getString(2) == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, AudioAttachment.FOLDER)
            ?: throw IOException("no se pudo crear ${AudioAttachment.FOLDER}/")
    }

    private fun names(resolver: ContentResolver, tree: Uri, folder: Uri): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val cursor = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("no se pudo listar ${AudioAttachment.FOLDER}/")
        return cursor.use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
    }

    private companion object {
        val MIME_TYPE: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension("m4a") ?: "audio/mp4"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
