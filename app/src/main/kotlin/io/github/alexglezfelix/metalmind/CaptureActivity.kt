package io.github.alexglezfelix.metalmind

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.alexglezfelix.metalmind.core.Capture
import io.github.alexglezfelix.metalmind.core.CaptureResult
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = VaultSettings(applicationContext)
        setContent {
            MetalmindTheme {
                CaptureScreen(settings)
            }
        }
    }
}

@Composable
private fun MetalmindTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Lo que pasó al guardar, ya listo para mostrar. */
private sealed interface Outcome {
    data class Saved(val name: String) : Outcome
    data class Failed(val message: String) : Outcome
    data object FolderLost : Outcome
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureScreen(settings: VaultSettings) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(settings.folder) }
    var text by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var folderLost by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            settings.choose(tree)
            folder = tree
            folderLost = false
        }
    }

    val current = folder
    if (current == null) {
        ChooseFolder(lost = folderLost, onChoose = { picker.launch(null) })
        return
    }

    val savedFormat = stringResource(R.string.saved)
    val writeErrorFormat = stringResource(R.string.error_write)
    val nameTakenFormat = stringResource(R.string.error_name_taken)

    fun save() {
        if (saving || text.isBlank()) return
        saving = true
        val toSave = text
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    when (val r = Capture.save(SafNoteFolder(context.contentResolver, current), toSave, LocalDateTime.now())) {
                        is CaptureResult.Saved -> Outcome.Saved(r.name)
                        is CaptureResult.NameTaken -> Outcome.Failed(nameTakenFormat.format(r.stem))
                        CaptureResult.Empty -> null
                    }
                } catch (_: SecurityException) {
                    Outcome.FolderLost
                } catch (e: IOException) {
                    Outcome.Failed(writeErrorFormat.format(e.message.orEmpty()))
                } catch (e: IllegalArgumentException) {
                    // Así avisa el proveedor de que la carpeta ya no existe.
                    Outcome.Failed(writeErrorFormat.format(e.message.orEmpty()))
                }
            }
            saving = false
            when (outcome) {
                is Outcome.Saved -> {
                    // Solo se borra si no se escribió nada más mientras se guardaba.
                    if (text == toSave) text = ""
                    snackbar.showSnackbar(savedFormat.format(outcome.name), duration = SnackbarDuration.Short)
                }
                is Outcome.Failed -> snackbar.showSnackbar(outcome.message, duration = SnackbarDuration.Long)
                Outcome.FolderLost -> {
                    settings.forget()
                    folderLost = true
                    folder = null
                }
                null -> {}
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(SafNoteFolder.label(current), maxLines = 1) },
                actions = {
                    TextButton(onClick = { picker.launch(current) }) {
                        Text(stringResource(R.string.change_folder))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (text.isNotBlank()) {
                FloatingActionButton(onClick = ::save) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.save))
                }
            }
        },
    ) { padding ->
        CaptureField(
            text = text,
            onTextChange = { text = it },
            onSave = ::save,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

@Composable
private fun CaptureField(
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = text,
        onValueChange = onTextChange,
        placeholder = { Text(stringResource(R.string.placeholder)) },
        // La tecla de acción del teclado guarda; con teclado físico, Ctrl+Enter.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onSave() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .focusRequester(focus)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed) {
                    onSave()
                    true
                } else {
                    false
                }
            },
    )
}

@Composable
private fun ChooseFolder(lost: Boolean, onChoose: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (lost) {
                Text(stringResource(R.string.folder_lost), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.choose_folder_explain), modifier = Modifier.fillMaxWidth())
            Button(onClick = onChoose) { Text(stringResource(R.string.choose_folder)) }
        }
    }
}

