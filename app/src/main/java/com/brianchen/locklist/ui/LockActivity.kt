package com.brianchen.locklist.ui

import android.graphics.BitmapFactory
import android.os.Bundle
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.R
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskRepository
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
                    settings = app.settings,
                    wallpaperRevision = wallpaperRevision,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun LockChecklistScreen(
    tasks: List<Task>,
    repo: TaskRepository,
    settings: AppSettings,
    wallpaperRevision: Long,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val doneCount = tasks.count { it.done }
    val total = tasks.size
    val progress = if (total == 0) 0f else doneCount / total.toFloat()
    var drag by remember { mutableFloatStateOf(0f) }
    val wallpaper = remember(wallpaperRevision) {
        val file = settings.wallpaperFile()
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.bg_lock),
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
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                tasks.forEach { task ->
                    LockTaskRow(
                        task = task,
                        onToggle = { scope.launch { repo.setDone(task, !task.done) } }
                    )
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
private fun LockTaskRow(task: Task, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (task.done) 1.06f else 1f,
        animationSpec = spring(),
        label = "tickScale"
    )
    val textColor by animateColorAsState(
        targetValue = if (task.done) Color.White.copy(alpha = 0.55f) else Color.White,
        label = "tickColor"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = { onToggle() }
        )
        Text(
            text = task.title,
            color = textColor,
            style = MaterialTheme.typography.titleLarge,
            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
