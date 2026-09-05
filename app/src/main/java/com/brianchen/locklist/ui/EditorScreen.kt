package com.brianchen.locklist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(repo: TaskRepository, tasks: List<Task>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Task?>(null) }
    var renameTitle by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Tasks", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("New task") }
            )
            Button(
                onClick = {
                    val title = draft.trim()
                    if (title.isEmpty()) return@Button
                    scope.launch { repo.add(title) }
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
                .padding(top = 12.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            scope.launch { repo.delete(task) }
                            true
                        } else {
                            false
                        }
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFC62828))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text("Delete", color = Color.White)
                        }
                    }
                ) {
                    TaskEditorRow(
                        task = task,
                        onRename = {
                            renaming = task
                            renameTitle = task.title
                        },
                        onToggleRecurring = {
                            scope.launch { repo.setRecurring(task, !task.recurring) }
                        },
                        onMoveUp = { scope.launch { repo.moveUp(task) } },
                        onMoveDown = { scope.launch { repo.moveDown(task) } },
                        onDelete = { scope.launch { repo.delete(task) } }
                    )
                }
            }
        }
    }

    val target = renaming
    if (target != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = renameTitle.trim()
                        if (title.isNotEmpty()) {
                            scope.launch { repo.rename(target, title) }
                        }
                        renaming = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TaskEditorRow(
    task: Task,
    onRename: () -> Unit,
    onToggleRecurring: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = task.title,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onRename)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.titleMedium
        )
        TextButton(onClick = onToggleRecurring) {
            Text(if (task.recurring) "Daily on" else "Daily")
        }
        TextButton(onClick = onMoveUp) { Text("Up") }
        TextButton(onClick = onMoveDown) { Text("Down") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
