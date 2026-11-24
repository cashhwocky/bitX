package com.cashhwocky.bitx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object PublicGuestClient {
    private val client = OkHttpClient()

    suspend fun fetchFeed(tab: BitXTab, query: String = ""): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val q = when (tab) {
                BitXTab.SEARCH -> query.ifBlank { "trending" }
                BitXTab.CLIPS -> "video OR clip"
                BitXTab.SAVED -> "android OR pixel"
                BitXTab.HOME -> "tech OR gaming OR music"
            }
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?q=$encoded&limit=25"

            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Public feed error: HTTP ${resp.code}"))
                }
                val root = JSONObject(body)
                val posts = root.optJSONArray("posts") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<NativeTweet>()

                for (i in 0 until posts.length()) {
                    val p = posts.optJSONObject(i) ?: continue
                    val author = p.optJSONObject("author") ?: continue
                    val record = p.optJSONObject("record") ?: continue
                    val embed = p.optJSONObject("embed")

                    val imgUrl = embed?.optJSONArray("images")?.optJSONObject(0)?.optString("fullsize")
                        ?: embed?.optString("thumbnail")?.takeIf { it.isNotBlank() }

                    list.add(
                        NativeTweet(
                            id = p.optString("uri", i.toString()),
                            authorName = author.optString("displayName").ifBlank { author.optString("handle", "User") },
                            handle = "@${author.optString("handle", "user")}",
                            avatarUrl = author.optString("avatar", ""),
                            text = record.optString("text", ""),
                            mediaUrl = imgUrl,
                            likes = p.optInt("likeCount", 0),
                            retweets = p.optInt("repostCount", 0),
                            replies = p.optInt("replyCount", 0),
                            isVideo = embed?.optString("\$type")?.contains("video") == true
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
