package com.cashhwocky.bitx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object PublicGuestClient {
    private val client = OkHttpClient()
    private const val UA = "bitX/2.0 (Android; +https://github.com/cashhwocky/bitX)"

    private fun stripHtml(html: String, stripTrailingUrls: Boolean): String {
        var text = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
        if (stripTrailingUrls) {
            text = text.replace(Regex("https?://\\S+"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
        }
        return text
    }

    private fun parseStatus(item: JSONObject, fallbackId: String): NativeTweet? {
        val account = item.optJSONObject("account") ?: return null
        val mediaList = mutableListOf<String>()
        var isVideo = false

        val mediaArr = item.optJSONArray("media_attachments")
        if (mediaArr != null) {
            for (j in 0 until mediaArr.length()) {
                val m = mediaArr.optJSONObject(j) ?: continue
                val type = m.optString("type")
                if (type in listOf("video", "gifv")) isVideo = true
                val bestUrl = if (type == "image") {
                    m.optString("url").takeIf { it.isNotBlank() } ?: m.optString("preview_url")
                } else {
                    m.optString("preview_url").takeIf { it.isNotBlank() } ?: m.optString("url")
                }
                if (!bestUrl.isNullOrBlank()) mediaList.add(bestUrl)
            }
        }

        // Fallback to link preview card image if no direct media attachment
        if (mediaList.isEmpty()) {
            val cardImg = item.optJSONObject("card")?.optString("image")?.takeIf { it.isNotBlank() }
            if (cardImg != null) mediaList.add(cardImg)
        }

        val text = stripHtml(item.optString("content", ""), stripTrailingUrls = mediaList.isNotEmpty())
        if (text.isBlank() && mediaList.isEmpty()) return null

        return NativeTweet(
            id = item.optString("id", fallbackId),
            authorName = account.optString("display_name").ifBlank { account.optString("username", "User") },
            handle = "@${account.optString("acct", "user")}",
            avatarUrl = account.optString("avatar", ""),
            text = text,
            mediaUrl = mediaList.firstOrNull(),
            mediaUrls = mediaList,
            likes = item.optInt("favourites_count", 0),
            retweets = item.optInt("reblogs_count", 0),
            replies = item.optInt("replies_count", 0),
            isVideo = isVideo,
            isSensitive = item.optBoolean("sensitive", false),
            spoilerText = item.optString("spoiler_text", "")
        )
    }

    suspend fun fetchFeed(tab: BitXTab, query: String = ""): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val url = when (tab) {
                BitXTab.HOME -> "https://mastodon.social/api/v1/trends/statuses?limit=30"
                BitXTab.CLIPS -> "https://mastodon.social/api/v1/timelines/public?only_media=true&limit=30"
                BitXTab.SAVED -> "https://mastodon.social/api/v1/timelines/tag/android?limit=30"
                BitXTab.SEARCH -> {
                    val cleanTag = query.trim().removePrefix("#").replace(Regex("[^a-zA-Z0-9_]"), "")
                    if (cleanTag.isBlank()) {
                        "https://mastodon.social/api/v1/trends/statuses?limit=30"
                    } else {
                        val encoded = URLEncoder.encode(cleanTag, "UTF-8")
                        "https://mastodon.social/api/v1/timelines/tag/$encoded?limit=30"
                    }
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Public feed error: HTTP ${resp.code}"))
                }
                val arr = JSONArray(body)
                val list = mutableListOf<NativeTweet>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseStatus(item, i.toString())?.let { list.add(it) }
                }
                val filtered = if (tab == BitXTab.CLIPS) list.filter { it.mediaUrls.isNotEmpty() } else list
                Result.success(filtered)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchReplies(statusId: String): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://mastodon.social/api/v1/statuses/$statusId/context"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Comments error: HTTP ${resp.code}"))
                }
                val descendants = JSONObject(body).optJSONArray("descendants") ?: JSONArray()
                val list = mutableListOf<NativeTweet>()
                for (i in 0 until descendants.length()) {
                    val item = descendants.optJSONObject(i) ?: continue
                    parseStatus(item, "reply_$i")?.let { list.add(it) }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
