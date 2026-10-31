package org.attache.tmux

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import timber.log.Timber

val TmuxWindowStripHeight = 44.dp

/** One chip per tmux window, drawn from what tmux last reported. A tap sends
 * `select-window` and changes nothing locally: the chip moves when tmux says
 * the active window moved. */
@Composable
fun TmuxWindowStrip(control: TmuxControl, modifier: Modifier = Modifier) {
    val windows by control.windows.collectAsState()
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().height(TmuxWindowStripHeight)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("tmuxWindowStrip"),
        ) {
            items(windows, key = { it.id }) { window ->
                FilterChip(
                    selected = window.active,
                    onClick = {
                        scope.launch {
                            try {
                                control.selectWindow(window.id)
                            } catch (e: TmuxCommandError) {
                                // tmux refused (the window closed under the
                                // tap); its next notification redraws the strip.
                                Timber.w("select-window failed: %s", e.message)
                            }
                        }
                    },
                    label = { Text("${window.index}: ${window.name}") },
                    modifier = Modifier.testTag("tmuxWindow-${window.id}"),
                )
            }
        }
    }
}
