package io.github.alexglezfelix.metalmind

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.alexglezfelix.metalmind.core.Frontmatter
import io.github.alexglezfelix.metalmind.core.IndexedNote
import io.github.alexglezfelix.metalmind.core.NoteMarkdown
import io.github.alexglezfelix.metalmind.core.TagScanner
import kotlinx.coroutines.launch

/**
 * Una nota del vault, solo para leer: el markdown renderizado (ver
 * [NoteMarkdown]), con los `#tag`, los `[[links]]` y las URL tocables. El
 * texto se puede seleccionar para copiarlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    note: IndexedNote?,
    onBack: () -> Unit,
    onTag: (String) -> Unit,
    onLink: (String) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(note?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (note == null) {
            Text(
                stringResource(R.string.note_gone),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        val scheme = MaterialTheme.colorScheme
        val colors = NoteColors(
            link = scheme.primary,
            code = scheme.surfaceVariant,
            muted = scheme.onSurfaceVariant,
            highlight = scheme.tertiaryContainer,
        )
        val frontmatterTags = remember(note.text) { frontmatterTags(note.text) }
        // El texto se arma una vez por nota; los toques llaman siempre a lo último.
        val tag by rememberUpdatedState(onTag)
        val link by rememberUpdatedState(onLink)
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val noApp = stringResource(R.string.url_no_app)
        val url by rememberUpdatedState<(String) -> Unit> { target ->
            // Sin una app que abra el link (ni navegador), un aviso en vez de cerrar la app.
            if (runCatching { uriHandler.openUri(target) }.isFailure) {
                scope.launch { snackbar.showSnackbar(noApp.format(target)) }
            }
        }
        val body = remember(note.text, colors) {
            render(note.text, colors, { tag(it) }, { link(it) }, { url(it) })
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (frontmatterTags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (tag in frontmatterTags) {
                        SuggestionChip(onClick = { onTag(tag) }, label = { Text("#$tag") })
                    }
                }
            }
            val clipboard = LocalClipboardManager.current
            val noteClipboard = remember(clipboard) { NoteClipboard(clipboard) }
            CompositionLocalProvider(LocalClipboardManager provides noteClipboard) {
                SelectionContainer {
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Los tags de `tags:` en el frontmatter, sin repetir. */
private fun frontmatterTags(text: String): List<String> {
    val split = Frontmatter.split(text) ?: return emptyList()
    return TagScanner.tags(text.substring(0, split.body)).distinct()
}

/** Colores de la nota, del tema. */
private data class NoteColors(val link: Color, val code: Color, val muted: Color, val highlight: Color)

/** El cuerpo (sin frontmatter) ya sin marcas de markdown, con estilos, sangrías, tags y links. */
private fun render(
    text: String,
    colors: NoteColors,
    onTag: (String) -> Unit,
    onLink: (String) -> Unit,
    onUrl: (String) -> Unit,
): AnnotatedString {
    val note = NoteMarkdown.render(text)
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.link))
    val urlStyle = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        // Cada línea es un párrafo: un `\n` al final de un párrafo se dibuja como
        // un renglón vacío de más. En su lugar va U+2060, que no se ve ni agrega
        // renglón, y al copiar vuelve a ser `\n`.
        append(note.text.replace('\n', LINE_SEPARATOR))
        val indents = note.indents.associateBy { it.start }
        var start = 0
        while (start <= note.text.length) {
            val newline = note.text.indexOf('\n', start)
            val end = if (newline < 0) note.text.length else newline + 1
            val indent = indents[start]
            val style = if (indent == null) {
                ParagraphStyle()
            } else {
                val first = (indent.level * LIST_INDENT_EM).em
                val rest = (indent.level * LIST_INDENT_EM + indent.hanging * MARKER_EM).em
                ParagraphStyle(textIndent = TextIndent(firstLine = first, restLine = rest))
            }
            if (end > start) addStyle(style, start, end)
            if (newline < 0) break
            start = end
        }
        for (span in note.spans) {
            when (span) {
                is NoteMarkdown.Styled -> addStyle(spanStyle(span.style, colors), span.start, span.end)
                is NoteMarkdown.Tag ->
                    addLink(LinkAnnotation.Clickable("tag", linkStyle) { onTag(span.name) }, span.start, span.end)
                is NoteMarkdown.Link ->
                    addLink(LinkAnnotation.Clickable("link", linkStyle) { onLink(span.target) }, span.start, span.end)
                is NoteMarkdown.Url ->
                    addLink(LinkAnnotation.Clickable("url", urlStyle) { onUrl(span.url) }, span.start, span.end)
            }
        }
    }
}

private fun spanStyle(style: NoteMarkdown.Style, colors: NoteColors): SpanStyle = when (style) {
    NoteMarkdown.Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    NoteMarkdown.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    NoteMarkdown.Style.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    NoteMarkdown.Style.Highlight -> SpanStyle(background = colors.highlight)
    NoteMarkdown.Style.Code, NoteMarkdown.Style.CodeBlock ->
        SpanStyle(fontFamily = FontFamily.Monospace, background = colors.code)
    NoteMarkdown.Style.Table -> SpanStyle(fontFamily = FontFamily.Monospace)
    NoteMarkdown.Style.Heading1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.4.em)
    NoteMarkdown.Style.Heading2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.25.em)
    NoteMarkdown.Style.Heading3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.1.em)
    NoteMarkdown.Style.Heading4, NoteMarkdown.Style.Heading5, NoteMarkdown.Style.Heading6 ->
        SpanStyle(fontWeight = FontWeight.Bold)
    NoteMarkdown.Style.Quote -> SpanStyle(color = colors.muted)
    NoteMarkdown.Style.Marker -> SpanStyle(color = colors.link)
    NoteMarkdown.Style.Rule -> SpanStyle(color = colors.muted)
}

/** Sangría por nivel de lista, y ancho aproximado de un carácter de viñeta. */
private const val LIST_INDENT_EM = 1.2f
private const val MARKER_EM = 0.5f

/** Un carácter invisible que casi nunca está en una nota. */
private const val LINE_SEPARATOR = '\u2060'

/**
 * Lo copiado de la nota, con saltos de línea normales en vez de [LINE_SEPARATOR].
 * `SelectionContainer` copia con `LocalClipboardManager` en Compose 1.7; si al
 * actualizar pasa a `LocalClipboard`, hay que envolver ese.
 */
private class NoteClipboard(private val clipboard: ClipboardManager) : ClipboardManager by clipboard {
    override fun setText(annotatedString: AnnotatedString) {
        clipboard.setText(AnnotatedString(annotatedString.text.replace(LINE_SEPARATOR, '\n')))
    }
}
