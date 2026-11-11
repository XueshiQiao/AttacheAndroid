package org.attache.tmux

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.attache.chat.AttacheCli
import org.attache.chat.Project
import org.attache.chat.ProjectDraft

/** The form: a new project from [window], or the saved one with [id]. */
private data class ProjectEdit(val id: String?, val draft: ProjectDraft, val window: TmuxWindow?)

/**
 * The host's saved projects (attache-cli keeps them, so the desktop shows
 * the same list): a tap makes a new window in the folder and runs the
 * command there; a window can be saved as one, and closed in the same step,
 * which is the point of saving it. Nothing is kept on the phone.
 *
 * Every action waits for the host's answer before the dialog moves on, so a
 * refusal is shown where it happened and a window is only ever closed after
 * its project is known to be saved.
 */
@Composable
fun ProjectsDialog(control: TmuxControl, onDismiss: () -> Unit) {
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

    LaunchedEffect(reload) {
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
            val why = AttacheCli.openProject(control, project.id, session)
            busy = false
            if (why == null) onDismiss() else say("Not opened: $why")
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

    val edit = editing
    val remove = removing
    when {
        edit != null -> ProjectEditor(
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

        remove != null -> AlertDialog(
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

        pickingWindow -> AlertDialog(
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

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Projects") },
            text = {
                val shown = list
                when {
                    shown == null && unreachable -> Text("The host did not answer.")

                    shown == null -> Text("Reading…")

                    shown.isEmpty() -> Text(
                        "None saved yet. Save a window as a project, close it, and open it again from here: " +
                            "a new window in its folder, running its command.",
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 420.dp).testTag("projectList")) {
                        items(shown, key = { it.id }) { project ->
                            ProjectRow(
                                project = project,
                                enabled = !busy,
                                onOpen = { open(project) },
                                onEdit = {
                                    editing = ProjectEdit(
                                        project.id,
                                        ProjectDraft(project.name, project.path, project.command),
                                        null,
                                    )
                                },
                                onRemove = { removing = project },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = state.windows.isNotEmpty() && !busy, onClick = { pickingWindow = true }) {
                    Text("Save a window…")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    enabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                // A folder that is gone cannot be opened, but the entry can
                // still be edited to point somewhere real.
                .clickable(enabled = enabled && project.exists, onClick = onOpen)
                .padding(vertical = 10.dp),
        ) {
            Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (project.exists) project.path else "${project.path} (folder missing)",
                style = MaterialTheme.typography.bodySmall,
                color = if (project.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            if (project.command.isNotEmpty()) {
                Text(
                    project.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(enabled = enabled, onClick = onEdit) { Text("Edit") }
        TextButton(enabled = enabled, onClick = onRemove) { Text("Remove") }
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
