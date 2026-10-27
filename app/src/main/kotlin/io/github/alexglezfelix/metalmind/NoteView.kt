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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.alexglezfelix.metalmind.core.Frontmatter
import io.github.alexglezfelix.metalmind.core.IndexedNote
import io.github.alexglezfelix.metalmind.core.TagScanner

/**
 * Una nota del vault, solo para leer: el texto tal cual, con los encabezados
 * y el código marcados, y los `#tag` y `[[links]]` tocables. El texto se puede
 * seleccionar para copiarlo.
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
        val linkColor = MaterialTheme.colorScheme.primary
        val codeColor = MaterialTheme.colorScheme.surfaceVariant
        val frontmatterTags = remember(note.text) { frontmatterTags(note.text) }
        // El texto se arma una vez por nota; los toques llaman siempre a lo último.
        val tag by rememberUpdatedState(onTag)
        val link by rememberUpdatedState(onLink)
        val body = remember(note.text, linkColor, codeColor) {
            render(note.text, linkColor, codeColor, { tag(it) }, { link(it) })
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
            SelectionContainer {
                Text(body, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Los tags de `tags:` en el frontmatter, sin repetir. */
private fun frontmatterTags(text: String): List<String> {
    val split = Frontmatter.split(text) ?: return emptyList()
    return TagScanner.tags(text.substring(0, split.body)).distinct()
}

/** El cuerpo (sin frontmatter) con encabezados, bloques de código, tags y links. */
private fun render(
    text: String,
    linkColor: Color,
    codeColor: Color,
    onTag: (String) -> Unit,
    onLink: (String) -> Unit,
): AnnotatedString {
    val start = Frontmatter.bodyStart(text)
    val body = text.substring(start).trim('\n', '\r')
    val offset = text.indexOf(body, start).coerceAtLeast(start)
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor))
    return buildAnnotatedString {
        append(body)
        var at = 0
        var fence: String? = null
        for (line in body.split('\n')) {
            val end = at + line.length
            val trimmed = line.trimStart()
            when {
                fence != null -> {
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor), at, end)
                    if (trimmed.startsWith(fence)) fence = null
                }
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    fence = trimmed.take(3)
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor), at, end)
                }
                HEADING.matches(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val size = when (level) {
                        1 -> 1.4.em
                        2 -> 1.25.em
                        3 -> 1.1.em
                        else -> 1.em
                    }
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size), at, end)
                }
            }
            at = end + 1
        }
        for (span in TagScanner.markup(text)) {
            val from = span.start - offset
            val to = span.end - offset
            if (from < 0 || to > body.length) continue
            when (span) {
                is TagScanner.Tag -> addLink(LinkAnnotation.Clickable("tag", linkStyle) { onTag(span.name) }, from, to)
                is TagScanner.Link -> addLink(LinkAnnotation.Clickable("link", linkStyle) { onLink(span.target) }, from, to)
            }
        }
    }
}

private val HEADING = Regex("^#{1,6}([ \\t].*)?$")
