package io.github.alexglezfelix.metalmind.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    private fun TestScope.session(
        chunks: List<ShortArray>,
        transcriber: Transcriber,
        afterRead: (VoiceSession, Int) -> Unit,
    ): VoiceSession {
        lateinit var session: VoiceSession
        session = VoiceSession(
            scope = this,
            newRecorder = { VoiceRecorder(FakePcmSource(chunks) { afterRead(session, it) }) },
            transcriber = transcriber,
            io = StandardTestDispatcher(testScheduler),
        )
        return session
    }

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
        assertEquals(2 * VoiceRecorder.SAMPLE_RATE, done.audio.size)
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
    fun `el silencio no llega a whisper`() = runTest {
        val transcriber = FakeTranscriber { "Subtítulos realizados por la comunidad de Amara.org" }
        val session = session(List(5) { ShortArray(1600) }, transcriber) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.NothingHeard, session.state.value)
        assertEquals(0, transcriber.calls)
    }

    @Test
    fun `si whisper no reconoce nada no hay texto`() = runTest {
        val session = session(List(5) { FakePcmSource.tone() }, FakeTranscriber { "[Música]" }) { s, read ->
            if (read == 5) s.stop()
        }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.NothingHeard, session.state.value)
    }

    @Test
    fun `cancelar mientras se graba descarta todo`() = runTest {
        val transcriber = FakeTranscriber { "hola" }
        val session = session(List(5) { FakePcmSource.tone() }, transcriber) { s, read -> if (read == 2) s.cancel() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, session.state.value)
        assertEquals(0, transcriber.calls)
    }

    @Test
    fun `cancelar mientras transcribe corta whisper y no deja el texto`() = runTest {
        val transcriber = FakeTranscriber { "hola" }
        lateinit var session: VoiceSession
        transcriber.onTranscribe = { session.cancel() }
        session = session(List(5) { FakePcmSource.tone() }, transcriber) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, session.state.value)
        assertEquals(1, transcriber.cancels)
    }

    @Test
    fun `un error del micrófono o del modelo se muestra`() = runTest {
        val broken = FakeTranscriber { throw IOException("no se pudo cargar el modelo") }
        val session = session(List(5) { FakePcmSource.tone() }, broken) { s, read -> if (read == 5) s.stop() }
        session.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Failed("no se pudo cargar el modelo"), session.state.value)
    }

    @Test
    fun `si el micrófono no se puede abrir lo dice en vez de cerrar la app`() = runTest {
        val session = VoiceSession(
            scope = this,
            newRecorder = { throw IOException("no se pudo abrir el micrófono") },
            transcriber = FakeTranscriber { "hola" },
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
            io = StandardTestDispatcher(testScheduler),
        )
        session.start()
        session.start()
        advanceUntilIdle()
        assertEquals(1, recorders)
        assertTrue(session.state.value is VoiceState.Done)
    }
}
