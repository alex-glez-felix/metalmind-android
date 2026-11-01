package io.github.alexglezfelix.metalmind

import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexglezfelix.metalmind.core.AudioAttachment
import java.io.File
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** El audio de una nota de voz: el m4a con el códec del sistema y su copia al vault. */
@RunWith(AndroidJUnit4::class)
class VoiceAttachmentsDeviceTest {
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

    /** Usa la carpeta del vault que la app ya tenga elegida; sin ella, se salta. */
    @Test
    fun soloVanAlVaultLosAudiosQueLaNotaEnlaza() {
        val tree = VaultSettings(context).folder
        assumeTrue("la app no tiene carpeta elegida", tree != null)
        val attachments = VoiceAttachments(context)
        val kept = attachments.add(tone, LocalDateTime.of(2001, 1, 1, 0, 0, 1))
        val dropped = attachments.add(tone, LocalDateTime.of(2001, 1, 1, 0, 0, 2))
        val text = "Prueba de voz\n${AudioAttachment.embed(kept)}"

        val saved = attachments.saveFor(context.contentResolver, tree!!, text)

        assertEquals(text, saved)
        val names = vaultAttachments(tree)
        assertTrue(names.toString(), kept in names)
        assertTrue(names.toString(), dropped !in names)
        // Ya guardado, sale de los pendientes: guardar otra vez no lo duplica.
        assertEquals(text, attachments.saveFor(context.contentResolver, tree, text))
        assertEquals(names, vaultAttachments(tree))
        File(context.cacheDir, "voz/$dropped").delete()
    }

    private fun vaultAttachments(tree: android.net.Uri): List<String> {
        val resolver = context.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        fun children(id: String) = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val folder = children(root).first { it.second == AudioAttachment.FOLDER }.first
        return children(folder).map { it.second }
    }
}
