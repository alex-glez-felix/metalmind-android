package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperSettingTest {
    private class MapPrefs(vararg values: Pair<String, Boolean>) : BooleanPrefs {
        val map = mutableMapOf(*values)

        override fun get(key: String) = map[key]

        override fun set(key: String, value: Boolean) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `en una instalación nueva Whisper viene apagado`() {
        val prefs = MapPrefs()
        assertFalse(WhisperSetting(prefs) { false }.enabled)
        assertEquals(false, prefs.map[WhisperSetting.KEY])
    }

    @Test
    fun `quien actualiza con un modelo importado sigue transcribiendo`() {
        val prefs = MapPrefs(WhisperSetting.LEGACY_SAVE_AUDIO to true)
        assertTrue(WhisperSetting(prefs) { true }.enabled)
        // "Guardar también el audio" ya no existe: el audio se guarda siempre.
        assertNull(prefs.map[WhisperSetting.LEGACY_SAVE_AUDIO])
    }

    @Test
    fun `la migración pasa una vez y después manda el usuario`() {
        val prefs = MapPrefs()
        var model = false
        WhisperSetting(prefs) { model }
        // Importar un modelo más tarde no lo enciende por su cuenta al volver a abrir.
        model = true
        val setting = WhisperSetting(prefs) { model }
        assertFalse(setting.enabled)
        setting.enabled = true
        assertTrue(WhisperSetting(prefs) { false }.enabled)
        // Y apagarlo con modelo se respeta.
        setting.enabled = false
        assertFalse(WhisperSetting(prefs) { true }.enabled)
    }
}
