package com.brianchen.locklist.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.brianchen.locklist.data.Task
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uploads phone photos into the portal's task-images bucket and removes them again. */
class TaskImages(
    context: Context,
    private val supabase: SupabaseClient,
    private val thumbs: ThumbCache
) {
    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val queueLock = Any()

    /** One queued storage delete. [queuedAt] is phone time. */
    data class PendingRemoval(val taskId: String, val path: String, val queuedAt: Long) {
        internal fun encode(): String = "$taskId\n$path\n$queuedAt"
    }

    /** Downscales the picked image, uploads it, and returns the storage path to store on the task. */
    suspend fun attach(task: Task, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((longest / MAX_EDGE).coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        val scaled = fitLongestEdge(bitmap, MAX_EDGE)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        val path = "${task.id}/${System.currentTimeMillis()}-photo.jpg"
        supabase.storage.from(BUCKET).upload(path, jpeg) {
            upsert = false
            contentType = ContentType.Image.JPEG
        }
        thumbs.seed(path, jpeg)
        path
    }

    /**
     * Queues a storage delete for [path]. The sync runs it only after the task's change has
     * reached the server and the server row no longer lists the path, so a delayed or lost
     * push can never leave the web pointing at a missing file.
     */
    fun removeLater(taskId: String, path: String) {
        if (taskId.isBlank() || path.isBlank()) return
        synchronized(queueLock) {
            val current = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            val kept = current.filterNot { decode(it)?.let { e -> e.taskId == taskId && e.path == path } == true }
            val next = kept.toMutableSet()
            next += PendingRemoval(taskId, path, System.currentTimeMillis()).encode()
            prefs.edit().putStringSet(KEY_PENDING, next).apply()
        }
    }

    /** Deletes a file right away. Prefer [removeLater]; the UI must not call this directly. */
    suspend fun remove(path: String) = withContext(Dispatchers.IO) {
        try {
            supabase.storage.from(BUCKET).delete(listOf(path))
        } catch (e: Exception) {
            // An orphaned file is harmless; the task no longer points at it.
            Log.w("LockList", "could not delete $path", e)
        }
        thumbs.evict(path)
    }

    internal fun pendingRemovals(): List<PendingRemoval> = synchronized(queueLock) {
        prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().mapNotNull { decode(it) }
    }

    internal fun dropPending(entry: PendingRemoval) {
        synchronized(queueLock) {
            val current = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            val next = current.filterNot { decode(it) == entry }.toSet()
            if (next.size != current.size) prefs.edit().putStringSet(KEY_PENDING, next).apply()
        }
    }

    /** Deletes the file now; throws on failure so the queue entry is kept for a later pass. */
    internal suspend fun deleteNow(path: String) = withContext(Dispatchers.IO) {
        supabase.storage.from(BUCKET).delete(listOf(path))
        thumbs.evict(path)
    }

    private fun decode(raw: String): PendingRemoval? {
        val parts = raw.split('\n')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val queuedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return PendingRemoval(parts[0], parts[1], queuedAt)
    }

    private fun fitLongestEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val BUCKET = "task-images"
        private const val MAX_EDGE = 1600
        private const val QUALITY = 85
        private const val PREFS = "locklist_sync"
        private const val KEY_PENDING = "pendingImageDeletes"
    }
}
