package com.cashhwocky.bitx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object PublicGuestClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private const val UA = "bitX/3.0 (Android; +https://github.com/cashhwocky/bitX)"
    private var homeRefreshCount = 0

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

    private fun isVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".m3u8")
    }

    private fun parseStatus(rawItem: JSONObject, fallbackId: String): NativeTweet? {
        val item = rawItem.optJSONObject("reblog") ?: rawItem
        val account = item.optJSONObject("account") ?: return null
        val mediaList = mutableListOf<String>()
        var isVideo = false
        var videoUrl: String? = null
        var externalUrl: String? = null

        val mediaArr = item.optJSONArray("media_attachments")
        if (mediaArr != null) {
            for (j in 0 until mediaArr.length()) {
                val m = mediaArr.optJSONObject(j) ?: continue
                val type = m.optString("type").lowercase()
                val directUrl = m.optString("url").takeIf { it.isNotBlank() && it != "null" }
                val remoteUrl = m.optString("remote_url").takeIf { it.isNotBlank() && it != "null" }
                val previewUrl = m.optString("preview_url").takeIf { it.isNotBlank() && it != "null" }

                val chosenVideo = when {
                    isVideoUrl(directUrl) -> directUrl
                    isVideoUrl(remoteUrl) -> remoteUrl
                    type in listOf("video", "gifv") -> directUrl ?: remoteUrl
                    else -> null
                }

                if (chosenVideo != null) {
                    isVideo = true
                    if (videoUrl == null) videoUrl = chosenVideo
                    if (previewUrl != null) mediaList.add(previewUrl)
                } else {
                    val bestImg = directUrl ?: remoteUrl ?: previewUrl
                    if (bestImg != null) mediaList.add(bestImg)
                }
            }
        }

        val card = item.optJSONObject("card")
        if (card != null && videoUrl == null) {
            val cardUrl = card.optString("url").takeIf { it.isNotBlank() && it != "null" }
            val cardImg = card.optString("image").takeIf { it.isNotBlank() && it != "null" }
            if (cardImg != null && mediaList.isEmpty()) mediaList.add(cardImg)
            if (cardUrl != null) externalUrl = cardUrl
        }

        val text = stripHtml(item.optString("content", ""), stripTrailingUrls = mediaList.isNotEmpty() || videoUrl != null)
        if (text.isBlank() && mediaList.isEmpty() && videoUrl == null) return null

        return NativeTweet(
            id = item.optString("id", fallbackId),
            authorName = account.optString("display_name").ifBlank { account.optString("username", "User") },
            handle = "@${account.optString("acct", "user")}",
            avatarUrl = account.optString("avatar", ""),
            text = text,
            mediaUrl = mediaList.firstOrNull(),
            mediaUrls = mediaList,
            videoUrl = videoUrl,
            externalUrl = externalUrl,
            likes = item.optInt("favourites_count", 0),
            retweets = item.optInt("reblogs_count", 0),
            replies = item.optInt("replies_count", 0),
            isVideo = isVideo,
            isSensitive = item.optBoolean("sensitive", false),
            spoilerText = item.optString("spoiler_text", ""),
            source = FeedSource.PUBLIC
        )
    }

    private fun fetchUrl(url: String): Result<List<NativeTweet>> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val arr = JSONArray(body)
                val list = mutableListOf<NativeTweet>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseStatus(item, i.toString())?.let { list.add(it) }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFeed(tab: BitXTab, query: String = "", forceFresh: Boolean = false): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        val urls = when (tab) {
            BitXTab.HOME -> {
                if (forceFresh) homeRefreshCount++
                val primary = when (homeRefreshCount % 3) {
                    0 -> "https://mastodon.social/api/v1/trends/statuses?limit=30"
                    1 -> "https://mastodon.social/api/v1/timelines/public?only_media=true&limit=30"
                    else -> "https://mastodon.social/api/v1/timelines/public?limit=30"
                }
                listOf(
                    primary,
                    "https://mastodon.social/api/v1/trends/statuses?limit=30",
                    "https://mstdn.social/api/v1/trends/statuses?limit=25"
                )
            }
            BitXTab.SAVED -> return@withContext Result.success(emptyList())
            BitXTab.SEARCH -> {
                val cleanTag = query.trim().removePrefix("#").replace(Regex("[^a-zA-Z0-9_]"), "")
                if (cleanTag.isBlank()) return@withContext Result.success(emptyList())
                val encoded = URLEncoder.encode(cleanTag, "UTF-8")
                listOf(
                    "https://mastodon.social/api/v1/timelines/tag/$encoded?only_media=true&limit=40",
                    "https://mastodon.social/api/v1/timelines/tag/$encoded?limit=35"
                )
            }
        }

        var lastErr: Throwable = Exception("Network error")
        for (url in urls) {
            val res = fetchUrl(url)
            if (res.isSuccess) {
                val list = res.getOrDefault(emptyList())
                if (list.isNotEmpty() || url == urls.last()) {
                    return@withContext Result.success(if (forceFresh && tab == BitXTab.HOME) list.shuffled() else list)
                }
            } else {
                lastErr = res.exceptionOrNull() ?: lastErr
                delay(250)
            }
        }
        Result.failure(lastErr)
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
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Comments HTTP ${resp.code}"))
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
