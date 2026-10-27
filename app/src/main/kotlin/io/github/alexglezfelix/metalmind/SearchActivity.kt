package io.github.alexglezfelix.metalmind

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexglezfelix.metalmind.core.SearchHit
import io.github.alexglezfelix.metalmind.core.TagCompletion
import io.github.alexglezfelix.metalmind.core.VaultIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Buscar en el vault y leer notas, sin editarlas. Se abre desde la captura
 * (la lupa, o `Ctrl+K` con teclado físico).
 */
class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val tree = VaultSettings(applicationContext).folder
        if (tree == null) {
            // Sin carpeta no hay nada que buscar: la captura pide elegirla.
            finish()
            return
        }
        setContent {
            MetalmindTheme {
                SearchScreen(tree = tree, onClose = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(tree: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    // `null` mientras se lee la caché; después, al día con el vault.
    var index by remember { mutableStateOf<VaultIndex?>(null) }
    var reading by remember { mutableStateOf(true) }
    var readFailed by remember { mutableStateOf(false) }
    LaunchedEffect(tree) {
        index = Vaults.cached(context, tree)
        try {
            index = Vaults.refresh(context, tree)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin acceso a la carpeta o con el proveedor fallando, se busca en lo de la caché.
            readFailed = true
        }
        reading = false
    }

    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // Las notas abiertas, de la primera a la que se ve: atrás vuelve a la anterior.
    var opened by remember { mutableStateOf(listOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val current = index
    val hits by produceState(emptyList<SearchHit>(), field.text, current) {
        value = if (current == null) emptyList() else withContext(Dispatchers.Default) { current.search(field.text) }
    }
    val tagQuery = if (field.selection.collapsed) TagCompletion.at(field.text, field.selection.end) else null
    val suggestions = remember(tagQuery, current) { tagQuery?.let { current?.suggestions(it.prefix) }.orEmpty() }

    fun open(id: String) {
        keyboard?.hide()
        opened = opened + id
    }

    fun back() {
        if (opened.isEmpty()) onClose() else opened = opened.dropLast(1)
    }

    fun searchTag(name: String) {
        val text = "#$name "
        field = TextFieldValue(text, TextRange(text.length))
        opened = emptyList()
    }

    val missingFormat = stringResource(R.string.link_missing)
    fun follow(target: String) {
        val note = current?.resolve(target)
        if (note != null) {
            open(note.id)
        } else {
            scope.launch { snackbar.showSnackbar(missingFormat.format(target)) }
        }
    }

    BackHandler(enabled = opened.isNotEmpty()) { back() }

    val root = Modifier
        .imePadding()
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                // Esc atrás; Ctrl+K vuelve a la búsqueda desde una nota.
                it.key == Key.Escape -> back()
                it.key == Key.K && it.isCtrlPressed -> opened = emptyList()
                else -> return@onPreviewKeyEvent false
            }
            true
        }

    val openNote = opened.lastOrNull()
    if (openNote != null) {
        NoteScreen(
            note = current?.note(openNote),
            onBack = ::back,
            onTag = ::searchTag,
            onLink = ::follow,
            snackbar = snackbar,
            modifier = root,
        )
        return
    }

    Scaffold(
        modifier = root,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    SearchField(
                        value = field,
                        onValueChange = { field = it },
                        onSubmit = { hits.firstOrNull()?.let { open(it.id) } },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tagQuery != null && suggestions.isNotEmpty()) {
                TagSuggestions(suggestions) { picked ->
                    val done = TagCompletion.accept(field.text, tagQuery, picked.name)
                    field = TextFieldValue(done.text, TextRange(done.cursor))
                }
            }
        },
    ) { padding ->
        val notes = current?.noteCount ?: 0
        val status = when {
            notes == 0 && reading -> stringResource(R.string.search_reading)
            notes == 0 && readFailed -> stringResource(R.string.search_read_failed)
            notes == 0 -> stringResource(R.string.search_no_notes)
            hits.isEmpty() -> stringResource(R.string.search_no_results)
            else -> null
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (status != null) {
                item {
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(hits, key = { it.id }) { hit ->
                SearchResult(hit, onClick = { open(hit.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onSubmit: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        // La tecla de acción (o Enter) abre el primer resultado.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        trailingIcon = {
            if (value.text.isNotEmpty()) {
                IconButton(onClick = { onValueChange(TextFieldValue()) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun SearchResult(hit: SearchHit, onClick: () -> Unit) {
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    val folder = hit.path.substringBeforeLast('/', "")
    ListItem(
        overlineContent = if (folder.isNotEmpty()) ({ Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        headlineContent = { Text(highlighted(hit.title, hit.titleHighlights, highlight), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (hit.snippet.isNotEmpty()) {
            { Text(highlighted(hit.snippet, hit.highlights, highlight), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (range in ranges) {
            if (range.first >= 0 && range.last < text.length) addStyle(style, range.first, range.last + 1)
        }
    }
