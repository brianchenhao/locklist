package com.brianchen.locklist.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.sync.ThumbCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(repo: TaskRepository, tasks: List<Task>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as LockListApp
    val uriHandler = LocalUriHandler.current
    val pagerState = rememberPagerState { TaskStatus.COLUMNS.size }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    // Ids and text survive rotation and dark-mode switches; the task itself is looked up live.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editNotes by rememberSaveable { mutableStateOf("") }
    var editRecurring by rememberSaveable { mutableStateOf(false) }
    // What the fields showed when the dialog opened. Save sends only what the user changed
    // from these, so a web edit that lands while the dialog is open is never written back over.
    var openedTitle by rememberSaveable { mutableStateOf("") }
    var openedNotes by rememberSaveable { mutableStateOf("") }
    var openedRecurring by rememberSaveable { mutableStateOf(false) }
    var attachTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val taskId = attachTargetId
        attachTargetId = null
        if (uri == null || taskId == null) return@rememberLauncherForActivityResult
        status = "Uploading photo…"
        scope.launch {
            try {
                // Read from Room, not the composed list: right after a recreate it can still be empty.
                val task = repo.observeTasks().first().firstOrNull { it.id == taskId }
                if (task == null) {
                    status = "That task is gone, photo not added"
                    return@launch
                }
                val path = app.images.attach(task, uri)
                repo.addImage(task, path)
                expanded[task.id] = true
                status = null
            } catch (e: Exception) {
                status = "Upload failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    val fabLift by animateDpAsState(
        targetValue = if (snackbar.currentSnackbarData != null) 64.dp else 0.dp,
        label = "fabLift"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            KanbanTabs(pagerState = pagerState, tasks = tasks, onDark = false)
            val message = status
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            KanbanPager(
                tasks = tasks,
                pagerState = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                // Room under the last card so the FAB never covers its chevron or buttons.
                contentPadding = PaddingValues(bottom = 88.dp),
                empty = {
                    Text(
                        text = "Nothing here yet. Tap + to add.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            ) { task ->
                EditorTaskCard(
                    task = task,
                    thumbs = app.thumbs,
                    expanded = expanded[task.id] == true,
                    onToggleExpanded = { expanded[task.id] = expanded[task.id] != true },
                    onToggleDone = { scope.launch { repo.setDone(task, !task.done) } },
                    onEdit = {
                        editingId = task.id
                        editTitle = task.title
                        editNotes = task.notes
                        editRecurring = task.recurring
                        openedTitle = task.title
                        openedNotes = task.notes
                        openedRecurring = task.recurring
                    },
                    onAttach = {
                        attachTargetId = task.id
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemoveImage = { path ->
                        removingId = task.id
                        removingPath = path
                    },
                    onMove = { next -> scope.launch { repo.setStatus(task, next) } },
                    onMoveUp = { scope.launch { repo.moveUp(task) } },
                    onMoveDown = { scope.launch { repo.moveDown(task) } },
                    onDelete = {
                        val snapshot = task
                        snackbar.currentSnackbarData?.dismiss()
                        scope.launch {
                            // One coroutine, so an Undo can never run before the delete it undoes.
                            repo.delete(snapshot)
                            val result = snackbar.showSnackbar(
                                message = "Task deleted",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) repo.undo(snapshot)
                        }
                    },
                    onOpenUrl = { url -> uriHandler.openUri(url) },
                    onOpenImage = { path ->
                        scope.launch {
                            val url = app.thumbs.signedUrl(path) ?: return@launch
                            uriHandler.openUri(url)
                        }
                    }
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        )
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = fabLift)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New task")
        }
    }

    if (showAdd) {
        AddTaskDialog(
            defaultStatus = TaskStatus.COLUMNS[pagerState.currentPage],
            showNotes = true,
            onAdd = { title, notes, taskStatus ->
                scope.launch { repo.add(title, taskStatus, notes) }
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    val target = editingId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("Edit task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Title") }
                    )
                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        label = { Text("Description") }
                    )
                    // Applied only on Save; Cancel leaves the task as it was.
                    FilterChip(
                        selected = editRecurring,
                        onClick = { editRecurring = !editRecurring },
                        label = { Text("Daily") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val titleEdited = editTitle.trim() != openedTitle.trim()
                        val notesEdited = editNotes.trim() != openedNotes.trim()
                        val recurringEdited = editRecurring != openedRecurring
                        // A field the user left alone passes the live value, which the
                        // repository reads as "not edited".
                        val title = if (titleEdited) editTitle.trim() else target.title
                        val notes = if (notesEdited) editNotes.trim() else target.notes
                        val recurring = if (recurringEdited) editRecurring else target.recurring
                        val changed = titleEdited || notesEdited || recurringEdited
                        if (title.isNotBlank() && changed) {
                            scope.launch {
                                repo.updateDetails(
                                    task = target,
                                    title = title,
                                    notes = notes,
                                    area = target.area,
                                    recurring = recurring
                                )
                            }
                        }
                        editingId = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text("Cancel") }
            }
        )
    }

    val removeId = removingId
    val removePath = removingPath
    if (removeId != null && removePath != null) {
        val closeRemove = {
            removingId = null
            removingPath = null
        }
        AlertDialog(
            onDismissRequest = closeRemove,
            title = { Text("Remove photo?") },
            text = { Text("It is removed from this task on the web too.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeRemove()
                        val task = tasks.firstOrNull { it.id == removeId }
                        if (task != null) {
                            scope.launch {
                                repo.removeImage(task, removePath)
                                // The storage file is deleted only after the row change reaches the server.
                                app.images.removeLater(task.id, removePath)
                            }
                        }
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = closeRemove) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EditorTaskCard(
    task: Task,
    thumbs: ThumbCache,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onMove: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenImage: (String) -> Unit
) {
    var moveMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(end = 8.dp, bottom = if (expanded) 8.dp else 0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = task.done, onCheckedChange = { onToggleDone() })
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpanded)
                        .padding(vertical = 12.dp)
                )
                ExpandChevron(
                    expanded = expanded,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(onClick = onToggleExpanded)
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    if (task.notes.isNotBlank()) {
                        TaskNoteText(
                            notes = task.notes,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            linkColor = MaterialTheme.colorScheme.primary,
                            onUrl = onOpenUrl
                        )
                    } else {
                        Text(
                            text = "No description",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (task.images.isNotEmpty()) {
                        TaskThumbs(
                            paths = task.images,
                            thumbs = thumbs,
                            onOpen = onOpenImage,
                            size = 96.dp,
                            onRemove = onRemoveImage,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(onClick = onAttach) { Text("Photo") }
                        Box {
                            TextButton(onClick = { moveMenu = true }) { Text("Move") }
                            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                                TaskStatus.COLUMNS
                                    .filter { it != TaskStatus.normalize(task.status) }
                                    .forEach { next ->
                                        DropdownMenuItem(
                                            text = { Text("To ${TaskStatus.label(next)}") },
                                            onClick = {
                                                moveMenu = false
                                                onMove(next)
                                            }
                                        )
                                    }
                                DropdownMenuItem(
                                    text = { Text("Up") },
                                    onClick = {
                                        moveMenu = false
                                        onMoveUp()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Down") },
                                    onClick = {
                                        moveMenu = false
                                        onMoveDown()
                                    }
                                )
                            }
                        }
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
