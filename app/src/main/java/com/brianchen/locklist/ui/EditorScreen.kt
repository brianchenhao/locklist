package com.brianchen.locklist.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskArea
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.sync.ThumbCache
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    repo: TaskRepository,
    tasks: List<Task>,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as LockListApp
    val uriHandler = LocalUriHandler.current
    val mode by settings.viewMode
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editArea by remember { mutableStateOf(TaskArea.PERSONAL) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModeChips(
                current = mode,
                onSelect = { settings.setViewMode(it) },
                onDark = false,
                modifier = Modifier.fillMaxWidth()
            )
            key(mode) {
                val pages = remember(mode) { mode.pages() }
                val pagerState = rememberPagerState { pages.size }
                if (pages.size > 1) {
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 0.dp
                    ) {
                        pages.indices.forEach { index ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(pageTitle(mode, index, tasks)) }
                            )
                        }
                    }
                }
                ModeBoard(
                    mode = mode,
                    tasks = tasks,
                    pagerState = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    header = { cell, count ->
                        Text(
                            text = "${cell.title} · $count",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    },
                    row = { task, compact ->
                        EditorTaskRow(
                            task = task,
                            compact = compact,
                            thumbs = app.thumbs,
                            onToggle = { scope.launch { repo.setDone(task, !task.done) } },
                            onEdit = {
                                editing = task
                                editTitle = task.title
                                editNotes = task.notes
                                editArea = TaskArea.normalize(task.area)
                            },
                            onMove = { next -> scope.launch { repo.setStatus(task, next) } },
                            onFlipArea = {
                                val next = if (TaskArea.normalize(task.area) == TaskArea.WORK) {
                                    TaskArea.PERSONAL
                                } else {
                                    TaskArea.WORK
                                }
                                scope.launch { repo.setArea(task, next) }
                            },
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
                    },
                    empty = { compact ->
                        Text(
                            text = if (compact) "—" else "Nothing here yet. Tap + to add.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
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
            defaultArea = mode.defaultArea(),
            defaultStatus = mode.defaultStatus(),
            showNotes = true,
            onAdd = { title, notes, area, status ->
                scope.launch { repo.add(title, status, notes, area) }
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
                        minLines = 3,
                        label = { Text("Notes") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskArea.ALL.forEach { option ->
                            FilterChip(
                                selected = editArea == option,
                                onClick = { editArea = option },
                                label = { Text(TaskArea.label(option)) }
                            )
                        }
                    }
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
                            scope.launch { repo.updateDetails(target, title, editNotes, editArea) }
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
private fun EditorTaskRow(
    task: Task,
    compact: Boolean,
    thumbs: ThumbCache,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMove: (String) -> Unit,
    onFlipArea: () -> Unit,
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
            .padding(vertical = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = if (compact) 12.dp else 10.dp, end = 4.dp)
        ) {
            Text(
                text = task.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit)
            )
            if (!compact && task.notes.isNotBlank()) {
                TaskNoteText(
                    notes = task.notes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = MaterialTheme.colorScheme.primary,
                    onUrl = onOpenUrl,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!compact && task.images.isNotEmpty()) {
                TaskThumbs(
                    paths = task.images,
                    thumbs = thumbs,
                    onOpen = onOpenImage,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        TextButton(onClick = { menu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text("⋯")
        }
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
                text = {
                    val other = if (TaskArea.normalize(task.area) == TaskArea.WORK) "Personal" else "Work"
                    Text("Mark as $other")
                },
                onClick = {
                    menu = false
                    onFlipArea()
                }
            )
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
