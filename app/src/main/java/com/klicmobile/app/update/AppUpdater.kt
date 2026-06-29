package com.klicmobile.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.klicmobile.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update against the public GitHub releases of klic-mobile-android. No Play Store:
 * the app checks the latest release, downloads its APK, and hands it to the system
 * installer. The new APK must be signed with the same key (the project's debug key) to
 * update in place. The repo is public, so no auth/token is needed to read or download.
 */
object AppUpdater {
    private const val LATEST_URL =
        "https://api.github.com/repos/pstepanovum/klic-mobile-android/releases/latest"

    private val client = OkHttpClient()

    data class Release(val versionName: String, val apkUrl: String, val notes: String)

    /** Fetch the latest published (non-prerelease) release, or null on any failure. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isEmpty()) return@use null
                val assets = json.optJSONArray("assets") ?: return@use null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                apkUrl?.let { Release(tag, it, json.optString("body")) }
            }
        }.getOrNull()
    }

    /** True when [latest] is a strictly higher semantic version than what's installed. */
    fun isNewerThanInstalled(latest: String): Boolean = compareVersions(latest, BuildConfig.VERSION_NAME) > 0

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** Download the APK to the cache dir, reporting 0f..1f progress. Returns the file. */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "klic-update.apk")
            if (out.exists()) out.delete()
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code})")
                val body = resp.body ?: error("Empty response")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
            }
            out
        }

    /** Whether the OS will let us install APKs (Android O+ requires a per-app grant). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to grant "install unknown apps" for Klic. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Launch the system package installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
