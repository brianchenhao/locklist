package com.brianchen.locklist.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(repo: TaskRepository, tasks: List<Task>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as LockListApp
    val uriHandler = LocalUriHandler.current
    val pagerState = rememberPagerState { TaskStatus.COLUMNS.size }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var attachTarget by remember { mutableStateOf<Task?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val task = attachTarget
        attachTarget = null
        if (uri == null || task == null) return@rememberLauncherForActivityResult
        status = "Uploading photo…"
        scope.launch {
            try {
                val path = app.images.attach(task, uri)
                repo.addImage(task, path)
                expanded[task.id] = true
                status = null
            } catch (e: Exception) {
                status = "Upload failed: ${e.message ?: "unknown error"}"
            }
        }
    }

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
                        editing = task
                        editTitle = task.title
                        editNotes = task.notes
                    },
                    onAttach = {
                        attachTarget = task
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemoveImage = { path ->
                        scope.launch {
                            repo.removeImage(task, path)
                            app.images.remove(path)
                        }
                    },
                    onMove = { next -> scope.launch { repo.setStatus(task, next) } },
                    onMoveUp = { scope.launch { repo.moveUp(task) } },
                    onMoveDown = { scope.launch { repo.moveDown(task) } },
                    onDelete = { scope.launch { repo.delete(task) } },
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
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
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

    val target = editing
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
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
                    TextButton(
                        onClick = {
                            scope.launch { repo.setRecurring(target, !target.recurring) }
                            editing = target.copy(recurring = !target.recurring)
                        }
                    ) {
                        Text(if (target.recurring) "Daily on" else "Daily")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = editTitle.trim()
                        if (title.isNotEmpty()) {
                            scope.launch { repo.updateDetails(target, title, editNotes) }
                        }
                        editing = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
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
