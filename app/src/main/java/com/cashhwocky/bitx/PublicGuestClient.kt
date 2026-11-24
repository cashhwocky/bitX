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

    private fun isDirectVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".m3u8")
    }

    private fun toPlayableEmbedUrl(rawUrl: String): String {
        // Convert YouTube watch/shorts/youtu.be URLs into clean embeddable player URLs
        val ytId = Regex("""(?:youtube\.com/(?:watch\?v=|shorts/|embed/)|youtu\.be/)([a-zA-Z0-9_-]{11})""")
            .find(rawUrl)?.groupValues?.getOrNull(1)
        if (ytId != null) {
            return "https://www.youtube.com/embed/$ytId?autoplay=1&playsinline=1"
        }
        // Convert PeerTube /w/ID or /videos/watch/ID to /videos/embed/ID
        if (rawUrl.contains("/videos/watch/")) {
            return rawUrl.replace("/videos/watch/", "/videos/embed/")
        }
        val ptMatch = Regex("""^(https?://[^/]+)/w/([a-zA-Z0-9_-]+)""").find(rawUrl)
        if (ptMatch != null) {
            return "${ptMatch.groupValues[1]}/videos/embed/${ptMatch.groupValues[2]}"
        }
        return rawUrl
    }

    private fun looksLikeVideoLink(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("youtube.com") ||
            l.contains("youtu.be") ||
            l.contains("vimeo.com") ||
            l.contains("peertube") ||
            l.contains("/videos/watch/") ||
            l.contains("/videos/embed/") ||
            l.contains("/w/") ||
            l.contains("streamable.com") ||
            l.contains("redgifs.com") ||
            l.contains("tiktok.com") ||
            l.contains("twitch.tv") ||
            l.contains("dailymotion.com")
    }

    private fun parseStatus(item: JSONObject, fallbackId: String): NativeTweet? {
        val account = item.optJSONObject("account") ?: return null
        val rawHtml = item.optString("content", "")
        val mediaList = mutableListOf<String>()
        var isVideo = false
        var videoUrl: String? = null
        var embedVideoUrl: String? = null

        val mediaArr = item.optJSONArray("media_attachments")
        if (mediaArr != null) {
            for (j in 0 until mediaArr.length()) {
                val m = mediaArr.optJSONObject(j) ?: continue
                val type = m.optString("type").lowercase()
                val directUrl = m.optString("url").takeIf { it.isNotBlank() && it != "null" }
                val remoteUrl = m.optString("remote_url").takeIf { it.isNotBlank() && it != "null" }
                val previewUrl = m.optString("preview_url").takeIf { it.isNotBlank() && it != "null" }
                val resolvedUrl = directUrl ?: remoteUrl

                if (type in listOf("video", "gifv") || isDirectVideoUrl(resolvedUrl) || isDirectVideoUrl(remoteUrl)) {
                    isVideo = true
                    if (videoUrl == null) {
                        videoUrl = if (isDirectVideoUrl(resolvedUrl)) resolvedUrl
                        else if (isDirectVideoUrl(remoteUrl)) remoteUrl
                        else resolvedUrl
                    }
                    if (previewUrl != null) mediaList.add(previewUrl)
                } else {
                    val bestImg = resolvedUrl ?: previewUrl
                    if (bestImg != null) mediaList.add(bestImg)
                }
            }
        }

        // Check Mastodon link preview card (iframe html or video URL)
        val card = item.optJSONObject("card")
        if (card != null && videoUrl == null) {
            val cardType = card.optString("type").lowercase()
            val cardUrl = card.optString("url").takeIf { it.isNotBlank() && it != "null" }
            val cardHtml = card.optString("html", "")
            val cardImg = card.optString("image").takeIf { it.isNotBlank() && it != "null" }
            if (cardImg != null && mediaList.isEmpty()) mediaList.add(cardImg)

            val iframeSrc = Regex("""src=["'](https?://[^"']+)["']""").find(cardHtml)?.groupValues?.getOrNull(1)
            if (iframeSrc != null) {
                isVideo = true
                embedVideoUrl = iframeSrc
            } else if (cardUrl != null && (cardType == "video" || looksLikeVideoLink(cardUrl))) {
                isVideo = true
                embedVideoUrl = toPlayableEmbedUrl(cardUrl)
            }
        }

        // Also scan links inside post HTML in case card was omitted
        if (videoUrl == null && embedVideoUrl == null) {
            val hrefs = Regex("""href=["'](https?://[^"']+)["']""").findAll(rawHtml).map { it.groupValues[1] }
            for (href in hrefs) {
                if (isDirectVideoUrl(href)) {
                    isVideo = true
                    videoUrl = href
                    break
                } else if (looksLikeVideoLink(href)) {
                    isVideo = true
                    embedVideoUrl = toPlayableEmbedUrl(href)
                    break
                }
            }
        }

        val hasAnyMedia = mediaList.isNotEmpty() || videoUrl != null || embedVideoUrl != null
        val text = stripHtml(rawHtml, stripTrailingUrls = hasAnyMedia)
        if (text.isBlank() && !hasAnyMedia) return null

        return NativeTweet(
            id = item.optString("id", fallbackId),
            authorName = account.optString("display_name").ifBlank { account.optString("username", "User") },
            handle = "@${account.optString("acct", "user")}",
            avatarUrl = account.optString("avatar", ""),
            text = text,
            mediaUrl = mediaList.firstOrNull(),
            mediaUrls = mediaList,
            videoUrl = videoUrl,
            embedVideoUrl = embedVideoUrl,
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
                BitXTab.SAVED -> return@withContext Result.success(emptyList())
                BitXTab.SEARCH -> {
                    val cleanTag = query.trim().removePrefix("#").replace(Regex("[^a-zA-Z0-9_]"), "")
                    if (cleanTag.isBlank()) return@withContext Result.success(emptyList())
                    val encoded = URLEncoder.encode(cleanTag, "UTF-8")
                    "https://mastodon.social/api/v1/timelines/tag/$encoded?limit=40"
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
                Result.success(list)
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
