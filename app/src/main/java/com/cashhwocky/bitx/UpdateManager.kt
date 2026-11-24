package com.cashhwocky.bitx

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class UpdateInfo(
    val versionTag: String,
    val apkUrl: String,
    val notes: String
)

object UpdateManager {
    private val client = OkHttpClient()
    private const val REPO = "cashhwocky/bitX"

    private fun isNewer(remote: String, current: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        if (rParts.isNotEmpty() && cParts.isNotEmpty()) {
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }
        return remote != current
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "bitX-Updater")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name").removePrefix("v").trim()
                val notes = json.optString("body").ifBlank { "A new version of bitX is ready to install." }
                val assets = json.optJSONArray("assets") ?: return@withContext null

                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }

                if (tag.isNotBlank() && !apkUrl.isNullOrBlank() && isNewer(tag, currentVersion)) {
                    UpdateInfo(tag, apkUrl, notes)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstall(context: Context, apkUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(apkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val apkFile = File(context.cacheDir, "bitX-update.apk")
                resp.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
