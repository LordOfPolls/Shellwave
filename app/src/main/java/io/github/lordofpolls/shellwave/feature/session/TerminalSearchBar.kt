package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only whether the bar is showing - the query, matches and current index live with the terminal that owns them. */
internal class TerminalSearchController {
    var open by mutableStateOf(false)
        private set

    fun show() {
        open = true
    }

    fun hide() {
        open = false
    }
}

/** [key] a fresh instance per session, e.g. the selected session id, so switching tabs doesn't leave the bar open over a session that was never searched. */
@Composable
internal fun rememberTerminalSearchController(key: Any?): TerminalSearchController =
    remember(key) { TerminalSearchController() }

/** "Search" in [TerminalOverflowMenu] shows this above the terminal. Up/down jump between matches by scrolling; highlighting the match itself is a later step. */
@Composable
internal fun TerminalSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchIndex: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search scrollback") },
        )
        Text(
            if (matchCount == 0) "0 of 0" else "${matchIndex + 1} of $matchCount",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrevious, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close search")
        }
    }
}
