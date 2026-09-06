package com.brianchen.locklist.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.brianchen.locklist.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

class AppUpdate {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val release = getJson("https://api.github.com/repos/$REPO/releases/latest")
            .let { json.decodeFromString<GithubRelease>(it) }
        val latestAsset = release.assets.firstOrNull { it.name == LATEST_JSON }
            ?: error("Release ${release.tagName} is missing $LATEST_JSON")
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: error("Release ${release.tagName} has no APK")
        val meta = json.decodeFromString<LatestMeta>(getJson(latestAsset.downloadUrl))
        UpdateInfo(
            versionCode = meta.versionCode,
            versionName = meta.versionName,
            apkUrl = apk.downloadUrl
        )
    }

    suspend fun downloadApk(url: String, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        openStream(url).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun getJson(url: String): String {
        return openStream(url).bufferedReader().use { it.readText() }
    }

    private fun openStream(url: String): java.io.InputStream {
        var current = url
        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("User-Agent", "LockList")
                val token = BuildConfig.GITHUB_UPDATE_TOKEN
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location")
                    ?: error("Redirect without Location from $current")
                current = next
                connection.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("GitHub $code: ${err.ifBlank { connection.responseMessage }}")
            }
            return connection.inputStream
        }
        error("Too many redirects for $url")
    }

    companion object {
        private val REPO = BuildConfig.GITHUB_REPO.ifBlank { "brianchenhao/locklist" }
        const val LATEST_JSON = "latest.json"
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String
)

@Serializable
data class LatestMeta(
    val versionCode: Int,
    val versionName: String
)
