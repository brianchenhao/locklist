package com.brianchen.locklist.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.ui.theme.LockListTheme

class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LockListTheme(darkTheme = true, dynamicColor = false) {
                LockChecklistScreen(onDismiss = { finish() })
            }
        }
    }
}

private data class HardcodedItem(val title: String, val done: Boolean)

@Composable
private fun LockChecklistScreen(onDismiss: () -> Unit) {
    val items = remember {
        mutableStateListOf(
            HardcodedItem("Keys", false),
            HardcodedItem("Wallet", false),
            HardcodedItem("Phone", false)
        )
    }
    val doneCount = items.count { it.done }
    var drag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
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
            .padding(horizontal = 24.dp, vertical = 48.dp)
    ) {
        Text(
            text = "$doneCount of 3 done",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { doneCount / 3f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(32.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        items[index] = item.copy(done = !item.done)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = { items[index] = item.copy(done = it) }
                )
                Text(
                    text = item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Dismiss")
        }
    }
}
