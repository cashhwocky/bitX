package com.cashhwocky.bitx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class BskySession(
    val accessJwt: String,
    val handle: String
)

object BlueskyClient {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private const val UA = "bitX/3.0 (Android; +https://github.com/cashhwocky/bitX)"

    suspend fun login(identifier: String, password: String): Result<BskySession> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("identifier", identifier.trim().removePrefix("@"))
                put("password", password)
            }.toString()

            val req = Request.Builder()
                .url("https://bsky.social/xrpc/com.atproto.server.createSession")
                .post(payload.toRequestBody(jsonType))
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optString("message", "HTTP ${resp.code}")
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return@withContext Result.failure(Exception("Bluesky login failed: $errMsg"))
                }
                val json = JSONObject(body)
                val jwt = json.getString("accessJwt")
                val handle = json.optString("handle", identifier)
                Result.success(BskySession(jwt, handle))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parsePostObj(post: JSONObject, fallbackId: String): NativeTweet? {
        val author = post.optJSONObject("author") ?: return null
        val record = post.optJSONObject("record") ?: return null
        val embed = post.optJSONObject("embed")

        val mediaList = mutableListOf<String>()
        var videoUrl: String? = null
        var externalUrl: String? = null
        var isVideo = false

        if (embed != null) {
            val embedType = embed.optString("\$type", "")
            // Images
            val images = embed.optJSONArray("images")
                ?: embed.optJSONObject("media")?.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val img = images.optJSONObject(i) ?: continue
                    val full = img.optString("fullsize").takeIf { it.isNotBlank() }
                        ?: img.optString("thumb").takeIf { it.isNotBlank() }
                    if (full != null) mediaList.add(full)
                }
            }
            // Native Bluesky HLS Video
            val playlist = embed.optString("playlist").takeIf { it.isNotBlank() }
                ?: embed.optJSONObject("media")?.optString("playlist")?.takeIf { it.isNotBlank() }
            if (playlist != null || embedType.contains("video")) {
                isVideo = true
                videoUrl = playlist
                val thumb = embed.optString("thumbnail").takeIf { it.isNotBlank() }
                    ?: embed.optJSONObject("media")?.optString("thumbnail")?.takeIf { it.isNotBlank() }
                if (thumb != null) mediaList.add(thumb)
            }
            // External Link Card
            val ext = embed.optJSONObject("external")
            if (ext != null && videoUrl == null) {
                val uri = ext.optString("uri").takeIf { it.isNotBlank() }
                val thumb = ext.optString("thumb").takeIf { it.isNotBlank() }
                if (thumb != null && mediaList.isEmpty()) mediaList.add(thumb)
                if (uri != null) externalUrl = uri
            }
        }

        val labels = post.optJSONArray("labels")
        var sensitive = false
        if (labels != null) {
            for (i in 0 until labels.length()) {
                val lbl = labels.optJSONObject(i)?.optString("val", "").orEmpty().lowercase()
                if (lbl in listOf("porn", "sexual", "nudity", "nsfw", "graphic-media")) {
                    sensitive = true
                }
            }
        }

        return NativeTweet(
            id = post.optString("uri", fallbackId),
            authorName = author.optString("displayName").ifBlank { author.optString("handle", "User") },
            handle = "@${author.optString("handle", "user")}",
            avatarUrl = author.optString("avatar", ""),
            text = record.optString("text", ""),
            mediaUrl = mediaList.firstOrNull(),
            mediaUrls = mediaList,
            videoUrl = videoUrl,
            externalUrl = externalUrl,
            likes = post.optInt("likeCount", 0),
            retweets = post.optInt("repostCount", 0),
            replies = post.optInt("replyCount", 0),
            isVideo = isVideo,
            isSensitive = sensitive,
            source = FeedSource.BLUESKY
        )
    }

    suspend fun fetchFeed(
        accessJwt: String?,
        tab: BitXTab,
        query: String = ""
    ): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            if (tab == BitXTab.SAVED) return@withContext Result.success(emptyList())
            if (accessJwt.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Sign in to Bluesky in Settings to load your Bluesky feed."))
            }

            val url = when (tab) {
                BitXTab.HOME -> "https://bsky.social/xrpc/app.bsky.feed.getTimeline?limit=35"
                BitXTab.SEARCH -> {
                    val q = query.trim()
                    if (q.isBlank()) return@withContext Result.success(emptyList())
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    "https://bsky.social/xrpc/app.bsky.feed.searchPosts?q=$encoded&limit=35"
                }
                BitXTab.SAVED -> return@withContext Result.success(emptyList())
            }

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessJwt")
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Bluesky HTTP ${resp.code}: session may have expired"))
                }
                val root = JSONObject(body)
                val list = mutableListOf<NativeTweet>()
                if (tab == BitXTab.HOME) {
                    val feed = root.optJSONArray("feed") ?: JSONArray()
                    for (i in 0 until feed.length()) {
                        val post = feed.optJSONObject(i)?.optJSONObject("post") ?: continue
                        parsePostObj(post, "bsky_$i")?.let { list.add(it) }
                    }
                } else {
                    val posts = root.optJSONArray("posts") ?: JSONArray()
                    for (i in 0 until posts.length()) {
                        val post = posts.optJSONObject(i) ?: continue
                        parsePostObj(post, "bsky_s_$i")?.let { list.add(it) }
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchReplies(accessJwt: String, postUri: String): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(postUri, "UTF-8")
            val req = Request.Builder()
                .url("https://bsky.social/xrpc/app.bsky.feed.getPostThread?uri=$encoded&depth=2")
                .header("Authorization", "Bearer $accessJwt")
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Replies HTTP ${resp.code}"))
                val replies = JSONObject(body).optJSONObject("thread")?.optJSONArray("replies") ?: JSONArray()
                val list = mutableListOf<NativeTweet>()
                for (i in 0 until replies.length()) {
                    val post = replies.optJSONObject(i)?.optJSONObject("post") ?: continue
                    parsePostObj(post, "bsky_r_$i")?.let { list.add(it) }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
