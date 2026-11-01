package io.github.alexglezfelix.metalmind

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.alexglezfelix.metalmind.core.SearchHit
import io.github.alexglezfelix.metalmind.core.TagCompletion
import io.github.alexglezfelix.metalmind.core.VaultIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La búsqueda: el índice del vault, lo escrito, los resultados y las notas
 * abiertas. La comparten la pantalla de búsqueda y, con dos paneles, la
 * captura.
 */
@Stable
class SearchState(
    fieldState: MutableState<TextFieldValue>,
    selectedState: MutableState<String?>,
    openedState: MutableState<List<String>>,
) {
    /** `null` mientras se lee la caché; después, al día con el vault. */
    var index by mutableStateOf<VaultIndex?>(null)
        private set
    var reading by mutableStateOf(true)
        private set
    var readFailed by mutableStateOf(false)
        private set

    var field by fieldState
        private set
    var hits by mutableStateOf(emptyList<SearchHit>())
        private set

    /** El resultado marcado: con las flechas o, con dos paneles, el que se ve a la derecha. */
    var selected by selectedState
        private set

    /** Las notas abiertas, de la primera a la que se ve: atrás vuelve a la anterior. */
    var opened by openedState
        private set
    val openNote: String? get() = opened.lastOrNull()

    val fieldFocus = FocusRequester()

    /** Si el foco está en el campo de búsqueda (ahí las flechas recorren la lista). */
    var fieldFocused by mutableStateOf(false)
        internal set
    val snackbar = SnackbarHostState()

    /** Cuántas veces se pidió releer el vault (al volver a la app, al guardar). */
    internal var refreshes by mutableIntStateOf(0)
        private set

    /** Releer el vault: solo las notas que cambiaron. */
    fun refresh() {
        refreshes++
    }

    internal suspend fun load(context: Context, tree: Uri) {
        if (index == null) index = Vaults.cached(context, tree)
        try {
            index = Vaults.refresh(context, tree)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin acceso a la carpeta o con el proveedor fallando, se busca en lo de la caché.
            readFailed = true
            reading = false
            return
        }
        readFailed = false
        reading = false
    }

    internal fun updateHits(hits: List<SearchHit>) {
        this.hits = hits
    }

    fun edit(value: TextFieldValue) {
        // Otra búsqueda: Enter vuelve a abrir el primero.
        if (value.text != field.text) selected = null
        field = value
    }

    /**
     * Abre una nota desde la lista: reemplaza las que hubiera abiertas. Con
     * [mark] queda marcada (con dos paneles, es la que se ve a la derecha);
     * en un panel no, para que Enter siga abriendo el primero.
     */
    fun show(id: String, mark: Boolean) {
        if (mark) selected = id
        opened = listOf(id)
    }

    /** Sigue un `[[link]]`. `false` si no hay ninguna nota así. */
    fun follow(target: String): Boolean {
        val note = index?.resolve(target) ?: return false
        opened = opened + note.id
        return true
    }

    /** Vuelve a la nota anterior. `false` si no había ninguna abierta. */
    fun back(): Boolean {
        if (opened.isEmpty()) return false
        opened = opened.dropLast(1)
        return true
    }

    fun closeNotes() {
        opened = emptyList()
    }

    fun searchTag(name: String) {
        val text = "#$name "
        edit(TextFieldValue(text, TextRange(text.length)))
        opened = emptyList()
    }

    /** Mueve la marca con las flechas; con dos paneles, la nota se ve a la derecha. */
    fun move(delta: Int, showRight: Boolean) {
        val next = Panes.step(hits.map { it.id }, selected, delta) ?: return
        if (showRight) show(next, mark = true) else selected = next
    }

    /** Lo que abre Enter: el marcado si sigue en la lista, si no el primero. */
    fun submitTarget(): String? = hits.firstOrNull { it.id == selected }?.id ?: hits.firstOrNull()?.id
}

@Composable
fun rememberSearchState(tree: Uri): SearchState {
    val context = LocalContext.current
    // Con la carpeta: al cambiarla, lo buscado y las notas abiertas eran del
    // vault anterior (la nota abierta saldría como que ya no está).
    val field = rememberSaveable(tree, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val selected = rememberSaveable(tree) { mutableStateOf<String?>(null) }
    val opened = rememberSaveable(tree) { mutableStateOf(listOf<String>()) }
    val state = remember(tree) { SearchState(field, selected, opened) }
    // Al volver a la app (y al guardar, con refresh()) se relee lo que cambió:
    // con dos paneles la búsqueda vive tanto como la captura.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    LaunchedEffect(state, state.refreshes) { state.load(context, tree) }
    return state
}

/**
 * El campo de búsqueda y los resultados. Con [twoPanes], las flechas muestran
 * a la derecha el resultado marcado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPane(
    state: SearchState,
    twoPanes: Boolean,
    navigationIcon: @Composable () -> Unit,
    autoFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val current = state.index
    // En otro hilo: con miles de notas, cada tecla no frena el teclado.
    val text = state.field.text
    LaunchedEffect(text, current) {
        if (current != null) state.updateHits(withContext(Dispatchers.Default) { current.search(text) })
    }
    val field = state.field
    val tagQuery = if (field.selection.collapsed) TagCompletion.at(field.text, field.selection.end) else null
    val suggestions = remember(tagQuery, current) { tagQuery?.let { current?.suggestions(it.prefix) }.orEmpty() }

    fun open(id: String) {
        keyboard?.hide()
        state.show(id, mark = twoPanes)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = navigationIcon,
                title = {
                    SearchField(
                        value = field,
                        onValueChange = state::edit,
                        onSubmit = { state.submitTarget()?.let(::open) },
                        onMove = { state.move(it, showRight = twoPanes) },
                        focus = state.fieldFocus,
                        onFocusChange = { state.fieldFocused = it },
                        autoFocus = autoFocus,
                    )
                },
            )
        },
        bottomBar = {
            if (tagQuery != null && suggestions.isNotEmpty()) {
                TagSuggestions(suggestions) { picked ->
                    val done = TagCompletion.accept(field.text, tagQuery, picked.name)
                    state.edit(TextFieldValue(done.text, TextRange(done.cursor)))
                }
            }
        },
    ) { padding ->
        val notes = current?.noteCount ?: 0
        val status = when {
            notes == 0 && state.reading -> stringResource(R.string.search_reading)
            notes == 0 && state.readFailed -> stringResource(R.string.search_read_failed)
            notes == 0 -> stringResource(R.string.search_no_notes)
            state.hits.isEmpty() -> stringResource(R.string.search_no_results)
            else -> null
        }
        // Con algo marcado, que se vea; si no, los resultados desde arriba (si
        // no, al cambiar, la lista sigue al que estaba primero y queda a media
        // altura). Un solo efecto: si el índice se actualiza con un resultado
        // marcado, la lista no salta al tope.
        val list = rememberLazyListState()
        val hits = state.hits
        val marked = hits.indexOfFirst { it.id == state.selected }
        LaunchedEffect(hits, marked) {
            if (marked < 0) {
                list.scrollToItem(0)
                return@LaunchedEffect
            }
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == marked }
            when {
                item == null -> list.animateScrollToItem(marked)
                item.offset < 0 -> list.animateScrollBy(item.offset.toFloat())
                item.offset + item.size > info.viewportEndOffset ->
                    list.animateScrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
            }
        }
        LazyColumn(state = list, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (status != null) {
                item {
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.hits, key = { it.id }) { hit ->
                SearchResult(hit, marked = hit.id == state.selected, onClick = { open(hit.id) })
                HorizontalDivider()
            }
        }
    }
}

/** La nota abierta, con sus `#tag` y `[[links]]` llevando a la búsqueda. */
@Composable
fun OpenNote(state: SearchState, modifier: Modifier = Modifier) {
    val id = state.openNote ?: return
    val scope = rememberCoroutineScope()
    val missingFormat = stringResource(R.string.link_missing)
    // Otra nota, desde arriba: sin la clave, el scroll de la anterior se queda.
    key(id) {
        NoteScreen(
            note = state.index?.note(id),
            onBack = { state.back() },
            onTag = state::searchTag,
            onLink = { target ->
                if (!state.follow(target)) scope.launch { state.snackbar.showSnackbar(missingFormat.format(target)) }
            },
            snackbar = state.snackbar,
            modifier = modifier,
        )
    }
}

/** El panel derecho de la búsqueda cuando todavía no se abrió ninguna nota. */
@Composable
fun NoNoteOpen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.pick_a_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onMove: (Int) -> Unit,
    focus: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    autoFocus: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (autoFocus) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        // La tecla de acción (o Enter) abre el marcado, o el primero.
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
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .onPreviewKeyEvent {
                // Con teclado físico, las flechas recorren los resultados.
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> onMove(1)
                    Key.DirectionUp -> onMove(-1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    )
}

@Composable
private fun SearchResult(hit: SearchHit, marked: Boolean, onClick: () -> Unit) {
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
        colors = if (marked) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
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
