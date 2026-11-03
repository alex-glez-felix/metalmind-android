package io.github.alexglezfelix.metalmind

import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexglezfelix.metalmind.core.AudioInbox
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** El audio de una nota de voz: el m4a con el códec del sistema y su paso al buzón del vault. */
@RunWith(AndroidJUnit4::class)
class VoiceInboxDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Tres segundos de un tono de 440 Hz a 16 kHz. */
    private val tone = ShortArray(3 * 16_000) { (sin(2 * PI * 440 * it / 16_000) * 8000).toInt().toShort() }

    @Test
    fun elM4aSeLeeComoAacConSuDuración() {
        val out = File(context.cacheDir, "prueba.m4a")
        M4aEncoder.encode(tone, out)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            val seconds = format.getLong(MediaFormat.KEY_DURATION) / 1e6
            assertTrue("dura $seconds s", seconds in 2.8..3.2)
            // 32 kbit/s: unos 12 KB, no los 96 KB del PCM.
            assertTrue("${out.length()} bytes", out.length() in 4_000..30_000)
        } finally {
            extractor.release()
            out.delete()
        }
    }

    /** Usa la carpeta del vault que la app ya tenga elegida; sin ella, se salta. Borra lo que escribe. */
    @Test
    fun cadaGrabaciónVaAlBuzónSinPisarNinguna() {
        val tree = VaultSettings(context).folder
        assumeTrue("la app no tiene carpeta elegida", tree != null)
        val inbox = VoiceInbox(context) { tree }
        val before = inboxFiles(tree!!).map { it.second }

        // Dos en el mismo segundo (o con un nombre ya tomado): ninguna pisa a otra.
        val first = inbox.save(tone)
        val second = inbox.save(tone)

        assertTrue(first != second)
        val after = inboxFiles(tree)
        assertEquals((before + first + second).sorted(), after.map { it.second }.sorted())
        // Ya en el vault, nada queda esperando en la app.
        assertEquals(0, inbox.flush())

        for ((id, name) in after) {
            if (name in before) continue
            DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        }
    }

    /** Sin carpeta, el audio no se pierde: espera en la app y pasa al vault después. */
    @Test
    fun sinCarpetaElAudioEsperaEnLaApp() {
        val tree = VaultSettings(context).folder
        assumeTrue("la app no tiene carpeta elegida", tree != null)
        var folder: android.net.Uri? = null
        val inbox = VoiceInbox(context) { folder }
        val before = inboxFiles(tree!!).map { it.second }

        val error = runCatching { inbox.save(tone) }.exceptionOrNull()
        assertTrue("$error", error?.message?.contains("quedó en la app") == true)
        folder = tree
        assertEquals(1, inbox.flush())

        val added = inboxFiles(tree).filter { it.second !in before }
        assertEquals(1, added.size)
        DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, added[0].first))
    }

    /** Los archivos de `buzon/audio/`: (id, nombre). */
    private fun inboxFiles(tree: android.net.Uri): List<Pair<String, String>> {
        val resolver = context.contentResolver
        fun children(id: String) = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (segment in AudioInbox.FOLDER) {
            id = children(id).firstOrNull { it.second == segment }?.first ?: return emptyList()
        }
        return children(id)
    }
}
