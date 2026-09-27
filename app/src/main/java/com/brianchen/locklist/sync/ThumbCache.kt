package com.brianchen.locklist.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbCache(
    context: Context,
    private val supabase: SupabaseClient
) {
    private val dir = File(context.applicationContext.filesDir, "thumbs").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(32)

    suspend fun thumb(path: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(path)?.let { return@withContext it }
        val file = cacheFile(path)
        if (file.exists()) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) {
                memory.put(path, cached)
                return@withContext cached
            }
        }
        val url = signedUrl(path) ?: return@withContext null
        val bytes = download(url) ?: return@withContext null
        val sampled = decodeSampled(bytes, TARGET_PX) ?: return@withContext null
        file.outputStream().use { out ->
            sampled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        memory.put(path, sampled)
        sampled
    }

    /** Store a thumb for an image the phone just uploaded, so it shows without a download. */
    fun seed(path: String, jpeg: ByteArray) {
        val sampled = decodeSampled(jpeg, TARGET_PX) ?: return
        cacheFile(path).outputStream().use { out ->
            sampled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        memory.put(path, sampled)
    }

    fun evict(path: String) {
        memory.remove(path)
        cacheFile(path).delete()
    }

    suspend fun signedUrl(path: String): String? = withContext(Dispatchers.IO) {
        try {
            supabase.storage.from(BUCKET).createSignedUrl(path, SIGNED_TTL_SECONDS.seconds)
        } catch (_: Exception) {
            null
        }
    }

    // URL.openStream has no timeout on Android; a dead connection would hang the thumb forever.
    private fun download(url: String): ByteArray? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFile(path: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(dir, "$name.jpg")
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = (longest / target).coerceAtLeast(1)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    companion object {
        private const val BUCKET = "task-images"
        private const val TARGET_PX = 128
        private const val SIGNED_TTL_SECONDS = 3600
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
