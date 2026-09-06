package com.brianchen.locklist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
    val pagerState = rememberPagerState { TaskStatus.COLUMNS.size }
    val app = LocalContext.current.applicationContext as LockListApp
    val uriHandler = LocalUriHandler.current
    var editing by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Tasks", style = MaterialTheme.typography.headlineSmall)
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 0.dp
        ) {
            TaskStatus.COLUMNS.forEachIndexed { index, status ->
                val count = tasks.count { TaskStatus.normalize(it.status) == status }
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text("${TaskStatus.label(status)} ($count)") }
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            val status = TaskStatus.COLUMNS[page]
            val columnTasks = tasks.filter { TaskStatus.normalize(it.status) == status }
            BoardColumn(
                status = status,
                tasks = columnTasks,
                thumbs = app.thumbs,
                onAdd = { title -> scope.launch { repo.add(title, status) } },
                onToggle = { task -> scope.launch { repo.setDone(task, !task.done) } },
                onEdit = { task ->
                    editing = task
                    editTitle = task.title
                    editNotes = task.notes
                },
                onMove = { task, next -> scope.launch { repo.setStatus(task, next) } },
                onMoveUp = { task -> scope.launch { repo.moveUp(task) } },
                onMoveDown = { task -> scope.launch { repo.moveDown(task) } },
                onDelete = { task -> scope.launch { repo.delete(task) } },
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
                        minLines = 3,
                        label = { Text("Notes") }
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
private fun BoardColumn(
    status: String,
    tasks: List<Task>,
    thumbs: ThumbCache,
    onAdd: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    onMove: (Task, String) -> Unit,
    onMoveUp: (Task) -> Unit,
    onMoveDown: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenImage: (String) -> Unit
) {
    var draft by remember(status) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Add a task") }
            )
            TextButton(
                onClick = {
                    val title = draft.trim()
                    if (title.isEmpty()) return@TextButton
                    onAdd(title)
                    draft = ""
                }
            ) {
                Text("Add")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                EditorTaskRow(
                    task = task,
                    thumbs = thumbs,
                    onToggle = { onToggle(task) },
                    onEdit = { onEdit(task) },
                    onMove = { next -> onMove(task, next) },
                    onMoveUp = { onMoveUp(task) },
                    onMoveDown = { onMoveDown(task) },
                    onDelete = { onDelete(task) },
                    onOpenUrl = onOpenUrl,
                    onOpenImage = onOpenImage
                )
            }
        }
    }
}

@Composable
private fun EditorTaskRow(
    task: Task,
    thumbs: ThumbCache,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMove: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenImage: (String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, end = 4.dp)
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit)
            )
            if (task.notes.isNotBlank()) {
                TaskNoteText(
                    notes = task.notes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = MaterialTheme.colorScheme.primary,
                    onUrl = onOpenUrl,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (task.images.isNotEmpty()) {
                TaskThumbs(
                    paths = task.images,
                    thumbs = thumbs,
                    onOpen = onOpenImage,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        TextButton(onClick = { menu = true }) { Text("⋯") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            TaskStatus.COLUMNS.filter { it != TaskStatus.normalize(task.status) }.forEach { next ->
                DropdownMenuItem(
                    text = { Text("Move to ${TaskStatus.label(next)}") },
                    onClick = {
                        menu = false
                        onMove(next)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = {
                    menu = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Up") },
                onClick = {
                    menu = false
                    onMoveUp()
                }
            )
            DropdownMenuItem(
                text = { Text("Down") },
                onClick = {
                    menu = false
                    onMoveDown()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menu = false
                    onDelete()
                }
            )
        }
    }
}
