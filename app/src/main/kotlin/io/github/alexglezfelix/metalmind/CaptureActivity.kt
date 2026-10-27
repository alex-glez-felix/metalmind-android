package io.github.alexglezfelix.metalmind

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.alexglezfelix.metalmind.core.Capture
import io.github.alexglezfelix.metalmind.core.CaptureResult
import io.github.alexglezfelix.metalmind.core.SharedText
import io.github.alexglezfelix.metalmind.core.TagCompletion
import io.github.alexglezfelix.metalmind.core.VaultIndex
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = VaultSettings(applicationContext)
        val shared = sharedText(intent)
        setContent {
            MetalmindTheme {
                CaptureScreen(
                    settings = settings,
                    initialText = shared.orEmpty(),
                    // Al compartir, guardar devuelve a la app de donde se vino.
                    onSaved = if (shared != null) ({ finish() }) else null,
                )
            }
        }
    }

    /** El texto de "Compartir con Metalmind", o `null` si no se abrió así. */
    private fun sharedText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        return SharedText.toNote(
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        )
    }
}

/** Lo que pasó al guardar, ya listo para mostrar. */
private sealed interface Outcome {
    data class Saved(val name: String) : Outcome
    data class Failed(val message: String) : Outcome
    data object FolderLost : Outcome
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureScreen(settings: VaultSettings, initialText: String, onSaved: (() -> Unit)?) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(settings.folder) }
    // Con el cursor al final, para seguir escribiendo después de lo compartido.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    val text = field.text
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

    // Los tags del vault: primero los de la caché, enseguida; después, al día.
    var tags by remember(current) { mutableStateOf(VaultIndex.EMPTY) }
    LaunchedEffect(current) {
        tags = Vaults.cached(context, current)
        try {
            tags = Vaults.refresh(context, current)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Las sugerencias son opcionales: sin acceso a la carpeta, si ya no
            // existe o si el proveedor falla, quedan las de la caché, y el
            // guardado dirá lo que haga falta.
        }
    }
    val query = if (field.selection.collapsed) TagCompletion.at(text, field.selection.end) else null
    val suggestions = remember(query, tags) { query?.let { tags.suggestions(it.prefix) }.orEmpty() }

    val savedFormat = stringResource(R.string.saved)
    val writeErrorFormat = stringResource(R.string.error_write)
    val nameTakenFormat = stringResource(R.string.error_name_taken)

    fun search() {
        context.startActivity(Intent(context, SearchActivity::class.java))
    }

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
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SecurityException) {
                    Outcome.FolderLost
                } catch (e: Exception) {
                    // IOException, o lo que el proveedor de la carpeta relance tal
                    // cual (IllegalArgumentException si ya no existe,
                    // UnsupportedOperationException si no deja crear, …): nunca
                    // cerrar la app con el texto sin guardar.
                    Outcome.Failed(writeErrorFormat.format(e.message ?: e.javaClass.simpleName))
                }
            }
            saving = false
            when (outcome) {
                is Outcome.Saved -> {
                    val message = savedFormat.format(outcome.name)
                    if (onSaved != null) {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onSaved()
                        return@launch
                    }
                    // Solo se borra si no se escribió nada más mientras se guardaba.
                    if (field.text == toSave) field = TextFieldValue()
                    snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
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
                    IconButton(onClick = ::search) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                    }
                    TextButton(onClick = { picker.launch(current) }) {
                        Text(stringResource(R.string.change_folder))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (query != null && suggestions.isNotEmpty()) {
                TagSuggestions(suggestions) { picked ->
                    val done = TagCompletion.accept(text, query, picked.name)
                    field = TextFieldValue(done.text, TextRange(done.cursor))
                }
            }
        },
        floatingActionButton = {
            if (text.isNotBlank()) {
                FloatingActionButton(onClick = ::save) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.save))
                }
            }
        },
    ) { padding ->
        CaptureField(
            value = field,
            onValueChange = { field = it },
            onSave = ::save,
            onSearch = ::search,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

@Composable
private fun CaptureField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.placeholder)) },
        // La tecla de acción del teclado guarda; con teclado físico, Ctrl+Enter.
        // Ctrl+K abre la búsqueda.
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
                if (it.type != KeyEventType.KeyDown || !it.isCtrlPressed) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Enter -> onSave()
                    Key.K -> onSearch()
                    else -> return@onPreviewKeyEvent false
                }
                true
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

