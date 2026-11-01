package io.github.alexglezfelix.metalmind

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.alexglezfelix.metalmind.core.PcmSource
import io.github.alexglezfelix.metalmind.core.VoiceRecorder
import java.io.IOException

/** El micrófono del teléfono, a 16 kHz mono como lo pide whisper. */
class AudioRecordSource : PcmSource {
    private val record: AudioRecord

    init {
        val min = AudioRecord.getMinBufferSize(RATE, CHANNEL, ENCODING)
        if (min <= 0) throw IOException("el micrófono no graba a $RATE Hz")
        record = newRecord(maxOf(min, RATE / 2 * 2))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("no se pudo abrir el micrófono")
        }
    }

    // Solo se crea tras concederse RECORD_AUDIO (VoiceFlow lo comprueba).
    @SuppressLint("MissingPermission")
    private fun newRecord(bytes: Int) =
        // VOICE_RECOGNITION: sin el control automático de ganancia ni el
        // filtrado pensados para llamadas, que deforman la voz para whisper.
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, CHANNEL, ENCODING, bytes)

    override fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IOException("el micrófono está ocupado")
        }
    }

    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

    override fun stop() = record.stop()

    override fun release() = record.release()

    private companion object {
        const val RATE = VoiceRecorder.SAMPLE_RATE
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
