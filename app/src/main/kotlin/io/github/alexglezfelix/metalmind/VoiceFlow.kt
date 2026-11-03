package io.github.alexglezfelix.metalmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Qué hace falta antes de grabar, o qué se le muestra al usuario. */
enum class VoiceStep {
    /** Nada: o no se tocó el micrófono, o ya se está grabando. */
    None,

    /**
     * Whisper está encendido y falta su modelo: elegirlo o descargarlo (o
     * apagar Whisper y solo grabar).
     */
    ChooseModel,

    /** Los ajustes de voz (Whisper y su modelo), sin grabar al cerrarlos. */
    Settings,

    /** Falta el permiso del micrófono: pedirlo al sistema. */
    RequestPermission,

    /** Se negó el permiso para siempre: solo queda ir a los ajustes. */
    PermissionDenied,
}

/**
 * Del toque en el micrófono a la grabación: el modelo, solo si Whisper está
 * encendido y falta ([needsModel]); después el permiso (pedido solo ahora,
 * nunca al abrir la app), y entonces [record].
 */
class VoiceFlow(
    private val needsModel: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val record: () -> Unit,
) {
    var step by mutableStateOf(VoiceStep.None)
        private set

    fun mic() {
        step = when {
            needsModel() -> VoiceStep.ChooseModel
            !hasPermission() -> VoiceStep.RequestPermission
            else -> {
                record()
                VoiceStep.None
            }
        }
    }

    /**
     * Ya hay modelo, o se apagó Whisper: sigue hacia el permiso y la
     * grabación, si se venía del micrófono.
     */
    fun modelReady() {
        if (step == VoiceStep.ChooseModel) mic()
    }

    /**
     * Respuesta al pedido de permiso. [canAskAgain] es falso si Android ya no
     * mostrará el diálogo (se negó dos veces, o "no volver a preguntar").
     */
    fun permissionResult(granted: Boolean, canAskAgain: Boolean) {
        if (step != VoiceStep.RequestPermission) return
        step = when {
            granted -> {
                record()
                VoiceStep.None
            }
            canAskAgain -> VoiceStep.None
            else -> VoiceStep.PermissionDenied
        }
    }

    /** Para encender o apagar Whisper o cambiar el modelo; también mientras se graba. */
    fun settings() {
        step = VoiceStep.Settings
    }

    fun dismiss() {
        step = VoiceStep.None
    }
}
