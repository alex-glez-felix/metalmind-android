package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceModelsTest {
    private val header = byteArrayOf('l'.code.toByte(), 'm'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 1, 0)

    @Test
    fun `reconoce un modelo por su SHA-256 aunque el navegador lo haya renombrado`() {
        assertEquals(
            ModelCheck.Known(VoiceModels.SMALL),
            ModelCheck.of("descarga (1).bin", header, VoiceModels.SMALL.sha256.uppercase()),
        )
    }

    @Test
    fun `un modelo de la lista que no coincide está dañado`() {
        assertEquals(
            ModelCheck.Corrupt(VoiceModels.BASE),
            ModelCheck.of("ggml-base-q5_1 (2).bin", header, "0".repeat(64)),
        )
    }

    @Test
    fun `otro modelo de whisper cpp se acepta tal cual`() {
        assertEquals(ModelCheck.Unknown, ModelCheck.of("ggml-medium-q5_0.bin", header, "0".repeat(64)))
    }

    @Test
    fun `cualquier otro archivo no es un modelo`() {
        assertEquals(ModelCheck.NotAModel, ModelCheck.of("foto.jpg", byteArrayOf(-1, -40, -1), "0".repeat(64)))
        assertEquals(ModelCheck.NotAModel, ModelCheck.of(null, ByteArray(0), "0".repeat(64)))
    }

    @Test
    fun `los enlaces de descarga apuntan al repositorio de whisper cpp`() {
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            VoiceModels.SMALL.url,
        )
        assertEquals(190, VoiceModels.SMALL.megabytes)
        assertTrue(VoiceModels.ALL.all { it.sha256.length == 64 })
    }
}
