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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import io.github.alexglezfelix.metalmind.core.AudioInbox
import io.github.alexglezfelix.metalmind.core.ModelCheck
import io.github.alexglezfelix.metalmind.core.VoiceModel
import io.github.alexglezfelix.metalmind.core.VoiceModels
import io.github.alexglezfelix.metalmind.core.VoiceRecorder
import io.github.alexglezfelix.metalmind.core.VoiceSession
import io.github.alexglezfelix.metalmind.core.VoiceState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Las notas de voz de la captura: el micrófono, sus diálogos y el buzón de audio. */
@Stable
class VoiceCapture internal constructor(
    internal val flow: VoiceFlow,
    internal val session: VoiceSession,
    internal val store: VoiceModelStore,
    internal val inbox: VoiceInbox,
) {
    private var whisperState by mutableStateOf(store.whisper.enabled)

    /** Si Whisper está encendido; con estado, para que los diálogos se actualicen. */
    var whisper: Boolean
        get() = whisperState
        set(on) {
            store.whisper.enabled = on
            whisperState = on
        }

    /** El toque en el micrófono. */
    fun mic() {
        if (!session.busy) flow.mic()
    }
}

/**
 * Cada grabación queda en `buzon/audio/` dentro de [folder]. [onText] recibe
 * lo transcrito (con el enlace al audio) para ponerlo en el campo;
 * [onMessage], lo que haya que avisar.
 */
@Composable
fun rememberVoiceCapture(folder: Uri, onText: (String) -> Unit, onMessage: (String) -> Unit): VoiceCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree by rememberUpdatedState(folder)
    val deliver by rememberUpdatedState(onText)
    val message by rememberUpdatedState(onMessage)
    val voice = remember {
        val app = context.applicationContext
        val store = VoiceModelStore(app)
        val inbox = VoiceInbox(app) { tree }
        lateinit var voice: VoiceCapture
        val session = VoiceSession(
            scope = scope,
            newRecorder = { VoiceRecorder(AudioRecordSource()) },
            transcriber = WhisperTranscriber({ store.model }, app.applicationInfo.nativeLibraryDir),
            sink = inbox,
            transcribe = { voice.whisper },
        )
        val flow = VoiceFlow(
            needsModel = { voice.whisper && store.model == null },
            hasPermission = { hasMicPermission(app) },
            record = { session.start() },
        )
        voice = VoiceCapture(flow, session, store, inbox)
        voice
    }
    // Los audios que no alcanzaron a llegar al vault la vez anterior.
    val flushFailedFormat = stringResource(R.string.voice_flush_failed)
    LaunchedEffect(folder) {
        val result = withContext(Dispatchers.IO) {
            try {
                voice.inbox.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
        when {
            result is Exception -> message(flushFailedFormat.format(result.message ?: result.javaClass.simpleName))
            result is Int && result > 0 ->
                message(context.resources.getQuantityString(R.plurals.voice_flushed, result, result, AudioInbox.PATH))
        }
    }
    // Salir de la captura corta la grabación o la transcripción: whisper no
    // debe seguir gastando batería para un campo que ya no existe.
    DisposableEffect(Unit) { onDispose { voice.session.cancel() } }

    val state by voice.session.state.collectAsState()
    val savedFormat = stringResource(R.string.voice_saved)
    val nothingHeard = stringResource(R.string.voice_nothing_heard)
    val nothingHeardKeptFormat = stringResource(R.string.voice_nothing_heard_kept)
    val failedKeptFormat = stringResource(R.string.voice_failed_kept)
    val errorFormat = stringResource(R.string.voice_error)
    val timeFormat = stringResource(R.string.voice_done_time)
    LaunchedEffect(state) {
        fun path(audio: String) = "${AudioInbox.PATH}/$audio"
        when (val s = state) {
            is VoiceState.Saved -> {
                voice.session.dismiss()
                message(savedFormat.format(path(s.audio)))
            }
            is VoiceState.Done -> {
                // La nota enlaza su audio: así se sabe que ya está transcrito (README).
                deliver(s.audio?.let { s.text + "\n" + AudioInbox.embed(it) } ?: s.text)
                voice.session.dismiss()
                s.audioError?.let { message(errorFormat.format(it)) }
                // Cuánto tardó, para saber si el teléfono va bien con este modelo (#17).
                message(timeFormat.format(clock(((s.millis + 500) / 1000).toInt()), clock(s.audioSeconds)))
            }
            is VoiceState.NothingHeard -> {
                voice.session.dismiss()
                message(s.audio?.let { nothingHeardKeptFormat.format(path(it)) } ?: nothingHeard)
            }
            is VoiceState.Failed -> {
                voice.session.dismiss()
                message(s.audio?.let { failedKeptFormat.format(s.message, path(it)) } ?: errorFormat.format(s.message))
            }
            else -> {}
        }
    }

    // Con la pantalla encendida mientras se graba, se guarda o se transcribe:
    // apagarse a mitad de un dictado cortaría el micrófono.
    val busy = state is VoiceState.Recording || state is VoiceState.Saving || state is VoiceState.Transcribing
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
    // Primero el estado: los ajustes se pueden abrir encima de la grabación.
    val state by voice.session.state.collectAsState()
    when (val s = state) {
        is VoiceState.Recording -> RecordingDialog(voice, s)
        VoiceState.Saving -> SavingDialog()
        is VoiceState.Transcribing -> TranscribingDialog(voice, s)
        else -> {}
    }

    when (voice.flow.step) {
        VoiceStep.None -> {}
        VoiceStep.ChooseModel, VoiceStep.Settings -> ModelDialog(voice)
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
}

/** Que tocar fuera o Atrás no tire una grabación de varios minutos. */
private val sticky = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

@Composable
private fun RecordingDialog(voice: VoiceCapture, state: VoiceState.Recording) {
    // Whisper se puede encender o apagar mientras se graba: vale al detener.
    val whisper = voice.whisper
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
                        Icon(
                            painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(if (whisper) R.string.voice_stop else R.string.voice_stop_save),
                        )
                    }
                }
                // El nivel, para ver que el micrófono oye; ×4 porque la voz
                // normal anda por 0.05–0.2 de la escala completa.
                LinearProgressIndicator(
                    progress = { (state.level * 4).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        if (whisper) R.string.voice_limit_whisper else R.string.voice_limit,
                        clock(VoiceRecorder.MAX_SECONDS),
                        AudioInbox.PATH,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Los ajustes se abren encima, sin cortar la grabación.
                TextButton(onClick = voice.flow::settings) {
                    Text(
                        when {
                            !whisper -> stringResource(R.string.voice_whisper_off)
                            else -> stringResource(
                                R.string.voice_whisper_on,
                                voice.store.label ?: stringResource(R.string.voice_whisper_no_model),
                            )
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = voice.session::stop) {
                Text(stringResource(if (whisper) R.string.voice_transcribe else R.string.voice_save))
            }
        },
        dismissButton = { TextButton(onClick = voice.session::cancel) { Text(stringResource(R.string.voice_discard)) } },
    )
}

@Composable
private fun SavingDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = sticky,
        title = { Text(stringResource(R.string.voice_saving)) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
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
        // El audio ya está en el buzón: cancelar solo corta whisper.
        dismissButton = { TextButton(onClick = voice.session::cancel) { Text(stringResource(R.string.voice_stop_transcribing)) } },
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
    val whisper = voice.whisper
    AlertDialog(
        onDismissRequest = { if (!copying) voice.flow.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !copying, dismissOnClickOutside = !copying),
        title = { Text(stringResource(R.string.voice_model_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.voice_whisper_explain, AudioInbox.PATH))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !copying) { voice.whisper = !whisper },
                ) {
                    Text(stringResource(R.string.voice_whisper_switch), modifier = Modifier.weight(1f))
                    Switch(checked = whisper, onCheckedChange = null, enabled = !copying)
                }
                if (!whisper) return@Column
                HorizontalDivider()
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
            when {
                whisper -> Button(enabled = !copying, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.voice_model_pick))
                }
                // Se venía del micrófono y se apagó Whisper: a grabar, sin modelo.
                voice.flow.step == VoiceStep.ChooseModel -> Button(onClick = voice.flow::modelReady) {
                    Text(stringResource(R.string.voice_record_only))
                }
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
