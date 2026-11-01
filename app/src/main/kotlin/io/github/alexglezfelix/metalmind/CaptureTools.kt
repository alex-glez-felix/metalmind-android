package io.github.alexglezfelix.metalmind

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexglezfelix.metalmind.core.Formatted
import io.github.alexglezfelix.metalmind.core.Formatting
import io.github.alexglezfelix.metalmind.core.Formatting.Block
import io.github.alexglezfelix.metalmind.core.Formatting.Inline
import io.github.alexglezfelix.metalmind.core.LinkSuggestion

/** Un botón de formato: lo que hace sobre el texto. */
sealed interface Format {
    data class Wrap(val style: Inline) : Format
    data class Lines(val block: Block) : Format

    fun apply(value: TextFieldValue): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        val done = when (this) {
            is Wrap -> Formatting.inline(value.text, start, end, style)
            is Lines -> Formatting.block(value.text, start, end, block)
        }
        return done.toValue()
    }

    companion object {
        /**
         * El formato de un atajo de teclado físico: Ctrl+B negrita, Ctrl+I
         * cursiva, Ctrl+E código, Ctrl+Shift+8 lista y Ctrl+Shift+9 checkbox
         * (los de Google Docs).
         */
        fun forShortcut(event: KeyEvent): Format? {
            if (!event.isCtrlPressed) return null
            return when {
                event.isShiftPressed -> when (event.key) {
                    Key.Eight, Key.NumPad8 -> Lines(Block.LIST)
                    Key.Nine, Key.NumPad9 -> Lines(Block.CHECKBOX)
                    else -> null
                }
                event.key == Key.B -> Wrap(Inline.BOLD)
                event.key == Key.I -> Wrap(Inline.ITALIC)
                event.key == Key.E -> Wrap(Inline.CODE)
                else -> null
            }
        }
    }
}

/** Con Enter tras un elemento de lista, el renglón nuevo sigue la lista. */
fun continueList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (!new.selection.collapsed) return new
    return Formatting.typedNewline(old.text, old.selection.min, old.selection.max, new.text, new.selection.end)
        ?.toValue() ?: new
}

private fun Formatted.toValue() = TextFieldValue(text, TextRange(start, end))

/** La barra de formato sobre el teclado. Los botones no toman el foco: el teclado sigue arriba. */
@Composable
fun FormatBar(onFormat: (Format) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
    ) {
        FormatButton(stringResource(R.string.format_bold), { onFormat(Format.Wrap(Inline.BOLD)) }) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
        }
        FormatButton(stringResource(R.string.format_italic), { onFormat(Format.Wrap(Inline.ITALIC)) }) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif)) { append("I") }
        }
        FormatButton(stringResource(R.string.format_list), { onFormat(Format.Lines(Block.LIST)) }) {
            withStyle(SpanStyle(fontWeight = FontWeight.Black, fontSize = 22.sp)) { append("•") }
        }
        FormatButton(stringResource(R.string.format_checkbox), { onFormat(Format.Lines(Block.CHECKBOX)) }) { append("☐") }
        FormatButton(stringResource(R.string.format_code), { onFormat(Format.Wrap(Inline.CODE)) }) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("</>") }
        }
    }
}

@Composable
private fun FormatButton(
    description: String,
    onClick: () -> Unit,
    label: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(buildAnnotatedString(label), style = MaterialTheme.typography.titleMedium)
    }
}

/** La fila de notas sobre el teclado mientras se escribe un `[[link]]`. */
@Composable
fun LinkSuggestions(suggestions: List<LinkSuggestion>, onPick: (LinkSuggestion) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (suggestion in suggestions) {
            SuggestionChip(
                onClick = { onPick(suggestion) },
                label = {
                    // La carpeta, si la hay, para distinguir notas con el mismo nombre.
                    Column(modifier = Modifier.widthIn(max = 280.dp).padding(vertical = 4.dp)) {
                        Text(suggestion.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (suggestion.folder.isNotEmpty()) {
                            Text(
                                suggestion.folder,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Lo que va sobre el teclado: las sugerencias, si las hay, y la barra de formato. */
@Composable
fun CaptureToolbar(suggestions: @Composable (() -> Unit)?, onFormat: (Format) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        // Sobre la barra de navegación del sistema: con el teclado arriba, el
        // imePadding de la pantalla ya la cubre y esto no suma nada.
        Column(modifier = Modifier.navigationBarsPadding()) {
            suggestions?.invoke()
            FormatBar(onFormat)
        }
    }
}
