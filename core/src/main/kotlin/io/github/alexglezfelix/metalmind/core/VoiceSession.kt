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

/**
 * Dónde queda el audio de cada nota de voz: en `recordings/` del vault
 * ([AudioRecordings]), antes de transcribir nada.
 */
fun interface AudioSink {
    /**
     * Guarda [samples] y devuelve el nombre con el que se enlaza. Bloquea el
     * hilo que la llama; si falla, lanza la excepción.
     */
    fun save(samples: ShortArray): String
}

sealed interface VoiceState {
    data object Idle : VoiceState

    data class Recording(val seconds: Int, val level: Float, val paused: Boolean) : VoiceState

    /** Se dejó de grabar y el audio se está escribiendo en el vault. */
    data object Saving : VoiceState

    /** [audioSeconds]: lo grabado, para poner el tiempo que tarda en contexto. */
    data class Transcribing(val percent: Int, val audioSeconds: Int) : VoiceState

    /**
     * Sin transcribir (Whisper apagado, o se canceló la transcripción): el
     * audio quedó en el vault como [audio].
     */
    data class Saved(val audio: String) : VoiceState

    /**
     * Listo: [text] va al campo de captura, y [audio] es el nombre con el que
     * quedó en el vault (`null` si no se pudo guardar: [audioError] dice por
     * qué). [millis] es lo que tardó whisper (cargar el modelo y transcribir),
     * para saber si el teléfono va bien con ese modelo.
     */
    data class Done(
        val text: String,
        val audio: String?,
        val millis: Long,
        val audioSeconds: Int,
        val audioError: String? = null,
    ) : VoiceState

    /**
     * Se grabó silencio, o whisper no reconoció nada; el audio quedó igual como
     * [audio] (`null` si no se pudo guardar: [audioError] dice por qué).
     */
    data class NothingHeard(val audio: String?, val audioError: String? = null) : VoiceState

    /** Algo falló; si el audio alcanzó a guardarse, [audio] es su nombre. */
    data class Failed(val message: String, val audio: String? = null) : VoiceState
}

/**
 * Una nota de voz de principio a fin: grabar (con pausa), guardar el audio en
 * el vault y, si [transcribe] lo pide al detener, transcribir y entregar el
 * texto. Una sola a la vez.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val newRecorder: () -> VoiceRecorder,
    private val transcriber: Transcriber,
    private val sink: AudioSink,
    /** Si Whisper está encendido; se pregunta al detener la grabación. */
    private val transcribe: () -> Boolean = { true },
    /**
     * Se salió de la app grabando y lo grabado ya se guardó ([audio]) o no se
     * pudo ([error]). Se llama aunque ya no haya pantalla que mire [state].
     */
    private val onLeft: (audio: String?, error: String?) -> Unit = { _, _ -> },
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

    /** Lo pone [leave]: se guarda lo grabado, no se transcribe y nada lo descarta. */
    @Volatile private var leaving = false

    /** El nombre del audio de esta nota, en cuanto quedó en el vault. */
    @Volatile private var audio: String? = null

    /** Por qué no quedó en el vault, si falló al guardarlo. */
    @Volatile private var saveError: String? = null

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
        leaving = false
        audio = null
        saveError = null
        _state.value = VoiceState.Recording(0, 0f, paused = false)
        job = scope.launch {
            val end = try {
                withContext(io) { run(rec) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceState.Failed(e.message ?: e.javaClass.simpleName, audio)
            }
            recorder = null
            _state.update {
                when {
                    !cancelled || leaving -> end
                    // Cancelado a mitad de camino: lo que haya quedado en el vault se dice.
                    it is VoiceState.Recording || it is VoiceState.Saving || it is VoiceState.Transcribing -> afterCancel()
                    else -> it
                }
            }
        }
    }

    private fun run(rec: VoiceRecorder): VoiceState {
        val samples = rec.record { count, level ->
            // update: pause() puede estar cambiando el estado desde el hilo de la UI.
            _state.update {
                if (it is VoiceState.Recording && !it.paused) {
                    it.copy(seconds = count / VoiceRecorder.SAMPLE_RATE, level = level)
                } else {
                    it
                }
            }
        } ?: return VoiceState.Idle
        // Se salió antes de la primera lectura: no hay audio que guardar.
        if (leaving && samples.isEmpty()) return VoiceState.Idle
        // Primero el audio, siempre: es la fuente de verdad. Ni el silencio,
        // ni whisper apagado, ni un fallo al transcribir lo pierden.
        _state.value = VoiceState.Saving
        val (saved, error) = try {
            sink.save(samples) to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
        audio = saved
        saveError = error
        if (leaving) {
            onLeft(saved, error)
            return VoiceState.Idle
        }
        if (!transcribe()) {
            return if (saved != null) VoiceState.Saved(saved) else VoiceState.Failed(error!!)
        }
        if (Pcm.isSilent(samples)) return VoiceState.NothingHeard(saved, error)
        if (cancelled) return afterCancel()
        val audioSeconds = samples.size / VoiceRecorder.SAMPLE_RATE
        _state.value = VoiceState.Transcribing(0, audioSeconds)
        val start = clock()
        val raw = try {
            transcriber.transcribe(Pcm.toFloats(samples)) { percent ->
                _state.update { if (it is VoiceState.Transcribing) it.copy(percent = percent) else it }
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            return VoiceState.Failed(if (error != null) "$message; $error" else message, saved)
        } ?: return afterCancel()
        val millis = clock() - start
        val text = Transcript.clean(raw)
        return if (text.isEmpty()) {
            VoiceState.NothingHeard(saved, error)
        } else {
            VoiceState.Done(text, saved, millis, audioSeconds, error)
        }
    }

    /** Tras cancelar: si el audio ya estaba en el vault, ahí se queda; si no se pudo guardar, se dice. */
    private fun afterCancel(): VoiceState =
        audio?.let { VoiceState.Saved(it) } ?: saveError?.let { VoiceState.Failed(it) } ?: VoiceState.Idle

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

    /** Termina de grabar: guarda el audio y, si toca, transcribe. */
    fun stop() {
        recorder?.stop()
    }

    /**
     * Se sale de la app: lo grabado se guarda como al [stop], pero sin
     * transcribir, y [onLeft] lo cuenta. Sigue aunque se cancele el scope
     * después: el guardado bloquea su hilo hasta terminar. Si ya está
     * transcribiendo, no cambia nada: el audio ya está en el vault.
     */
    fun leave() {
        val now = _state.value
        if (now !is VoiceState.Recording && now !is VoiceState.Saving) return
        leaving = true
        recorder?.stop()
    }

    /**
     * Grabando, descarta lo grabado: no queda audio. Después de detener o de
     * [leave], el audio se queda: solo corta la transcripción (si no se pudo
     * guardar, termina en [VoiceState.Failed]).
     */
    fun cancel() {
        if (!busy) return
        cancelled = true
        if (!leaving) recorder?.cancel()
        transcriber.cancel()
        // Mientras se escribe el audio no se interrumpe: al terminar, start() dice dónde quedó.
        _state.update { if (it is VoiceState.Saving) it else afterCancel() }
    }

    /** Vuelve a [VoiceState.Idle] tras mostrar el resultado o el error. */
    fun dismiss() {
        _state.update {
            if (it is VoiceState.Recording || it is VoiceState.Saving || it is VoiceState.Transcribing) it else VoiceState.Idle
        }
    }
}
