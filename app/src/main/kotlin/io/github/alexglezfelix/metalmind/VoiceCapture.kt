package io.github.alexglezfelix.metalmind

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.alexglezfelix.metalmind.core.AudioAttachment
import io.github.alexglezfelix.metalmind.core.ModelCheck
import io.github.alexglezfelix.metalmind.core.VoiceModel
import io.github.alexglezfelix.metalmind.core.VoiceModels
import io.github.alexglezfelix.metalmind.core.VoiceRecorder
import io.github.alexglezfelix.metalmind.core.VoiceSession
import io.github.alexglezfelix.metalmind.core.VoiceState
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Las notas de voz de la captura: el micrófono, sus diálogos y los audios pendientes. */
@Stable
class VoiceCapture internal constructor(
    internal val flow: VoiceFlow,
    internal val session: VoiceSession,
    internal val store: VoiceModelStore,
    val attachments: VoiceAttachments,
) {
    /** El toque en el micrófono. */
    fun mic() {
        if (!session.busy) flow.mic()
    }
}

/**
 * [onText] recibe lo transcrito (y el enlace al audio, si se guarda) para
 * ponerlo en el campo; [onMessage], lo que haya que avisar.
 */
@Composable
fun rememberVoiceCapture(onText: (String) -> Unit, onMessage: (String) -> Unit): VoiceCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onText)
    val message by rememberUpdatedState(onMessage)
    val voice = remember {
        val app = context.applicationContext
        val store = VoiceModelStore(app)
        lateinit var session: VoiceSession
        session = VoiceSession(
            scope = scope,
            newRecorder = { VoiceRecorder(AudioRecordSource()) },
            transcriber = WhisperTranscriber({ store.model }, app.applicationInfo.nativeLibraryDir),
        )
        val flow = VoiceFlow(
            hasModel = { store.model != null },
            hasPermission = { hasMicPermission(app) },
            record = { session.start() },
        )
        VoiceCapture(flow, session, store, VoiceAttachments(app))
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { voice.attachments.prune() } }
    // Salir de la captura corta la grabación o la transcripción: whisper no
    // debe seguir gastando batería para un campo que ya no existe.
    DisposableEffect(Unit) { onDispose { voice.session.cancel() } }

    val state by voice.session.state.collectAsState()
    val nothingHeard = stringResource(R.string.voice_nothing_heard)
    val failedFormat = stringResource(R.string.voice_failed)
    val timeFormat = stringResource(R.string.voice_done_time)
    LaunchedEffect(state) {
        when (val s = state) {
            is VoiceState.Done -> {
                var text = s.text
                if (voice.store.saveAudio) {
                    try {
                        val name = withContext(Dispatchers.Default) { voice.attachments.add(s.audio, LocalDateTime.now()) }
                        text += "\n" + AudioAttachment.embed(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // El texto vale más que el audio: se entrega igual.
                        message(failedFormat.format(e.message ?: e.javaClass.simpleName))
                    }
                }
                deliver(text)
                voice.session.dismiss()
                // Cuánto tardó, para saber si el teléfono va bien con este modelo (#17).
                message(timeFormat.format(clock(((s.millis + 500) / 1000).toInt()), clock(s.audioSeconds)))
            }
            VoiceState.NothingHeard -> {
                voice.session.dismiss()
                message(nothingHeard)
            }
            is VoiceState.Failed -> {
                voice.session.dismiss()
                message(failedFormat.format(s.message))
            }
            else -> {}
        }
    }

    // Con la pantalla encendida mientras se graba o transcribe: apagarse a
    // mitad de un dictado cortaría el micrófono.
    val busy = state is VoiceState.Recording || state is VoiceState.Transcribing
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    return voice
}

private fun hasMicPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** El botón del micrófono, para la barra de arriba de la captura. */
@Composable
fun VoiceButton(voice: VoiceCapture) {
    IconButton(onClick = voice::mic) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.voice_record))
    }
}

/** Los diálogos de la nota de voz; van una vez en la pantalla de captura. */
@Composable
fun VoiceDialogs(voice: VoiceCapture) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
        voice.flow.permissionResult(granted, canAskAgain)
    }
    when (voice.flow.step) {
        VoiceStep.None -> {}
        VoiceStep.ChooseModel -> ModelDialog(voice)
        VoiceStep.RequestPermission -> LaunchedEffect(Unit) { permission.launch(Manifest.permission.RECORD_AUDIO) }
        VoiceStep.PermissionDenied -> AlertDialog(
            onDismissRequest = voice.flow::dismiss,
            title = { Text(stringResource(R.string.voice_permission_title)) },
            text = { Text(stringResource(R.string.voice_permission_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    voice.flow.dismiss()
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text(stringResource(R.string.voice_open_settings)) }
            },
            dismissButton = { TextButton(onClick = voice.flow::dismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val state by voice.session.state.collectAsState()
    when (val s = state) {
        is VoiceState.Recording -> RecordingDialog(voice, s)
        is VoiceState.Transcribing -> TranscribingDialog(voice, s)
        else -> {}
    }
}

/** Que tocar fuera o Atrás no tire una grabación de varios minutos. */
private val sticky = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

@Composable
private fun RecordingDialog(voice: VoiceCapture, state: VoiceState.Recording) {
    var saveAudio by remember { mutableStateOf(voice.store.saveAudio) }
    AlertDialog(
        onDismissRequest = {},
        properties = sticky,
        title = {
            Text(stringResource(if (state.paused) R.string.voice_paused else R.string.voice_recording))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        clock(state.seconds),
                        fontSize = 40.sp,
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Botones grandes: se usan sin mirar mucho (manejando, caminando).
                    FilledIconButton(
                        onClick = if (state.paused) voice.session::resume else voice.session::pause,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (state.paused) rememberVectorPainter(Icons.Filled.PlayArrow) else painterResource(R.drawable.ic_pause),
                            contentDescription = stringResource(if (state.paused) R.string.voice_resume else R.string.voice_pause),
                        )
                    }
                    FilledIconButton(onClick = voice.session::stop, modifier = Modifier.size(56.dp)) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.voice_stop))
                    }
                }
                // El nivel, para ver que el micrófono oye; ×4 porque la voz
                // normal anda por 0.05–0.2 de la escala completa.
                LinearProgressIndicator(
                    progress = { (state.level * 4).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.voice_limit, clock(VoiceRecorder.MAX_SECONDS)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        saveAudio = !saveAudio
                        voice.store.saveAudio = saveAudio
                    },
                ) {
                    Checkbox(checked = saveAudio, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.voice_save_audio))
                }
                TextButton(onClick = {
                    voice.session.cancel()
                    voice.flow.manageModel()
                }) {
                    Text(
                        stringResource(R.string.voice_model_current, voice.store.label.orEmpty()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = voice.session::stop) { Text(stringResource(R.string.voice_transcribe)) } },
        dismissButton = { TextButton(onClick = voice.session::cancel) { Text(stringResource(R.string.voice_discard)) } },
    )
}

@Composable
private fun TranscribingDialog(voice: VoiceCapture, state: VoiceState.Transcribing) {
    // El reloj corre aunque whisper tarde en avisar el primer avance.
    val start = remember { SystemClock.elapsedRealtime() }
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            seconds = (SystemClock.elapsedRealtime() - start) / 1000
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        properties = sticky,
        title = { Text(stringResource(R.string.voice_transcribing)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.voice_transcribing_time, clock(seconds.toInt()), clock(state.audioSeconds)))
                Text(stringResource(R.string.voice_transcribing_offline), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = voice.session::cancel) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

/** Importando un modelo: el avance, o qué salió mal. */
private sealed interface Import {
    data object Idle : Import
    data class Copying(val progress: Float) : Import
    data class Failed(val message: String) : Import
}

@Composable
private fun ModelDialog(voice: VoiceCapture) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var import by remember { mutableStateOf<Import>(Import.Idle) }
    var label by remember { mutableStateOf(voice.store.label) }
    val corruptFormat = stringResource(R.string.voice_model_corrupt)
    val notAModel = stringResource(R.string.voice_model_not_a_model)
    val errorFormat = stringResource(R.string.voice_model_error)
    val noBrowser = stringResource(R.string.voice_no_browser)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        import = Import.Copying(0f)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    voice.store.import(context.contentResolver, uri) { p -> import = Import.Copying(p) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
            import = when (result) {
                is ModelCheck.Known, ModelCheck.Unknown -> {
                    label = voice.store.label
                    voice.flow.modelReady()
                    Import.Idle
                }
                is ModelCheck.Corrupt -> Import.Failed(corruptFormat.format(result.expected.fileName))
                ModelCheck.NotAModel -> Import.Failed(notAModel)
                is Exception -> Import.Failed(errorFormat.format(result.message ?: result.javaClass.simpleName))
                else -> Import.Idle
            }
        }
    }

    fun download(model: VoiceModel) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.url)))
        } catch (_: ActivityNotFoundException) {
            import = Import.Failed(noBrowser.format(model.url))
        }
    }

    val copying = import is Import.Copying
    AlertDialog(
        onDismissRequest = { if (!copying) voice.flow.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !copying, dismissOnClickOutside = !copying),
        title = { Text(stringResource(R.string.voice_model_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.voice_model_explain))
                for (model in VoiceModels.ALL) {
                    ModelRow(model, enabled = !copying, onDownload = { download(model) })
                }
                HorizontalDivider()
                Text(stringResource(R.string.voice_model_pick_explain), style = MaterialTheme.typography.bodySmall)
                when (val i = import) {
                    is Import.Copying -> {
                        Text(stringResource(R.string.voice_model_copying))
                        LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is Import.Failed -> Text(i.message, color = MaterialTheme.colorScheme.error)
                    Import.Idle -> {}
                }
                label?.let { current ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.voice_model_installed, current),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = !copying, onClick = {
                            voice.store.delete()
                            label = null
                        }) { Text(stringResource(R.string.voice_model_delete)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !copying, onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.voice_model_pick))
            }
        },
        dismissButton = {
            TextButton(enabled = !copying, onClick = voice.flow::dismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ModelRow(model: VoiceModel, enabled: Boolean, onDownload: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.voice_model_size, modelName(model), model.megabytes),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(modelDescription(model)), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = enabled, onClick = onDownload) { Text(stringResource(R.string.voice_model_download)) }
    }
}

private fun modelName(model: VoiceModel) = when (model) {
    VoiceModels.SMALL -> "small"
    VoiceModels.BASE -> "base"
    VoiceModels.TURBO -> "large-v3-turbo"
    else -> model.fileName
}

private fun modelDescription(model: VoiceModel) = when (model) {
    VoiceModels.SMALL -> R.string.voice_model_small
    VoiceModels.BASE -> R.string.voice_model_base
    else -> R.string.voice_model_turbo
}
