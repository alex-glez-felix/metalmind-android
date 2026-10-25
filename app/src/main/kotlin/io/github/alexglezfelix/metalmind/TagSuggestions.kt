package io.github.alexglezfelix.metalmind

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexglezfelix.metalmind.core.TagSuggestion

/** La fila de tags sobre el teclado mientras se escribe un `#tag`. */
@Composable
fun TagSuggestions(suggestions: List<TagSuggestion>, onPick: (TagSuggestion) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
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
                    label = { Text("#${suggestion.name}") },
                )
            }
        }
    }
}
