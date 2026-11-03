package io.github.alexglezfelix.metalmind

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceFlowTest {
    private var whisper = true
    private var model = false
    private var permission = false
    private var recordings = 0
    private val flow = VoiceFlow({ whisper && !model }, { permission }, { recordings++ })

    @Test
    fun `con Whisper encendido y sin modelo, el micrófono pide elegirlo y no el permiso`() {
        flow.mic()
        assertEquals(VoiceStep.ChooseModel, flow.step)
        assertEquals(0, recordings)
    }

    @Test
    fun `con Whisper apagado no pide modelo, solo el permiso`() {
        whisper = false
        flow.mic()
        assertEquals(VoiceStep.RequestPermission, flow.step)
        flow.permissionResult(granted = true, canAskAgain = true)
        assertEquals(1, recordings)
        permission = true
        flow.mic()
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(2, recordings)
    }

    @Test
    fun `apagar Whisper en el diálogo del modelo sigue a la grabación`() {
        permission = true
        flow.mic()
        whisper = false
        flow.modelReady()
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(1, recordings)
    }

    @Test
    fun `con el modelo listo pasa al permiso y, concedido, graba`() {
        flow.mic()
        model = true
        flow.modelReady()
        assertEquals(VoiceStep.RequestPermission, flow.step)
        flow.permissionResult(granted = true, canAskAgain = true)
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(1, recordings)
    }

    @Test
    fun `con modelo y permiso graba de una`() {
        model = true
        permission = true
        flow.mic()
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(1, recordings)
    }

    @Test
    fun `si niega el permiso no graba y si ya no se puede preguntar manda a los ajustes`() {
        model = true
        flow.mic()
        flow.permissionResult(granted = false, canAskAgain = true)
        assertEquals(VoiceStep.None, flow.step)
        flow.mic()
        flow.permissionResult(granted = false, canAskAgain = false)
        assertEquals(VoiceStep.PermissionDenied, flow.step)
        assertEquals(0, recordings)
    }

    @Test
    fun `cerrar el diálogo del modelo no graba`() {
        flow.mic()
        flow.dismiss()
        model = true
        flow.modelReady()
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(0, recordings)
    }

    @Test
    fun `los ajustes no graban al elegir modelo ni al cerrarse`() {
        model = true
        permission = true
        flow.settings()
        assertEquals(VoiceStep.Settings, flow.step)
        flow.modelReady()
        assertEquals(VoiceStep.Settings, flow.step)
        flow.dismiss()
        assertEquals(VoiceStep.None, flow.step)
        assertEquals(0, recordings)
    }

    @Test
    fun `una respuesta de permiso tardía no graba`() {
        model = true
        flow.permissionResult(granted = true, canAskAgain = true)
        assertEquals(0, recordings)
    }
}
