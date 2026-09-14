package com.brianchen.locklist.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskStatus
import kotlinx.coroutines.launch

/** Tabs for the four columns, with live counts. Pass onDark for the lock screen. */
@Composable
fun KanbanTabs(
    pagerState: PagerState,
    tasks: List<Task>,
    onDark: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val fg = if (onDark) Color.White else Color.Unspecified
    ScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = fg,
        edgePadding = 0.dp,
        indicator = { positions ->
            if (pagerState.currentPage < positions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[pagerState.currentPage]),
                    color = fg
                )
            }
        },
        divider = {}
    ) {
        TaskStatus.COLUMNS.forEachIndexed { index, status ->
            val count = tasks.count { TaskStatus.normalize(it.status) == status }
            if (onDark) {
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    selectedContentColor = Color.White,
                    unselectedContentColor = Color.White.copy(alpha = 0.6f),
                    text = { Text("${TaskStatus.label(status)} ($count)") }
                )
            } else {
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text("${TaskStatus.label(status)} ($count)") }
                )
            }
        }
    }
}

/** One swipeable page per column; each page is a lazy list of the caller's rows. */
@Composable
fun KanbanPager(
    tasks: List<Task>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit,
    row: @Composable (Task) -> Unit
) {
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        val status = TaskStatus.COLUMNS[page]
        val items = tasks.filter { TaskStatus.normalize(it.status) == status }
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) { empty() }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { task -> row(task) }
            }
        }
    }
}

/** Thin ring that fills with a check when done. */
@Composable
fun TickCircle(
    done: Boolean,
    color: Color,
    onClick: () -> Unit,
    size: Dp = 24.dp,
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

/** Small chevron shown when a card has more to reveal. */
@Composable
fun ExpandChevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = tint,
        modifier = modifier.size(20.dp)
    )
}

fun Task.hasDetails(): Boolean = notes.isNotBlank() || images.isNotEmpty()

/** Create-task dialog shared by lock screen and app. */
@Composable
fun AddTaskDialog(
    defaultStatus: String,
    showNotes: Boolean,
    onAdd: (title: String, notes: String, status: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(TaskStatus.normalize(defaultStatus)) }
    val focus = remember { FocusRequester() }
    val submit = {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) onAdd(trimmed, notes.trim(), status)
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
                        label = { Text("Description (optional)") }
                    )
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
