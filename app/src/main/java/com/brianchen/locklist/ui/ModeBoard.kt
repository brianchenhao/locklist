package com.brianchen.locklist.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.data.BoardCell
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskArea
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.data.ViewMode

/**
 * Draws the pages of a [ViewMode]. A page holding four cells becomes a 2x2 grid of compact
 * lists; any other page is one full-height list. Row and header rendering belong to the
 * caller so the lock screen and the app keep their own looks.
 */
@Composable
fun ModeBoard(
    mode: ViewMode,
    tasks: List<Task>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    /** Only called for grid cells; a full-page list is already named by its tab or chip. */
    header: @Composable (cell: BoardCell, count: Int) -> Unit,
    row: @Composable (task: Task, compact: Boolean) -> Unit,
    empty: @Composable (compact: Boolean) -> Unit
) {
    val pages = remember(mode) { mode.pages() }
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        val cells = pages[page]
        if (cells.size == 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    CellList(cells[0], tasks, true, header, row, empty, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    CellList(cells[1], tasks, true, header, row, empty, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.weight(1f)) {
                    CellList(cells[2], tasks, true, header, row, empty, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    CellList(cells[3], tasks, true, header, row, empty, Modifier.weight(1f))
                }
            }
        } else {
            CellList(cells.first(), tasks, false, header, row, empty, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CellList(
    cell: BoardCell,
    tasks: List<Task>,
    compact: Boolean,
    header: @Composable (cell: BoardCell, count: Int) -> Unit,
    row: @Composable (task: Task, compact: Boolean) -> Unit,
    empty: @Composable (compact: Boolean) -> Unit,
    modifier: Modifier
) {
    val items = tasks.filter(cell::matches)
    Column(modifier = modifier) {
        if (compact) header(cell, items.size)
        if (items.isEmpty()) empty(compact)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(items, key = { it.id }) { task -> row(task, compact) }
        }
    }
}

/** Horizontal row of the five modes; the selected one is filled. */
@Composable
fun ModeChips(
    current: ViewMode,
    onSelect: (ViewMode) -> Unit,
    onDark: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ViewMode.entries.forEach { mode ->
            val selected = mode == current
            FilterChip(
                selected = selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
                colors = if (onDark) {
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        labelColor = Color.White,
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color(0xFF0B1020)
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
                border = if (onDark) {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = Color.White.copy(alpha = 0.25f),
                        selectedBorderColor = Color.Transparent
                    )
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = selected)
                }
            )
        }
    }
}

/** Thin ring that fills with a check when done; the look from the board screenshot. */
@Composable
fun TickCircle(
    done: Boolean,
    color: Color,
    onClick: () -> Unit,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Done",
                tint = color,
                modifier = Modifier.size(size * 0.7f)
            )
        }
    }
}

/** Create-task dialog shared by lock screen and app. Defaults come from the current mode. */
@Composable
fun AddTaskDialog(
    defaultArea: String,
    defaultStatus: String,
    showNotes: Boolean,
    onAdd: (title: String, notes: String, area: String, status: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var area by remember { mutableStateOf(TaskArea.normalize(defaultArea)) }
    var status by remember { mutableStateOf(TaskStatus.normalize(defaultStatus)) }
    val focus = remember { FocusRequester() }
    val submit = {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) onAdd(trimmed, notes.trim(), area, status)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    singleLine = true,
                    label = { Text("Title") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                if (showNotes) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Notes (optional)") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskArea.ALL.forEach { option ->
                        FilterChip(
                            selected = area == option,
                            onClick = { area = option },
                            label = { Text(TaskArea.label(option)) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TaskStatus.COLUMNS.forEach { option ->
                        FilterChip(
                            selected = status == option,
                            onClick = { status = option },
                            label = { Text(TaskStatus.label(option)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Tab labels for a mode's pages, with live counts where a page is a single list. */
fun pageTitle(mode: ViewMode, page: Int, tasks: List<Task>): String {
    val label = mode.pageLabels()[page]
    val cells = mode.pages()[page]
    if (cells.size != 1) return label
    val count = tasks.count(cells.first()::matches)
    return "$label ($count)"
}
