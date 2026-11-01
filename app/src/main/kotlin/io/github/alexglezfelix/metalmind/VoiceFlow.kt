package io.github.alexglezfelix.metalmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Qué hace falta antes de grabar, o qué se le muestra al usuario. */
enum class VoiceStep {
    /** Nada: o no se tocó el micrófono, o ya se está grabando. */
    None,

    /** Falta el modelo de whisper: elegirlo o descargarlo. */
    ChooseModel,

    /** Falta el permiso del micrófono: pedirlo al sistema. */
    RequestPermission,

    /** Se negó el permiso para siempre: solo queda ir a los ajustes. */
    PermissionDenied,
}

/**
 * Del toque en el micrófono a la grabación: primero el modelo, después el
 * permiso (pedido solo ahora, nunca al abrir la app), y entonces [record].
 */
class VoiceFlow(
    private val hasModel: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val record: () -> Unit,
) {
    var step by mutableStateOf(VoiceStep.None)
        private set

    fun mic() {
        step = when {
            !hasModel() -> VoiceStep.ChooseModel
            !hasPermission() -> VoiceStep.RequestPermission
            else -> {
                record()
                VoiceStep.None
            }
        }
    }

    /** Ya hay modelo: sigue hacia el permiso y la grabación. */
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

    /** Para cambiar el modelo aunque ya haya uno. */
    fun manageModel() {
        step = VoiceStep.ChooseModel
    }

    fun dismiss() {
        step = VoiceStep.None
    }
}
