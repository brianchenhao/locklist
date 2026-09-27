package com.brianchen.locklist.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.sync.ThumbCache
import com.brianchen.locklist.ui.theme.LockListTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lock-screen writes outlive the activity, so a tick followed straight by Dismiss is kept. */
private val lockWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class LockActivity : ComponentActivity() {
    /** Bumped on every screen-on so the screen can reset to what matters now. */
    private val screenOns = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        // The lock background is always dark, so the bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as LockListApp
        val taskFlow = app.tasks.observeTasks()
        setContent {
            val themeMode by app.settings.themeMode
            val wallpaperRevision by app.settings.wallpaperRevision
            val screenOn by screenOns
            LockListTheme(themeMode = themeMode, dynamicColor = false) {
                val tasks: List<Task>? by taskFlow.collectAsState(initial = null)
                val health by app.sync.health.collectAsState()
                val now = rememberNow(60_000L)
                LockChecklistScreen(
                    tasks = tasks,
                    repo = app.tasks,
                    thumbs = app.thumbs,
                    settings = app.settings,
                    wallpaperRevision = wallpaperRevision,
                    screenOn = screenOn,
                    syncWarning = syncNeedsAttention(
                        lastOkAt = health.lastOkAt,
                        lastError = health.lastError,
                        pending = health.pending,
                        now = now,
                        offline = health.offline
                    ),
                    onDismiss = { finish() },
                    onOpenAfterUnlock = { url ->
                        startActivity(OpenLinkActivity.intent(this, url))
                        finish()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as LockListApp
        // This single instance is usually reused, so screen-on work lives here, not in
        // onCreate: pull what the portal changed while the socket was down, make sure the
        // live loop is alive, and catch up a daily reset the phone slept through.
        app.sync.startRealtime()
        app.sync.requestQuickSync("screen on")
        app.catchUpDailyReset()
        // The one reset per screen-on. onNewIntent must not bump it too: the service's delayed
        // launch lands a moment later and would wipe an Undo row or page the user just set.
        screenOns.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

/** One change the lock screen can take back: the task as it was, plus what to say about it. */
private class UndoAction(val snapshot: Task, val message: String)

@Composable
private fun LockChecklistScreen(
    tasks: List<Task>?,
    repo: TaskRepository,
    thumbs: ThumbCache,
    settings: AppSettings,
    wallpaperRevision: Long,
    screenOn: Int,
    syncWarning: Boolean,
    onDismiss: () -> Unit,
    onOpenAfterUnlock: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val loaded = tasks != null
    val list = tasks.orEmpty()
    var drag by remember { mutableFloatStateOf(0f) }
    var showAdd by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<UndoAction?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val pagerState = rememberPagerState { TaskStatus.COLUMNS.size }
    val context = LocalContext.current
    val wallpaper = rememberWallpaper(
        settings = settings,
        revision = wallpaperRevision,
        targetWidth = context.resources.displayMetrics.widthPixels
    )

    // Each screen-on starts fresh on the column that matters now, with rows collapsed.
    LaunchedEffect(screenOn, loaded) {
        if (!loaded) return@LaunchedEffect
        expanded.clear()
        undo = null
        pagerState.scrollToPage(glancePage(list))
    }

    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_MS)
            undo = null
        }
    }

    val moveTask: (Task, String) -> Unit = { task, next ->
        undo = UndoAction(task, "Moved to ${TaskStatus.label(next)}")
        lockWriteScope.launch { repo.setStatus(task, next) }
    }
    val toggleDone: (Task) -> Unit = { task ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val next = if (task.done) TaskStatus.MORE else TaskStatus.DONE
        undo = UndoAction(task, "Moved to ${TaskStatus.label(next)}")
        lockWriteScope.launch { repo.setDone(task, !task.done) }
    }

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
                .padding(horizontal = 20.dp, vertical = 44.dp)
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
                // The warning shares the headline's line, so it never pushes the tabs and list
                // down when it appears a moment after screen-on.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (loaded) glanceHeadline(list) else "",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (syncWarning) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LockAmber)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "not synced",
                            color = LockAmber,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                KanbanTabs(pagerState = pagerState, tasks = list, onDark = true)
            }
            KanbanPager(
                tasks = list,
                pagerState = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                empty = {
                    if (loaded) {
                        Text(
                            text = "Nothing here",
                            color = Color.White.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            ) { task ->
                LockTaskRow(
                    task = task,
                    thumbs = thumbs,
                    expanded = expanded[task.id] == true,
                    onToggleExpanded = { expanded[task.id] = expanded[task.id] != true },
                    onToggleDone = { toggleDone(task) },
                    onMove = { next -> moveTask(task, next) },
                    onOpenAfterUnlock = onOpenAfterUnlock
                )
            }
            val shownUndo = undo
            if (shownUndo != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shownUndo.message,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            undo = null
                            lockWriteScope.launch { repo.undo(shownUndo.snapshot) }
                        }
                    ) {
                        Text("Undo", color = Color(0xFF90CAF9))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = { showAdd = true }) {
                    Text("+ Add", color = Color.White)
                }
                Button(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            defaultStatus = TaskStatus.COLUMNS[pagerState.currentPage],
            showNotes = false,
            onAdd = { title, notes, status ->
                lockWriteScope.launch { repo.add(title, status, notes) }
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LockTaskRow(
    task: Task,
    thumbs: ThumbCache,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleDone: () -> Unit,
    onMove: (String) -> Unit,
    onOpenAfterUnlock: (String) -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (task.done) Color.White.copy(alpha = 0.55f) else Color.White,
        label = "tickColor"
    )
    val scope = rememberCoroutineScope()
    var moveMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        TickCircle(
            done = task.done,
            color = textColor,
            onClick = onToggleDone,
            modifier = Modifier.padding(top = 3.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .combinedClickable(
                    onClick = onToggleExpanded,
                    onLongClickLabel = "Move to another column",
                    // combinedClickable gives the long-press haptic itself.
                    onLongClick = { moveMenu = true }
                )
        ) {
            Text(
                text = task.title,
                color = textColor,
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded && task.notes.isNotBlank()) {
                TaskNoteText(
                    notes = task.notes,
                    color = Color.White.copy(alpha = 0.75f),
                    linkColor = Color(0xFF90CAF9),
                    onUrl = onOpenAfterUnlock,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (expanded && task.images.isNotEmpty()) {
                TaskThumbs(
                    paths = task.images,
                    thumbs = thumbs,
                    size = 96.dp,
                    onOpen = { path ->
                        scope.launch {
                            val url = thumbs.signedUrl(path) ?: return@launch
                            onOpenAfterUnlock(url)
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
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
            }
        }
        if (task.hasDetails()) {
            ExpandChevron(
                expanded = expanded,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(top = 4.dp, start = 8.dp)
                    .clickable(onClick = onToggleExpanded)
            )
        }
    }
}

private const val UNDO_MS = 4_000L

/** "3 in progress · 1 to review": what matters now, not the size of the backlog. */
private fun glanceHeadline(tasks: List<Task>): String {
    val ongoing = tasks.count { TaskStatus.normalize(it.status) == TaskStatus.ONGOING }
    val review = tasks.count { TaskStatus.normalize(it.status) == TaskStatus.REVIEW }
    val parts = buildList {
        if (ongoing > 0) add("$ongoing in progress")
        if (review > 0) add("$review to review")
    }
    return if (parts.isEmpty()) "Nothing in progress" else parts.joinToString(" · ")
}

/** First non-empty column of Progressing, Review, All task; Progressing when all are empty. */
private fun glancePage(tasks: List<Task>): Int {
    val status = listOf(TaskStatus.ONGOING, TaskStatus.REVIEW, TaskStatus.MORE)
        .firstOrNull { column -> tasks.any { TaskStatus.normalize(it.status) == column } }
        ?: TaskStatus.ONGOING
    return TaskStatus.COLUMNS.indexOf(status).coerceAtLeast(0)
}

/** The decoded wallpaper, kept for the life of the process so a screen-on skips the decode. */
private object WallpaperCache {
    @Volatile
    var revision: Long = Long.MIN_VALUE

    @Volatile
    var bitmap: Bitmap? = null
}

@Composable
private fun rememberWallpaper(settings: AppSettings, revision: Long, targetWidth: Int): Bitmap? {
    val cached = if (WallpaperCache.revision == revision) WallpaperCache.bitmap else null
    val state = produceState(initialValue = cached, revision) {
        if (WallpaperCache.revision == revision) {
            value = WallpaperCache.bitmap
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) { loadWallpaperBitmap(settings, targetWidth) }
        WallpaperCache.bitmap = loaded
        WallpaperCache.revision = revision
        value = loaded
    }
    return state.value
}

private fun loadWallpaperBitmap(settings: AppSettings, targetWidth: Int): Bitmap? {
    val file = settings.wallpaperFile()
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        // Wallpapers picked since v0.6 are already screen-sized; older ones may be full photos.
        var sample = 1
        if (bounds.outWidth > 0 && targetWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        Log.e("LockList", "wallpaper decode failed", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e("LockList", "wallpaper too large to decode", e)
        null
    }
}
