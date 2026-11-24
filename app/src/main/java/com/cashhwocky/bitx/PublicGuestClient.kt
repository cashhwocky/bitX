package com.cashhwocky.bitx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

object PublicGuestClient {
    private val client = OkHttpClient()

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    suspend fun fetchFeed(tab: BitXTab, query: String = ""): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val url = when (tab) {
                BitXTab.HOME -> "https://mastodon.social/api/v1/trends/statuses?limit=25"
                BitXTab.CLIPS -> "https://mastodon.social/api/v1/timelines/public?only_media=true&limit=25"
                BitXTab.SAVED -> "https://mastodon.social/api/v1/timelines/tag/android?limit=25"
                BitXTab.SEARCH -> {
                    val cleanTag = query.trim().removePrefix("#").replace(Regex("[^a-zA-Z0-9_]"), "")
                    if (cleanTag.isBlank()) {
                        "https://mastodon.social/api/v1/trends/statuses?limit=25"
                    } else {
                        val encoded = URLEncoder.encode(cleanTag, "UTF-8")
                        "https://mastodon.social/api/v1/timelines/tag/$encoded?limit=25"
                    }
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "bitX/2.0 (Android; +https://github.com/cashhwocky/bitX)")
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
                    val account = item.optJSONObject("account") ?: continue
                    val text = stripHtml(item.optString("content", ""))
                    if (text.isBlank()) continue

                    val mediaArr = item.optJSONArray("media_attachments")
                    val firstMedia = mediaArr?.optJSONObject(0)
                    val mediaUrl = firstMedia?.optString("preview_url")
                        ?.takeIf { it.isNotBlank() }
                        ?: firstMedia?.optString("url")?.takeIf { it.isNotBlank() }
                    val isVideo = firstMedia?.optString("type") in listOf("video", "gifv")

                    list.add(
                        NativeTweet(
                            id = item.optString("id", i.toString()),
                            authorName = account.optString("display_name").ifBlank { account.optString("username", "User") },
                            handle = "@${account.optString("acct", "user")}",
                            avatarUrl = account.optString("avatar", ""),
                            text = text,
                            mediaUrl = mediaUrl,
                            likes = item.optInt("favourites_count", 0),
                            retweets = item.optInt("reblogs_count", 0),
                            replies = item.optInt("replies_count", 0),
                            isVideo = isVideo
                        )
                    )
                }

                val filtered = if (tab == BitXTab.CLIPS) list.filter { !it.mediaUrl.isNullOrBlank() } else list
                Result.success(filtered)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
