package io.github.alexglezfelix.metalmind.core

/** Los ajustes guardados que hacen falta aquí: booleanos por clave. */
interface BooleanPrefs {
    /** El valor guardado, o `null` si nunca se guardó. */
    operator fun get(key: String): Boolean?

    operator fun set(key: String, value: Boolean)

    fun remove(key: String)
}

/**
 * Si Whisper transcribe en el teléfono al detener la grabación. Apagado de
 * entrada: sin él, grabar solo deja el audio en el buzón y no hace falta
 * modelo.
 *
 * La primera vez que se lee (en una instalación nueva o al actualizar desde
 * una versión sin este ajuste) se fija según [hasModel]: quien ya había
 * importado un modelo seguía transcribiendo, y actualizar no debe cambiarle
 * eso sin avisar. Desde entonces solo lo cambia el usuario.
 */
class WhisperSetting(private val prefs: BooleanPrefs, hasModel: () -> Boolean) {
    init {
        if (prefs[KEY] == null) prefs[KEY] = hasModel()
        // "Guardar también el audio con la nota": ahora el audio se guarda siempre.
        prefs.remove(LEGACY_SAVE_AUDIO)
    }

    var enabled: Boolean
        get() = prefs[KEY] ?: false
        set(value) {
            prefs[KEY] = value
        }

    companion object {
        const val KEY = "whisper"
        const val LEGACY_SAVE_AUDIO = "guardar_audio"
    }
}
