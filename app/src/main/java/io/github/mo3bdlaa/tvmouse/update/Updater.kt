package io.github.mo3bdlaa.tvmouse.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.mo3bdlaa.tvmouse.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and hands it to the system installer. */
object Updater {

    data class Release(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

    sealed interface Result {
        data class Available(val release: Release) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    private val latestUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(latestUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                    HttpURLConnection.HTTP_NOT_FOUND -> Result.UpToDate // no releases yet
                    else -> Result.Failed("GitHub answered HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun parse(body: String): Result {
        val json = JSONObject(body)
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
        }
        if (apkUrl == null || !Versions.isNewer(version, BuildConfig.VERSION_NAME)) return Result.UpToDate
        return Result.Available(
            Release(
                version = version,
                notes = json.optString("body").trim(),
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url"),
            ),
        )
    }

    /** Downloads the release APK into the cache. [onProgress] gets 0..1, or -1 when the size is unknown. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val target = File(dir, "tv-mouse-${release.version}.apk")
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    error("Download failed: HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                var done = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            } else {
                                onProgress(-1f)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            target
        }

    /** Android asks the user once to allow this app to install updates. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

object Versions {
    private fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** True when [candidate] (e.g. "1.2.0") is newer than [current] (e.g. "1.1.9" or "0.0.0-dev"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
