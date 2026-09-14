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

    suspend fun remove(path: String) = withContext(Dispatchers.IO) {
        try {
            supabase.storage.from(BUCKET).delete(listOf(path))
        } catch (e: Exception) {
            // An orphaned file is harmless; the task no longer points at it.
            Log.w("LockList", "could not delete $path", e)
        }
        thumbs.evict(path)
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
    }
}
