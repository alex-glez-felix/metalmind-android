package io.github.alexglezfelix.metalmind.core

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceRecorderTest {
    private val chunk = VoiceRecorder.SAMPLE_RATE / 10

    @Test
    fun `graba hasta que se detiene y suelta el micrófono`() {
        lateinit var recorder: VoiceRecorder
        val source = FakePcmSource(List(3) { FakePcmSource.tone(mark = it.toShort()) }) { if (it == 3) recorder.stop() }
        recorder = VoiceRecorder(source)
        val levels = mutableListOf<Float>()
        val audio = recorder.record { _, level -> levels += level }!!
        assertEquals(3 * chunk, audio.size)
        assertEquals(listOf<Short>(0, 1, 2), listOf(audio[0], audio[chunk], audio[2 * chunk]))
        assertEquals(listOf("start", "stop", "release"), source.events)
        assertTrue(levels.all { it in 0.25f..0.35f }, "nivel: $levels")
    }

    @Test
    fun `cancelar no devuelve nada`() {
        lateinit var recorder: VoiceRecorder
        val source = FakePcmSource(List(2) { FakePcmSource.tone() }) { if (it == 2) recorder.cancel() }
        recorder = VoiceRecorder(source)
        assertNull(recorder.record())
        assertEquals("release", source.events.last())
    }

    @Test
    fun `en pausa apaga el micrófono y no guarda nada`() {
        lateinit var recorder: VoiceRecorder
        val chunks = List(4) { FakePcmSource.tone(mark = it.toShort()) }
        val source = FakePcmSource(chunks) {
            when (it) {
                2 -> recorder.pause()
                4 -> recorder.stop()
            }
        }
        // Reanuda desde "la UI" cuando la grabadora ya apagó el micrófono.
        source.onStop = {
            source.onStop = {}
            thread { Thread.sleep(20); recorder.resume() }
        }
        recorder = VoiceRecorder(source)
        val audio = recorder.record()!!
        assertEquals(listOf("start", "stop", "start", "stop", "release"), source.events)
        assertEquals(listOf<Short>(0, 1, 2, 3), (0 until 4).map { audio[it * chunk] })
    }

    @Test
    fun `se detiene sola al llegar al máximo`() {
        val source = FakePcmSource(List(25) { FakePcmSource.tone() })
        val audio = VoiceRecorder(source, maxSeconds = 2).record()!!
        assertEquals(2 * VoiceRecorder.SAMPLE_RATE, audio.size)
    }

    @Test
    fun `un error del micrófono suelta el micrófono`() {
        val source = object : PcmSource {
            val events = mutableListOf<String>()
            override fun start() { events += "start" }
            override fun read(buffer: ShortArray) = -3
            override fun stop() { events += "stop" }
            override fun release() { events += "release" }
        }
        val error = runCatching { VoiceRecorder(source).record() }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(listOf("start", "stop", "release"), source.events)
    }
}

class PcmTest {
    @Test
    fun `convierte a flotantes entre -1 y 1`() {
        assertContentEquals(floatArrayOf(-1f, 0f, 0.5f), Pcm.toFloats(shortArrayOf(Short.MIN_VALUE, 0, 16384)))
    }

    @Test
    fun `el silencio y el ruido de fondo cuentan como silencio`() {
        assertTrue(Pcm.isSilent(ShortArray(VoiceRecorder.SAMPLE_RATE)))
        assertTrue(Pcm.isSilent(FakePcmSource.tone(amplitude = 0.005f)))
        assertTrue(Pcm.isSilent(ShortArray(0)))
    }

    @Test
    fun `un tramo con voz basta para que no sea silencio`() {
        val audio = ShortArray(VoiceRecorder.SAMPLE_RATE * 3)
        FakePcmSource.tone(amplitude = 0.05f).copyInto(audio, VoiceRecorder.SAMPLE_RATE * 2)
        assertTrue(!Pcm.isSilent(audio))
    }
}
