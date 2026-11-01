package io.github.alexglezfelix.metalmind

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Buscar en el vault y leer notas, sin editarlas. Se abre desde la captura
 * (la lupa, o `Ctrl+K` con teclado físico). En pantalla grande, la lista a la
 * izquierda y la nota a la derecha.
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

@Composable
private fun SearchScreen(tree: Uri, onClose: () -> Unit) {
    val state = rememberSearchState(tree)
    BackHandler(enabled = state.opened.isNotEmpty()) { state.back() }

    val layout = rememberPaneLayout()
    val twoPanes = layout.twoPanes
    val root = Modifier
        .imePadding()
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                // Esc atrás; Ctrl+K vuelve a la búsqueda desde una nota.
                it.key == Key.Escape -> if (!state.back()) onClose()
                it.key == Key.K && it.isCtrlPressed ->
                    if (twoPanes) state.fieldFocus.requestFocus() else state.closeNotes()
                else -> return@onPreviewKeyEvent false
            }
            true
        }
    val close: @Composable () -> Unit = {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
    }

    when {
        twoPanes -> Row(root.fillMaxSize()) {
            SearchPane(
                state,
                twoPanes = true,
                navigationIcon = close,
                autoFocus = true,
                modifier = Modifier.width(layout.listWidth),
            )
            VerticalDivider()
            Box(Modifier.weight(1f)) {
                if (state.openNote != null) OpenNote(state) else NoNoteOpen()
            }
        }
        state.openNote != null -> OpenNote(state, root)
        else -> SearchPane(state, twoPanes = false, navigationIcon = close, autoFocus = true, modifier = root)
    }
}
