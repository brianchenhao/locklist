package com.brianchen.locklist.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.sync.ThumbCache
import com.brianchen.locklist.ui.theme.LockListTheme
import kotlinx.coroutines.launch

class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LockListApp
        setContent {
            val themeMode by app.settings.themeMode
            val wallpaperRevision by app.settings.wallpaperRevision
            LockListTheme(themeMode = themeMode, dynamicColor = false) {
                val tasks by app.tasks.observeTasks().collectAsState(initial = emptyList())
                LockChecklistScreen(
                    tasks = tasks,
                    repo = app.tasks,
                    thumbs = app.thumbs,
                    settings = app.settings,
                    wallpaperRevision = wallpaperRevision,
                    onDismiss = { finish() },
                    onOpenAfterUnlock = { url ->
                        startActivity(OpenLinkActivity.intent(this, url))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun LockChecklistScreen(
    tasks: List<Task>,
    repo: TaskRepository,
    thumbs: ThumbCache,
    settings: AppSettings,
    wallpaperRevision: Long,
    onDismiss: () -> Unit,
    onOpenAfterUnlock: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val doneCount = tasks.count { it.done }
    val total = tasks.size
    val progress = if (total == 0) 0f else doneCount / total.toFloat()
    var drag by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val wallpaper = remember(wallpaperRevision) {
        loadWallpaperBitmap(settings, context.resources.displayMetrics.widthPixels)
    }
    val pagerState = rememberPagerState { TaskStatus.COLUMNS.size }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF24324A), Color(0xFF0B1020))
                )
            )
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, amount -> drag += amount },
                            onDragEnd = {
                                if (drag > 80f) onDismiss()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f }
                        )
                    }
            ) {
                Text(
                    text = "$doneCount of $total done",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    edgePadding = 0.dp,
                    indicator = { positions ->
                        if (pagerState.currentPage < positions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[pagerState.currentPage]),
                                color = Color.White
                            )
                        }
                    },
                    divider = {}
                ) {
                    TaskStatus.COLUMNS.forEachIndexed { index, status ->
                        val count = tasks.count { TaskStatus.normalize(it.status) == status }
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color.White.copy(alpha = 0.6f),
                            text = { Text("${TaskStatus.label(status)} ($count)") }
                        )
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 12.dp)
            ) { page ->
                val status = TaskStatus.COLUMNS[page]
                val columnTasks = tasks.filter { TaskStatus.normalize(it.status) == status }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(columnTasks, key = { it.id }) { task ->
                        LockTaskRow(
                            task = task,
                            thumbs = thumbs,
                            onToggle = { scope.launch { repo.setDone(task, !task.done) } },
                            onOpenAfterUnlock = onOpenAfterUnlock
                        )
                    }
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun LockTaskRow(
    task: Task,
    thumbs: ThumbCache,
    onToggle: () -> Unit,
    onOpenAfterUnlock: (String) -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (task.done) 1.06f else 1f,
        animationSpec = spring(),
        label = "tickScale"
    )
    val textColor by animateColorAsState(
        targetValue = if (task.done) Color.White.copy(alpha = 0.55f) else Color.White,
        label = "tickColor"
    )
    val note = firstNoteLine(task.notes)
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = { onToggle() }
        )
        Column(modifier = Modifier.padding(start = 8.dp, top = 10.dp)) {
            Text(
                text = task.title,
                color = textColor,
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                modifier = Modifier.clickable(onClick = onToggle)
            )
            if (note.isNotEmpty()) {
                TaskNoteText(
                    notes = note,
                    color = Color.White.copy(alpha = 0.7f),
                    linkColor = Color(0xFF90CAF9),
                    onUrl = onOpenAfterUnlock,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (task.images.isNotEmpty()) {
                TaskThumbs(
                    paths = task.images,
                    thumbs = thumbs,
                    onOpen = { path ->
                        scope.launch {
                            val url = thumbs.signedUrl(path) ?: return@launch
                            onOpenAfterUnlock(url)
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun loadWallpaperBitmap(settings: AppSettings, targetWidth: Int): Bitmap? {
    val file = settings.wallpaperFile()
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = if (bounds.outWidth > 0 && targetWidth > 0) {
            (bounds.outWidth / targetWidth).coerceAtLeast(1)
        } else {
            2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        Log.e("LockList", "wallpaper decode failed", e)
        null
    }
}
