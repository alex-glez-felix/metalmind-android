package io.github.alexglezfelix.metalmind

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.alexglezfelix.metalmind.core.ModelCheck
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * El modelo de whisper, en el almacenamiento privado de la app (nunca en el
 * vault ni en la nube). Hay uno a la vez.
 */
class VoiceModelStore(context: Context) {
    private val dir = File(context.filesDir, "voz")
    private val file = File(dir, "modelo.bin")
    private val prefs = context.getSharedPreferences("voz", Context.MODE_PRIVATE)

    val model: File? get() = file.takeIf { it.isFile }

    /** Cómo se llama el modelo instalado: el de la lista, o el nombre del archivo elegido. */
    val label: String? get() = if (file.isFile) prefs.getString(KEY_LABEL, null) ?: file.name else null

    /** Si se guarda el audio junto a la nota. Apagado de entrada. */
    var saveAudio: Boolean
        get() = prefs.getBoolean(KEY_SAVE_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_AUDIO, value).apply()

    /**
     * Copia el archivo [uri] como modelo, calculando su SHA-256 por el camino.
     * Solo reemplaza el modelo anterior si el nuevo sirve. Bloquea: llámala
     * fuera del hilo principal.
     */
    fun import(resolver: ContentResolver, uri: Uri, onProgress: (Float) -> Unit): ModelCheck {
        val (name, size) = describe(resolver, uri)
        dir.mkdirs()
        val partial = File(dir, "modelo.bin.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(4)
        var headerRead = 0
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("no se pudo abrir el archivo")
            input.use { inp ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        if (headerRead < header.size) {
                            val take = minOf(n, header.size - headerRead)
                            buffer.copyInto(header, headerRead, 0, take)
                            headerRead += take
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        copied += n
                        if (size > 0) onProgress((copied.toFloat() / size).coerceAtMost(1f))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val check = ModelCheck.of(name, header.copyOf(headerRead), sha)
            if (check is ModelCheck.Known || check is ModelCheck.Unknown) {
                if (!partial.renameTo(file)) throw IOException("no se pudo guardar el modelo")
                val label = (check as? ModelCheck.Known)?.model?.fileName ?: name ?: file.name
                prefs.edit().putString(KEY_LABEL, label).apply()
            }
            return check
        } finally {
            partial.delete()
        }
    }

    fun delete() {
        file.delete()
        prefs.edit().remove(KEY_LABEL).apply()
    }

    private fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long> =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = it.getString(0)
            val size = if (it.isNull(1)) -1L else it.getLong(1)
            name to size
        } ?: (null to -1L)

    private companion object {
        const val KEY_LABEL = "modelo"
        const val KEY_SAVE_AUDIO = "guardar_audio"
    }
}
