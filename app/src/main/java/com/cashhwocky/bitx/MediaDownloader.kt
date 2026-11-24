package com.cashhwocky.bitx

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object MediaDownloader {
    private val client = OkHttpClient()

    suspend fun saveToGallery(context: Context, url: String, isVideo: Boolean) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, if (isVideo) "Saving video..." else "Saving image...", Toast.LENGTH_SHORT).show()
        }
        val success = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body ?: return@withContext false
                    val ext = if (isVideo) "mp4" else "jpg"
                    val mime = if (isVideo) "video/mp4" else "image/jpeg"
                    val fileName = "bitX_${System.currentTimeMillis()}.$ext"
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val relPath = if (isVideo) {
                        "${Environment.DIRECTORY_MOVIES}/bitX"
                    } else {
                        "${Environment.DIRECTORY_PICTURES}/bitX"
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    }
                    val uri = context.contentResolver.insert(collection, values) ?: return@withContext false
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().copyTo(out)
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
        withContext(Dispatchers.Main) {
            val msg = if (success) "Saved to Camera Roll (bitX folder)" else "Failed to save media"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
