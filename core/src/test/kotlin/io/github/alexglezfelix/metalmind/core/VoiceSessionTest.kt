package io.github.alexglezfelix.metalmind.core

import java.io.IOException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class VoiceSessionTest {
    private class FakeTranscriber(private val result: (FloatArray) -> String?) : Transcriber {
        var calls = 0
        var cancels = 0
        var onTranscribe: () -> Unit = {}

        override fun transcribe(samples: FloatArray, onProgress: (Int) -> Unit): String? {
            calls++
            onProgress(50)
            onTranscribe()
            onProgress(100)
            return result(samples)
        }

        override fun cancel() {
            cancels++
        }
    }

    /** El buzón: guarda en memoria, con los nombres de la app. */
    private class FakeSink(private val fail: String? = null) : AudioSink {
        val saved = mutableListOf<Pair<String, ShortArray>>()

        override fun save(samples: ShortArray): String {
            fail?.let { throw IOException(it) }
            val name = AudioInbox.fileName(LocalDateTime.of(2026, 11, 3, 9, 15, 0), saved.map { it.first })
            saved += name to samples
            return name
        }
    }

    private val sink = FakeSink()

    private fun TestScope.session(
        chunks: List<ShortArray>,
        transcriber: Transcriber,
        whisper: Boolean = true,
        sink: AudioSink = this@VoiceSessionTest.sink,
        afterRead: (VoiceSession, Int) -> Unit,
    ): VoiceSession {
        lateinit var session: VoiceSession
        session = VoiceSession(
            scope = this,
            newRecorder = { VoiceRecorder(FakePcmSource(chunks) { afterRead(session, it) }) },
            transcriber = transcriber,
            sink = sink,
            transcribe = { whisper },
            io = StandardTestDispatcher(testScheduler),
        )
        return session
    }

    /** Whisper sin modelo: transcribir falla, como en la app. */
    private val noModel = FakeTranscriber { throw IOException("no hay modelo") }

    @Test
    fun `grabar, detener y recibir el texto limpio con el audio`() = runTest {
        val transcriber = FakeTranscriber { " Comprar pan.\n[Música]" }
        val seen = mutableListOf<VoiceState>()
        val session = session(List(20) { FakePcmSource.tone() }, transcriber) { s, read ->
            seen += s.state.value
            if (read == 20) s.stop()
        }
        session.start()
        assertIs<VoiceState.Recording>(session.state.value)
        advanceUntilIdle()
        val done = assertIs<VoiceState.Done>(session.state.value)
        assertEquals("Comprar pan.", done.text)
        // El audio se guardó antes de transcribir, y el texto lo enlaza.
        assertEquals("2026-11-03-091500.m4a", done.audio)
        assertEquals(2 * VoiceRecorder.SAMPLE_RATE, sink.saved.single().second.size)
        assertEquals(2, done.audioSeconds)
        // El contador avanza mientras se graba.
        assertEquals(1, (seen.last() as VoiceState.Recording).seconds)
        session.dismiss()
        assertEquals(VoiceState.Idle, session.state.value)
    }

    @Test
    fun `la pausa se ve en el estado`() = runTest {
        val states = mutableListOf<VoiceState>()
        val session = session(List(3) { FakePcmSource.tone() }, FakeTranscriber { "hola" }) { s, read ->
            if (read == 1) {
                s.pause()
                states += s.state.value
                s.resume()
                states += s.state.value
            }
            if (read == 3) s.stop()
        }
        session.start()
        advanceUntilIdle()
        assertEquals(listOf(true, false), states.map { (it as VoiceState.Recording).paused })
        assertIs<VoiceState.Done>(session.state.value)
    }

    @Test
    fun `el silencio no llega a whisper pero el audio se guarda`() = runTest {
        val transcriber = FakeTranscriber { "Subtítulos realizados por la comunidad de Amara.org" }
        val session = session(List(5) { ShortArray(1600) }, transcriber) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.NothingHeard("2026-11-03-091500.m4a"), session.state.value)
        assertEquals(0, transcriber.calls)
        assertEquals(1, sink.saved.size)
    }

    @Test
    fun `con Whisper apagado graba sin modelo y deja el audio en el buzón`() = runTest {
        val seen = mutableListOf<VoiceState>()
        val session = session(List(5) { FakePcmSource.tone() }, noModel, whisper = false) { s, read -> if (read == 5) s.stop() }
        session.start()
        val job = launch { session.state.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()
        assertEquals(VoiceState.Saved("2026-11-03-091500.m4a"), session.state.value)
        assertEquals(0, noModel.calls)
        assertEquals(5 * 1600, sink.saved.single().second.size)
        assertTrue(VoiceState.Saving in seen)
    }

    @Test
    fun `con Whisper apagado también se guarda el silencio`() = runTest {
        val session = session(List(5) { ShortArray(1600) }, noModel, whisper = false) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Saved("2026-11-03-091500.m4a"), session.state.value)
    }

    @Test
    fun `si falla la transcripción el audio queda y se avisa`() = runTest {
        val session = session(List(5) { FakePcmSource.tone() }, noModel) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Failed("no hay modelo", audio = "2026-11-03-091500.m4a"), session.state.value)
        assertEquals(1, sink.saved.size)
    }

    @Test
    fun `si no se puede guardar el audio lo dice, y con Whisper el texto llega igual`() = runTest {
        val broken = FakeSink(fail = "no se pudo crear buzon/")
        val off = session(List(3) { FakePcmSource.tone() }, noModel, whisper = false, sink = broken) { s, read ->
            if (read == 3) s.stop()
        }
        off.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Failed("no se pudo crear buzon/"), off.state.value)

        val on = session(List(3) { FakePcmSource.tone() }, FakeTranscriber { "hola" }, sink = broken) { s, read ->
            if (read == 3) s.stop()
        }
        on.start()
        advanceUntilIdle()
        val done = assertIs<VoiceState.Done>(on.state.value)
        assertEquals("hola", done.text)
        assertEquals(null, done.audio)
        assertEquals("no se pudo crear buzon/", done.audioError)
    }

    @Test
    fun `dos grabaciones en el mismo segundo no se pisan`() = runTest {
        repeat(2) {
            val session = session(List(2) { FakePcmSource.tone() }, noModel, whisper = false) { s, read -> if (read == 2) s.stop() }
            session.start()
            advanceUntilIdle()
        }
        assertEquals(listOf("2026-11-03-091500.m4a", "2026-11-03-091500-2.m4a"), sink.saved.map { it.first })
    }

    @Test
    fun `si whisper no reconoce nada no hay texto`() = runTest {
        val session = session(List(5) { FakePcmSource.tone() }, FakeTranscriber { "[Música]" }) { s, read ->
            if (read == 5) s.stop()
        }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.NothingHeard("2026-11-03-091500.m4a"), session.state.value)
    }

    @Test
    fun `descartar mientras se graba no deja audio`() = runTest {
        for (whisper in listOf(true, false)) {
            val transcriber = FakeTranscriber { "hola" }
            val session = session(List(5) { FakePcmSource.tone() }, transcriber, whisper) { s, read -> if (read == 2) s.cancel() }
            session.start()
            advanceUntilIdle()
            assertEquals(VoiceState.Idle, session.state.value)
            assertEquals(0, transcriber.calls)
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `cancelar mientras transcribe corta whisper y el audio se queda`() = runTest {
        val transcriber = FakeTranscriber { "hola" }
        lateinit var session: VoiceSession
        transcriber.onTranscribe = { session.cancel() }
        session = session(List(5) { FakePcmSource.tone() }, transcriber) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Saved("2026-11-03-091500.m4a"), session.state.value)
        assertEquals(1, transcriber.cancels)
        assertEquals(1, sink.saved.size)
    }

    @Test
    fun `cancelar mientras se guarda el audio no lo pierde`() = runTest {
        lateinit var session: VoiceSession
        val saving = AudioSink { samples ->
            session.cancel()
            sink.save(samples)
        }
        session = session(List(3) { FakePcmSource.tone() }, FakeTranscriber { "hola" }, sink = saving) { s, read ->
            if (read == 3) s.stop()
        }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Saved("2026-11-03-091500.m4a"), session.state.value)
    }

    @Test
    fun `si el micrófono no se puede abrir lo dice en vez de cerrar la app`() = runTest {
        val session = VoiceSession(
            scope = this,
            newRecorder = { throw IOException("no se pudo abrir el micrófono") },
            transcriber = FakeTranscriber { "hola" },
            sink = sink,
            io = StandardTestDispatcher(testScheduler),
        )
        session.start()
        assertEquals(VoiceState.Failed("no se pudo abrir el micrófono"), session.state.value)
        session.dismiss()
        assertEquals(VoiceState.Idle, session.state.value)
    }

    @Test
    fun `no empieza otra mientras hay una en curso`() = runTest {
        var recorders = 0
        lateinit var session: VoiceSession
        session = VoiceSession(
            scope = this,
            newRecorder = {
                recorders++
                VoiceRecorder(FakePcmSource(List(3) { FakePcmSource.tone() }) { if (it == 3) session.stop() })
            },
            transcriber = FakeTranscriber { "hola" },
            sink = sink,
            io = StandardTestDispatcher(testScheduler),
        )
        session.start()
        session.start()
        advanceUntilIdle()
        assertEquals(1, recorders)
        assertTrue(session.state.value is VoiceState.Done)
    }

    @Test
    fun `mide cuánto tarda la transcripción y cuánto audio era`() = runTest {
        var now = 1_000L
        val transcriber = FakeTranscriber { "hola" }
        val seen = mutableListOf<VoiceState>()
        lateinit var session: VoiceSession
        transcriber.onTranscribe = {
            seen += session.state.value
            now += 9_500
        }
        session = VoiceSession(
            scope = this,
            newRecorder = { VoiceRecorder(FakePcmSource(List(30) { FakePcmSource.tone() }) { if (it == 30) session.stop() }) },
            transcriber = transcriber,
            sink = sink,
            io = StandardTestDispatcher(testScheduler),
            clock = { now },
        )
        session.start()
        advanceUntilIdle()
        val done = assertIs<VoiceState.Done>(session.state.value)
        // Solo lo que tarda whisper, no la grabación.
        assertEquals(9_500, done.millis)
        assertEquals(3, done.audioSeconds)
        assertEquals(VoiceState.Transcribing(50, audioSeconds = 3), seen.single())
    }
}
