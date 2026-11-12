package org.attache.tmux

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.attache.chat.AttacheCli
import org.attache.chat.Project
import org.attache.chat.ProjectDraft

/**
 * A window this component just made, already switched to on the terminal:
 * what the place it was made from needs in order to go there too. [pane] is
 * set when the window runs a command, which may turn out to be an agent.
 */
data class MadeWindow(val window: String?, val pane: String?)

/** The form: a new project from [window], or the saved one with [id]. */
private data class ProjectEdit(val id: String?, val draft: ProjectDraft, val window: TmuxWindow?)

/**
 * The colours the list is drawn in. A dialog and the sheet are Material
 * surfaces; a place with a palette of its own (the terminal's glass) passes
 * that instead, since `MaterialTheme` there would put dark text on dark.
 */
data class ProjectsColors(val ink: Color, val muted: Color, val accent: Color, val error: Color, val line: Color)

@Composable
fun materialProjectsColors() = ProjectsColors(
    ink = MaterialTheme.colorScheme.onSurface,
    muted = MaterialTheme.colorScheme.onSurfaceVariant,
    accent = MaterialTheme.colorScheme.primary,
    error = MaterialTheme.colorScheme.error,
    line = MaterialTheme.colorScheme.outlineVariant,
)

/** The whole thing in a dialog, for the places with no drawer to put it in. */
@Composable
fun ProjectsDialog(control: TmuxControl, onDismiss: () -> Unit, onOpened: (MadeWindow) -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projects") },
        text = {
            ProjectsPanel(
                control,
                onOpened = {
                    onDismiss()
                    onOpened(it)
                },
                listMaxHeight = 420.dp,
            )
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * What the "+" at the top of either left drawer brings up: a sheet from the
 * bottom of the screen, as wide as the screen so a folder is read whole.
 * An empty window first, then the saved projects, each of which is a new
 * window too. Nothing is read from the host until the sheet is asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewWindowSheet(
    control: TmuxControl,
    onDismiss: () -> Unit,
    onMade: (MadeWindow) -> Unit,
) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var making by remember { mutableStateOf(false) }
    // An empty window, and the terminal put on it: a window that was made
    // and then has to be looked for is the thing this sheet is here to end.
    fun emptyWindow() {
        if (making) return
        making = true
        scope.launch {
            try {
                val window = control.newWindowId()
                if (window != null) control.goTo(window)
                onMade(MadeWindow(window, null))
            } catch (e: TmuxCommandError) {
                Toast.makeText(context, "No window made: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                making = false
            }
        }
    }
    val session = state.sessions.firstOrNull { it.id == state.sessionId }?.name
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .testTag("newWindowSheet"),
        ) {
            Text("New window", style = MaterialTheme.typography.titleLarge)
            if (session != null) {
                Text("in session $session", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = state.sessionId != null && !making) { emptyWindow() }
                    .padding(vertical = 12.dp)
                    .testTag("newEmptyWindow"),
            ) {
                Text(
                    "\$_",
                    color = muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Empty window", fontWeight = FontWeight.SemiBold)
                    Text("A plain shell, no project", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            HorizontalDivider()
            ProjectsPanel(control, onOpened = onMade, listMaxHeight = 360.dp, inSheet = true)
        }
    }
}

/**
 * The host's saved projects (attache-cli keeps them, so the desktop shows
 * the same list): a tap makes a new window in the folder and runs the
 * command there; a window can be saved as one, and closed in the same step,
 * which is the point of saving it. Nothing is kept on the phone.
 *
 * One component for every place the list appears, with its own forms: where
 * it is put decides only its colours, its height and what opening one leads
 * to. Every action waits for the host's answer before moving on, so a
 * refusal is shown where it happened and a window is only ever closed after
 * its project is known to be saved.
 */
@Composable
fun ProjectsPanel(
    control: TmuxControl,
    onOpened: (MadeWindow) -> Unit,
    listMaxHeight: Dp,
    modifier: Modifier = Modifier,
    colors: ProjectsColors = materialProjectsColors(),
    inSheet: Boolean = false,
) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<Project>?>(null) }
    var commands by remember { mutableStateOf<List<String>>(emptyList()) }
    var unreachable by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProjectEdit?>(null) }
    var removing by remember { mutableStateOf<Project?>(null) }
    var pickingWindow by remember { mutableStateOf(false) }

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    LaunchedEffect(reload, control) {
        val got = AttacheCli.projects(control)
        unreachable = got == null
        if (got != null) {
            list = got.projects
            commands = got.commands
        }
    }

    fun open(project: Project) {
        val session = state.sessionId ?: return say("No tmux session to open it in")
        if (busy) return
        busy = true
        scope.launch {
            val made = AttacheCli.openProject(control, project.id, session)
            if (made.why == null && made.window != null) control.goTo(made.window)
            busy = false
            if (made.why != null) return@launch say("Not opened: ${made.why}")
            // A plain shell never becomes an agent, so nobody waits for one.
            onOpened(MadeWindow(made.window, made.pane.takeIf { project.command.isNotBlank() }))
        }
    }

    fun startSaving(window: TmuxWindow) {
        if (busy) return
        busy = true
        scope.launch {
            val draft = AttacheCli.draftProject(control, window.id)
            busy = false
            pickingWindow = false
            // Without a draft the form still opens: the name is known here,
            // and the folder can be typed.
            editing = ProjectEdit(null, draft ?: ProjectDraft(window.name, "", ""), window)
        }
    }

    editing?.let { edit ->
        ProjectEditor(
            edit = edit,
            commands = commands,
            busy = busy,
            onDismiss = { editing = null },
            onSave = { draft, closeWindow ->
                busy = true
                scope.launch {
                    val why = AttacheCli.saveProject(control, edit.id, draft)
                    if (why != null) {
                        busy = false
                        say("Not saved: $why")
                        return@launch
                    }
                    if (closeWindow && edit.window != null) {
                        try {
                            control.query("kill-window -t ${edit.window.id}")
                        } catch (e: TmuxCommandError) {
                            say("Saved, but the window was not closed: ${e.message}")
                        }
                    }
                    busy = false
                    editing = null
                    reload++
                }
            },
        )
    }

    removing?.let { remove ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${remove.name}”?") },
            text = { Text("Only the saved entry goes. The folder, and any window opened from it, stay.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val why = AttacheCli.removeProject(control, remove.id)
                            busy = false
                            removing = null
                            if (why != null) say("Not removed: $why")
                            reload++
                        }
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (pickingWindow) {
        AlertDialog(
            onDismissRequest = { pickingWindow = false },
            title = { Text("Save which window?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.windows, key = { it.id }) { window ->
                        Text(
                            "${window.index}: ${window.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (window.active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { startSaving(window) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingWindow = false }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxWidth()) {
        val shown = list
        // In the sheet the list has a heading of its own, which also says
        // how a project is edited; elsewhere a line under the list does.
        if (inSheet) {
            Text(
                if (shown.isNullOrEmpty()) "PROJECTS" else "PROJECTS · HOLD TO EDIT",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            shown == null && unreachable -> Text("The host did not answer.", color = colors.muted)

            shown == null -> Text("Reading…", color = colors.muted)

            shown.isEmpty() -> Text(
                "None saved yet. Save a window as a project, close it, and open it again from here: " +
                    "a new window in its folder, running its command.",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )

            else -> LazyColumn(Modifier.heightIn(max = listMaxHeight).testTag("projectList")) {
                items(shown, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        colors = colors,
                        enabled = !busy,
                        onOpen = { open(project) },
                        onEdit = {
                            editing = ProjectEdit(project.id, ProjectDraft(project.name, project.path, project.command), null)
                        },
                        onRemove = { removing = project },
                    )
                }
            }
        }
        val canSave = state.windows.isNotEmpty() && !busy
        if (inSheet) {
            TextButton(
                enabled = canSave,
                onClick = { pickingWindow = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            ) {
                Text("Save a window as project…", color = colors.accent)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (shown.isNullOrEmpty()) "" else "Tap to open · hold to edit",
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = canSave, onClick = { pickingWindow = true }) {
                    Text("Save a window…", color = colors.accent)
                }
            }
        }
    }
}

/** Puts the terminal on [window]. tmux's refusal is not the caller's: the
 * window is made either way, and it is only one tap further off. */
private suspend fun TmuxControl.goTo(window: String) {
    try {
        switchTo(window)
    } catch (_: TmuxCommandError) {
    }
}

/**
 * One project, the whole width: its name with its command beside it, and
 * under them its folder in full, wrapped onto as many lines as it takes and
 * never cut, since the folder is what tells two projects of one name apart.
 * A tap opens it; a long press brings up Edit and Remove, which are rare.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    project: Project,
    colors: ProjectsColors,
    enabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = enabled,
                    // A folder that is gone cannot be opened; the tap then
                    // offers what can be done, which is to edit or remove.
                    onClick = { if (project.exists) onOpen() else menu = true },
                    onLongClick = { menu = true },
                )
                .padding(vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    color = colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    project.command.ifEmpty { "shell" },
                    color = colors.accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .widthIn(max = 150.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                if (project.exists) project.path else "${project.path} (folder missing)",
                color = if (project.exists) colors.muted else colors.error,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Edit…") },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove…", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun ProjectEditor(
    edit: ProjectEdit,
    commands: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProjectDraft, closeWindow: Boolean) -> Unit,
) {
    var name by remember(edit) { mutableStateOf(edit.draft.name) }
    var path by remember(edit) { mutableStateOf(edit.draft.path) }
    var command by remember(edit) { mutableStateOf(edit.draft.command) }
    val ready = !busy && name.isNotBlank() && path.isNotBlank()
    fun draft() = ProjectDraft(name.trim(), path.trim(), command.trim())

    // A command is code: no capitals put in, no words "corrected".
    val code = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.id == null) "Save as project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text("Folder") },
                    placeholder = { Text("~/Code/project") },
                    keyboardOptions = code,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    singleLine = true,
                    label = { Text("Command") },
                    placeholder = { Text("claude") },
                    supportingText = { Text("Runs in your shell, so an alias works. Empty for a plain shell.") },
                    keyboardOptions = code,
                    modifier = Modifier.fillMaxWidth().testTag("projectCommand"),
                )
                // The person's often-used commands (kept on the host, edited
                // on the desktop): a tap fills the field, so an alias is
                // picked rather than typed on a phone keyboard.
                if (commands.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        commands.forEach { suggestion ->
                            FilterChip(
                                selected = command.trim() == suggestion,
                                onClick = { command = suggestion },
                                label = { Text(suggestion, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
                if (edit.window != null) {
                    TextButton(enabled = ready, onClick = { onSave(draft(), true) }) {
                        Text(
                            "Save and close window ${edit.window.index}: ${edit.window.name}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Closing ends every process in the window, including an agent mid-run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onSave(draft(), false) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
