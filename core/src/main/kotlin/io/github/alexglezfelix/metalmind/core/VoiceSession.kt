package io.github.alexglezfelix.metalmind.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pasa audio a texto en el dispositivo. Bloquea el hilo que lo llama. */
interface Transcriber {
    /** El texto crudo (un segmento por línea), o `null` si se canceló con [cancel]. */
    fun transcribe(samples: FloatArray, onProgress: (percent: Int) -> Unit): String?

    /** Corta la transcripción en curso, desde otro hilo. */
    fun cancel()
}

sealed interface VoiceState {
    data object Idle : VoiceState

    data class Recording(val seconds: Int, val level: Float, val paused: Boolean) : VoiceState

    /** [audioSeconds]: lo grabado, para poner el tiempo que tarda en contexto. */
    data class Transcribing(val percent: Int, val audioSeconds: Int) : VoiceState

    /**
     * Listo: [text] va al campo de captura; [audio], si se quiere guardar.
     * [millis] es lo que tardó whisper, para saber si el teléfono va bien.
     */
    class Done(val text: String, val audio: ShortArray, val millis: Long) : VoiceState {
        val audioSeconds: Int get() = audio.size / VoiceRecorder.SAMPLE_RATE
    }

    /** Se grabó silencio, o whisper no reconoció nada. */
    data object NothingHeard : VoiceState

    data class Failed(val message: String) : VoiceState
}

/**
 * Una nota de voz de principio a fin: grabar (con pausa), transcribir y
 * entregar el texto. Una sola a la vez.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val newRecorder: () -> VoiceRecorder,
    private val transcriber: Transcriber,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Milisegundos de un reloj monótono, para medir la transcripción. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recorder: VoiceRecorder? = null
    private var job: Job? = null

    /** Lo pone [cancel]: lo que termine después ya no cambia el estado. */
    @Volatile private var cancelled = false

    val busy: Boolean get() = job?.isActive == true

    fun start() {
        if (busy) return
        // Abrir el micrófono puede fallar (otra app lo tiene, no graba a 16 kHz):
        // se muestra como cualquier otro error, no cierra la app.
        val rec = try {
            newRecorder()
        } catch (e: Exception) {
            _state.value = VoiceState.Failed(e.message ?: e.javaClass.simpleName)
            return
        }
        recorder = rec
        cancelled = false
        _state.value = VoiceState.Recording(0, 0f, paused = false)
        job = scope.launch {
            val end = try {
                withContext(io) { run(rec) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceState.Failed(e.message ?: e.javaClass.simpleName)
            }
            recorder = null
            _state.value = if (cancelled) VoiceState.Idle else end
        }
    }

    private fun run(rec: VoiceRecorder): VoiceState {
        val audio = rec.record { samples, level ->
            // update: pause() puede estar cambiando el estado desde el hilo de la UI.
            _state.update {
                if (it is VoiceState.Recording && !it.paused) {
                    it.copy(seconds = samples / VoiceRecorder.SAMPLE_RATE, level = level)
                } else {
                    it
                }
            }
        } ?: return VoiceState.Idle
        if (Pcm.isSilent(audio)) return VoiceState.NothingHeard
        if (cancelled) return VoiceState.Idle
        val audioSeconds = audio.size / VoiceRecorder.SAMPLE_RATE
        _state.value = VoiceState.Transcribing(0, audioSeconds)
        val start = clock()
        val raw = transcriber.transcribe(Pcm.toFloats(audio)) { percent ->
            _state.update { if (it is VoiceState.Transcribing) it.copy(percent = percent) else it }
        } ?: return VoiceState.Idle
        val millis = clock() - start
        val text = Transcript.clean(raw)
        return if (text.isEmpty()) VoiceState.NothingHeard else VoiceState.Done(text, audio, millis)
    }

    fun pause() {
        if (_state.value !is VoiceState.Recording) return
        recorder?.pause()
        _state.update { if (it is VoiceState.Recording) it.copy(paused = true, level = 0f) else it }
    }

    fun resume() {
        if (_state.value !is VoiceState.Recording) return
        recorder?.resume()
        _state.update { if (it is VoiceState.Recording) it.copy(paused = false) else it }
    }

    /** Termina de grabar y empieza a transcribir. */
    fun stop() {
        recorder?.stop()
    }

    /** Descarta lo grabado o corta la transcripción. */
    fun cancel() {
        if (!busy) return
        cancelled = true
        recorder?.cancel()
        transcriber.cancel()
        _state.value = VoiceState.Idle
    }

    /** Vuelve a [VoiceState.Idle] tras mostrar el resultado o el error. */
    fun dismiss() {
        if (!busy) _state.value = VoiceState.Idle
    }
}
