package com.brianchen.locklist.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Unsent changes older than this count as "not synced" on the lock screen. */
private const val STALE_MS = 30 * 60_000L

/** Amber used for sync warnings on the dark lock screen. */
val LockAmber = Color(0xFFFFB300)

/**
 * Wall-clock time that ticks every [periodMs], so "2 min ago" style text stays current. It is
 * also read again each time the screen comes back: delay() does not count deep sleep, so a
 * reused activity would otherwise judge ages by the time it was last awake.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(periodMs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }
    return now
}

/** "Synced 2 min ago" (or "Synced 2m ago" when [short]). lastOkAt 0 means never. */
fun syncAgo(lastOkAt: Long, now: Long, short: Boolean = false): String {
    if (lastOkAt <= 0L) return "Not synced yet"
    val minutes = (now - lastOkAt).coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> if (short) "Synced ${minutes}m ago" else "Synced $minutes min ago"
        hours < 48 -> if (short) "Synced ${hours}h ago" else "Synced $hours h ago"
        else -> if (short) "Synced ${days}d ago" else "Synced $days days ago"
    }
}

fun pendingText(pending: Int): String? = when {
    pending <= 0 -> null
    pending == 1 -> "1 change waiting"
    else -> "$pending changes waiting"
}

/** "Synced 2 min ago · 1 change waiting". */
fun syncHealthLine(lastOkAt: Long, pending: Int, now: Long): String {
    return listOfNotNull(syncAgo(lastOkAt, now), pendingText(pending)).joinToString(" · ")
}

/**
 * True when the web refused something, or changes have waited more than 30 min to reach the
 * web. Being [offline] alone is normal (lift, plane, Wi-Fi still waking) and only counts once
 * changes have been waiting that long.
 */
fun syncNeedsAttention(
    lastOkAt: Long,
    lastError: String?,
    pending: Int,
    now: Long,
    offline: Boolean = false
): Boolean {
    if (!lastError.isNullOrBlank() && !offline) return true
    return pending > 0 && (lastOkAt <= 0L || now - lastOkAt > STALE_MS)
}

/** Amber that stays readable on both the light and the dark app theme. */
@Composable
fun warningColor(): Color {
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) LockAmber else Color(0xFF8A5A00)
}
