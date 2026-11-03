package io.github.alexglezfelix.metalmind

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexglezfelix.metalmind.core.PcmSource
import io.github.alexglezfelix.metalmind.core.VoiceRecorder
import io.github.alexglezfelix.metalmind.core.VoiceSession
import io.github.alexglezfelix.metalmind.core.VoiceState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whisper de verdad en el dispositivo: la nota de voz de punta a punta, con un
 * WAV en lugar del micrófono (el del emulador no recibe audio del host).
 *
 * Necesita, en la carpeta externa de la app
 * (`/sdcard/Android/data/io.github.alexglezfelix.metalmind/files/`), un modelo
 * `modelo.bin` y una grabación `voz.wav` (16 kHz, mono, 16 bits) con el texto
 * de [EXPECTED]. Sin ellos, el test se salta. Ver README, "Probar la
 * transcripción en el dispositivo".
 *
 * Con `-e threads 6` mide con ese número de hilos en vez del que elige la app.
 */
@RunWith(AndroidJUnit4::class)
class WhisperDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Una grabación leída del WAV, en trozos como los del micrófono y sin esperar. */
    private class WavSource(file: File) : PcmSource {
        private val samples: ShortArray = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            b.position(44)
            ShortArray(b.remaining() / 2) { b.short }
        }
        private var at = 0
        var onEnd: () -> Unit = {}

        override fun start() {}

        override fun read(buffer: ShortArray): Int {
            val n = minOf(buffer.size, samples.size - at)
            if (n <= 0) {
                onEnd()
                return 0
            }
            samples.copyInto(buffer, 0, at, at + n)
            at += n
            return n
        }

        override fun stop() {}

        override fun release() {}
    }

    @Test
    fun transcribeTreintaSegundosDeVozEnEspañol() = runBlocking {
        val dir = context.getExternalFilesDir(null)!!
        val model = File(dir, "modelo.bin")
        val wav = File(dir, "voz.wav")
        assumeTrue("faltan $model o $wav", model.isFile && wav.isFile)

        val threads = InstrumentationRegistry.getArguments().getString("threads")?.toInt()
        val source = WavSource(wav)
        lateinit var session: VoiceSession
        session = VoiceSession(
            scope = this,
            newRecorder = { VoiceRecorder(source).also { rec -> source.onEnd = rec::stop } },
            transcriber = WhisperTranscriber({ model }, context.applicationInfo.nativeLibraryDir, threads),
            // Sin tocar el vault: aquí solo importa whisper.
            sink = { "prueba.m4a" },
        )
        val start = System.nanoTime()
        session.start()
        val end = session.state.first {
            it !is VoiceState.Recording && it !is VoiceState.Saving && it !is VoiceState.Transcribing
        }
        val seconds = (System.nanoTime() - start) / 1e9
        val text = (end as? VoiceState.Done)?.text ?: error("terminó en $end")
        Log.i(TAG, "%.1f s con %s, %d hilos: %s".format(seconds, model.length(), threads ?: WhisperTranscriber.threads, text))
        Log.i(TAG, "ggml: ${WhisperTranscriber.backend}")
        // Sin optimizar, whisper tarda minutos en lo que debería tardar segundos (#17).
        assertTrue("ggml sin optimizar: ${WhisperTranscriber.backend}", WhisperTranscriber.backend.startsWith("optimizado"))

        val words = EXPECTED.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length > 3 }.toSet()
        val heard = text.lowercase().split(Regex("""[^\p{L}]+""")).toSet()
        val hits = words.count { it in heard }
        Log.i(TAG, "palabras: $hits de ${words.size}")
        assertTrue("solo $hits de ${words.size} palabras: $text", hits >= words.size * 0.8)
    }

    private companion object {
        const val TAG = "WhisperDeviceTest"
        const val EXPECTED = "Idea para el fin de semana. Quiero terminar el prototipo de la aplicación de notas " +
            "antes del lunes. Primero, revisar el pull request de las notas de voz y probarlo en la tablet. " +
            "Después, comprar leche, pan y café para la semana. También tengo que llamar al dentista para " +
            "cambiar la cita del jueves a las cinco de la tarde. Por último, escribir un resumen de la reunión " +
            "con el equipo y mandarlo por correo antes del viernes. Ah, y no olvidar hacer el backup del servidor."
    }
}
