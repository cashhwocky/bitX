package com.cashhwocky.bitx

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

enum class FeedSource(val label: String) {
    PUBLIC("Public"),
    BLUESKY("Bluesky"),
    X("X")
}

enum class AppStyle(val title: String, val subtitle: String) {
    MATERIAL_YOU("Material You", "Dynamic wallpaper palette"),
    AMOLED("AMOLED Pure", "Pitch #000000 + hairline glow"),
    LIQUID_GLASS("Liquid Glass", "Frosted layered translucency"),
    TIDAL("Tidal Minimal", "High-contrast sleek dark")
}

enum class AppIconOption(
    val aliasName: String,
    val title: String,
    val bgHex: Long,
    val accentHex: Long
) {
    CLASSIC(".IconClassic", "bit Classic", 0xFF111318, 0xFFB4C5FF),
    NEON(".IconNeon", "AMOLED Neon", 0xFF000000, 0xFF00F5FF),
    GLASS(".IconGlass", "Liquid Glass", 0xFF1E293B, 0xFFF8FAFC),
    CYBER(".IconCyber", "Sunset Cyber", 0xFF1A0B2E, 0xFFFF758F)
}

object AppIconManager {
    fun setIcon(context: Context, selected: AppIconOption) {
        val pm = context.packageManager
        val pkg = context.packageName
        AppIconOption.entries.forEach { option ->
            val state = if (option == selected) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "$pkg${option.aliasName}"),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
        Toast.makeText(context, "Icon changed to ${selected.title} (launcher refreshes in a few seconds)", Toast.LENGTH_SHORT).show()
    }
}

data class NativeTweet(
    val id: String,
    val authorName: String,
    val handle: String,
    val avatarUrl: String,
    val text: String,
    val mediaUrl: String?,
    val mediaUrls: List<String> = listOfNotNull(mediaUrl),
    val videoUrl: String? = null,
    val externalUrl: String? = null,
    val likes: Int,
    val retweets: Int,
    val replies: Int,
    val isVideo: Boolean,
    val isSensitive: Boolean = false,
    val spoilerText: String = "",
    val source: FeedSource = FeedSource.PUBLIC
)

enum class BitXTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Search", Icons.Filled.Search, Icons.Outlined.Search),
    SAVED("Saved", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder)
}

object SavedPostsStore {
    private const val PREF_KEY = "saved_posts_json"

    fun load(context: Context): List<NativeTweet> {
        val raw = context.getSharedPreferences("bitx_saved", Context.MODE_PRIVATE)
            .getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NativeTweet>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mediaArr = o.optJSONArray("mediaUrls")
                val urls = mutableListOf<String>()
                if (mediaArr != null) {
                    for (j in 0 until mediaArr.length()) urls.add(mediaArr.getString(j))
                }
                val src = try {
                    FeedSource.valueOf(o.optString("source", "PUBLIC"))
                } catch (_: Exception) { FeedSource.PUBLIC }
                list.add(
                    NativeTweet(
                        id = o.getString("id"),
                        authorName = o.getString("authorName"),
                        handle = o.getString("handle"),
                        avatarUrl = o.optString("avatarUrl", ""),
                        text = o.optString("text", ""),
                        mediaUrl = o.optString("mediaUrl").takeIf { it.isNotBlank() },
                        mediaUrls = urls,
                        videoUrl = o.optString("videoUrl").takeIf { it.isNotBlank() },
                        externalUrl = o.optString("externalUrl").takeIf { it.isNotBlank() },
                        likes = o.optInt("likes", 0),
                        retweets = o.optInt("retweets", 0),
                        replies = o.optInt("replies", 0),
                        isVideo = o.optBoolean("isVideo", false),
                        isSensitive = o.optBoolean("isSensitive", false),
                        spoilerText = o.optString("spoilerText", ""),
                        source = src
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, list: List<NativeTweet>) {
        val arr = JSONArray()
        list.forEach { t ->
            val o = JSONObject().apply {
                put("id", t.id)
                put("authorName", t.authorName)
                put("handle", t.handle)
                put("avatarUrl", t.avatarUrl)
                put("text", t.text)
                put("mediaUrl", t.mediaUrl ?: "")
                put("mediaUrls", JSONArray(t.mediaUrls))
                put("videoUrl", t.videoUrl ?: "")
                put("externalUrl", t.externalUrl ?: "")
                put("likes", t.likes)
                put("retweets", t.retweets)
                put("replies", t.replies)
                put("isVideo", t.isVideo)
                put("isSensitive", t.isSensitive)
                put("spoilerText", t.spoilerText)
                put("source", t.source.name)
            }
            arr.put(o)
        }
        context.getSharedPreferences("bitx_saved", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, arr.toString()).apply()
    }
}

object XApiClient {
    private val client = OkHttpClient()
    private const val BEARER = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
    private const val FEATURES = """{"rweb_tipjar_consumption_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_enhance_cards_enabled":false}"""
    private val queryIdCache = mutableMapOf<String, String>()

    fun clearCache() { queryIdCache.clear() }

    private fun resolveQueryIds(authToken: String, ct0: String) {
        if (queryIdCache.containsKey("HomeTimeline")) return
        val homeReq = Request.Builder()
            .url("https://x.com/home")
            .header("cookie", "auth_token=$authToken; ct0=$ct0")
            .header("user-agent", UA)
            .build()
        val html = client.newCall(homeReq).execute().use { it.body?.string().orEmpty() }
        val jsUrls = Regex("""https://abs\.twimg\.com/responsive-web/client-web[^"']+/main\.[^"']+\.js""")
            .findAll(html).map { it.value }.distinct().toList()
        val ops = listOf("HomeTimeline", "HomeLatestTimeline", "Bookmarks", "SearchTimeline")
        for (jsUrl in jsUrls) {
            val jsBody = client.newCall(Request.Builder().url(jsUrl).header("user-agent", UA).build())
                .execute().use { it.body?.string().orEmpty() }
            for (op in ops) {
                if (!queryIdCache.containsKey(op)) {
                    val match = Regex("""queryId:"([a-zA-Z0-9_-]+)",operationName:"$op"""").find(jsBody)
                    if (match != null) queryIdCache[op] = match.groupValues[1]
                }
            }
            if (queryIdCache.size >= 3) break
        }
    }

    suspend fun fetchFeed(authToken: String?, ct0: String?, tab: BitXTab, query: String = ""): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            if (tab == BitXTab.SAVED) return@withContext Result.success(emptyList())
            if (authToken.isNullOrBlank() || ct0.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Sign in to X (or paste cookies in Settings) to load your X feed."))
            }
            resolveQueryIds(authToken, ct0)
            val (opName, varsJson) = when (tab) {
                BitXTab.HOME -> {
                    val op = if (queryIdCache.containsKey("HomeTimeline")) "HomeTimeline" else "HomeLatestTimeline"
                    op to """{"count":30,"includePromotedContent":false,"latestControlAvailable":true}"""
                }
                BitXTab.SAVED -> "Bookmarks" to """{"count":30,"includePromotedContent":false}"""
                BitXTab.SEARCH -> {
                    val q = query.trim().replace("\"", "")
                    if (q.isBlank()) return@withContext Result.success(emptyList())
                    "SearchTimeline" to """{"rawQuery":"$q","count":25,"querySource":"typed_query","product":"Top"}"""
                }
            }
            val qId = queryIdCache[opName]
                ?: return@withContext Result.failure(Exception("Could not resolve live $opName endpoint from x.com"))
            val vars = URLEncoder.encode(varsJson, "UTF-8")
            val feat = URLEncoder.encode(FEATURES, "UTF-8")
            val req = Request.Builder()
                .url("https://x.com/i/api/graphql/$qId/$opName?variables=$vars&features=$feat")
                .header("authorization", BEARER)
                .header("x-csrf-token", ct0)
                .header("x-twitter-auth-type", "OAuth2Session")
                .header("x-twitter-active-user", "yes")
                .header("cookie", "auth_token=$authToken; ct0=$ct0")
                .header("user-agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    queryIdCache.clear()
                    return@withContext Result.failure(Exception("X HTTP ${resp.code}: ${body.take(100)}"))
                }
                Result.success(parseTweetsDeep(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTweetsDeep(jsonStr: String): List<NativeTweet> {
        val results = mutableListOf<NativeTweet>()
        val seenIds = mutableSetOf<String>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("legacy") && node.has("core")) {
                        try {
                            val legacy = node.getJSONObject("legacy")
                            val id = legacy.optString("id_str")
                            val rawText = legacy.optString("full_text")
                            if (id.isNotBlank() && rawText.isNotBlank() && seenIds.add(id)) {
                                val userLegacy = node.getJSONObject("core")
                                    .getJSONObject("user_results")
                                    .getJSONObject("result")
                                    .getJSONObject("legacy")
                                val mediaArr = legacy.optJSONObject("extended_entities")?.optJSONArray("media")
                                val mediaList = mutableListOf<String>()
                                var isVideo = false
                                var videoUrl: String? = null
                                if (mediaArr != null) {
                                    for (i in 0 until mediaArr.length()) {
                                        val m = mediaArr.optJSONObject(i) ?: continue
                                        val mUrl = m.optString("media_url_https")
                                        if (mUrl.isNotBlank()) mediaList.add(mUrl)
                                        if (m.optString("type") in listOf("video", "animated_gif")) {
                                            isVideo = true
                                            val variants = m.optJSONObject("video_info")?.optJSONArray("variants")
                                            if (variants != null) {
                                                var bestBitrate = -1
                                                for (v in 0 until variants.length()) {
                                                    val vo = variants.optJSONObject(v) ?: continue
                                                    if (vo.optString("content_type") == "video/mp4") {
                                                        val br = vo.optInt("bitrate", 0)
                                                        if (br >= bestBitrate) {
                                                            bestBitrate = br
                                                            videoUrl = vo.optString("url")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                val cleanText = if (mediaList.isNotEmpty() || videoUrl != null) {
                                    rawText.replace(Regex("https://t\\.co/\\S+"), "").trim()
                                } else rawText
                                results.add(
                                    NativeTweet(
                                        id = id,
                                        authorName = userLegacy.optString("name", "User"),
                                        handle = "@${userLegacy.optString("screen_name", "user")}",
                                        avatarUrl = userLegacy.optString("profile_image_url_https", "").replace("_normal", "_bigger"),
                                        text = cleanText,
                                        mediaUrl = mediaList.firstOrNull(),
                                        mediaUrls = mediaList,
                                        videoUrl = videoUrl,
                                        likes = legacy.optInt("favorite_count", 0),
                                        retweets = legacy.optInt("retweet_count", 0),
                                        replies = legacy.optInt("reply_count", 0),
                                        isVideo = isVideo,
                                        isSensitive = legacy.optBoolean("possibly_sensitive", false),
                                        source = FeedSource.X
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
        }
        walk(JSONObject(jsonStr))
        return results
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { BitXRoot() }
    }
}

@Composable
fun BitXTheme(style: AppStyle, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamicBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = Color(0xFFB4C5FF),
            primaryContainer = Color(0xFF28438A),
            onPrimaryContainer = Color(0xFFDBE1FF),
            secondaryContainer = Color(0xFF3E4559),
            tertiaryContainer = Color(0xFF5A3D59),
            surface = Color(0xFF111318)
        )
    }

    val targetScheme = when (style) {
        AppStyle.MATERIAL_YOU -> dynamicBase
        AppStyle.AMOLED -> dynamicBase.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF070709),
            surfaceContainer = Color(0xFF0B0B0F),
            surfaceContainerHigh = Color(0xFF121218)
        )
        AppStyle.LIQUID_GLASS -> dynamicBase.copy(
            background = Color(0xFF090D16),
            surface = Color(0xFF090D16),
            surfaceContainerLow = Color(0xFF141C2B),
            surfaceContainer = Color(0xFF172133),
            surfaceContainerHigh = Color(0xFF1E293B)
        )
        AppStyle.TIDAL -> darkColorScheme(
            primary = Color(0xFF00FFFF),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF003838),
            onPrimaryContainer = Color(0xFF9EFFFF),
            secondary = Color(0xFFE2E8F0),
            secondaryContainer = Color(0xFF1E1E1E),
            onSecondaryContainer = Color.White,
            background = Color(0xFF080808),
            surface = Color(0xFF080808),
            surfaceContainerLow = Color(0xFF111111),
            surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1C1C1C),
            surfaceContainerHighest = Color(0xFF262626),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFF9CA3AF)
        )
    }

    MaterialTheme(colorScheme = targetScheme, content = content)
}

@Composable
fun BitXRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bitx_auth", Context.MODE_PRIVATE) }

    var xAuthToken by remember { mutableStateOf(prefs.getString("auth_token", null)) }
    var xCt0 by remember { mutableStateOf(prefs.getString("ct0", null)) }
    var bskyJwt by remember { mutableStateOf(prefs.getString("bsky_jwt", null)) }
    var bskyHandle by remember { mutableStateOf(prefs.getString("bsky_handle", null)) }

    var appStyle by remember {
        mutableStateOf(
            try { AppStyle.valueOf(prefs.getString("app_style", "MATERIAL_YOU") ?: "MATERIAL_YOU") }
            catch (_: Exception) { AppStyle.MATERIAL_YOU }
        )
    }
    var activeIcon by remember {
        mutableStateOf(
            try { AppIconOption.valueOf(prefs.getString("app_icon", "CLASSIC") ?: "CLASSIC") }
            catch (_: Exception) { AppIconOption.CLASSIC }
        )
    }
    var allowNsfw by remember { mutableStateOf(prefs.getBoolean("allow_nsfw", false)) }
    var showXLogin by remember { mutableStateOf(false) }

    BitXTheme(style = appStyle) {
        AnimatedContent(
            targetState = showXLogin,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
            label = "root_nav"
        ) { inLogin ->
            if (inLogin) {
                BackHandler { showXLogin = false }
                XNativeAuthScreen(
                    onAuthenticated = { token, csrf ->
                        prefs.edit().putString("auth_token", token).putString("ct0", csrf).apply()
                        xAuthToken = token
                        xCt0 = csrf
                        showXLogin = false
                    },
                    onSkip = { showXLogin = false }
                )
            } else {
                NativeBitXApp(
                    xAuthToken = xAuthToken,
                    xCt0 = xCt0,
                    bskyJwt = bskyJwt,
                    bskyHandle = bskyHandle,
                    appStyle = appStyle,
                    activeIcon = activeIcon,
                    allowNsfw = allowNsfw,
                    onChangeStyle = { s ->
                        appStyle = s
                        prefs.edit().putString("app_style", s.name).apply()
                    },
                    onChangeIcon = { icon ->
                        activeIcon = icon
                        prefs.edit().putString("app_icon", icon.name).apply()
                        AppIconManager.setIcon(context, icon)
                    },
                    onToggleNsfw = { enabled ->
                        allowNsfw = enabled
                        prefs.edit().putBoolean("allow_nsfw", enabled).apply()
                    },
                    onOpenXLogin = { showXLogin = true },
                    onSaveManualXCookies = { token, csrf ->
                        prefs.edit().putString("auth_token", token).putString("ct0", csrf).apply()
                        xAuthToken = token
                        xCt0 = csrf
                        XApiClient.clearCache()
                    },
                    onLogoutX = {
                        prefs.edit().remove("auth_token").remove("ct0").apply()
                        CookieManager.getInstance().removeAllCookies(null)
                        XApiClient.clearCache()
                        xAuthToken = null
                        xCt0 = null
                    },
                    onSaveBskySession = { session ->
                        prefs.edit().putString("bsky_jwt", session.accessJwt).putString("bsky_handle", session.handle).apply()
                        bskyJwt = session.accessJwt
                        bskyHandle = session.handle
                    },
                    onLogoutBsky = {
                        prefs.edit().remove("bsky_jwt").remove("bsky_handle").apply()
                        bskyJwt = null
                        bskyHandle = null
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XNativeAuthScreen(onAuthenticated: (String, String) -> Unit, onSkip: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    fun checkCookies(): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookies = cm.getCookie("https://x.com") ?: return false
        val map = cookies.split(";").associate {
            val parts = it.trim().split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val token = map["auth_token"]
        val csrf = map["ct0"]
        if (!token.isNullOrBlank() && !csrf.isNullOrBlank()) {
            onAuthenticated(token, csrf)
            return true
        }
        return false
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            if (checkCookies()) break
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        Surface(color = colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onSkip) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onPrimaryContainer)
                    }
                    Column {
                        Text("Sign in to X", color = colors.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Returns to native feed once signed in", color = colors.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
                FilledTonalButton(onClick = { checkCookies() }) { Text("Done") }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { checkCookies() }
                    }
                    loadUrl("https://x.com/login")
                }
            }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun NativeVideoPlayer(
    videoUrl: String,
    autoPlay: Boolean,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFullscreenDialog by remember { mutableStateOf(false) }

    val exoPlayer = remember(videoUrl) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f
                prepare()
                playWhenReady = autoPlay
            }
    }

    LaunchedEffect(autoPlay, showFullscreenDialog) {
        exoPlayer.playWhenReady = autoPlay && !showFullscreenDialog
    }

    DisposableEffect(videoUrl) {
        onDispose { exoPlayer.release() }
    }

    if (showFullscreenDialog && !isFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreenDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                NativeVideoPlayer(videoUrl = videoUrl, autoPlay = true, isFullscreen = true, modifier = Modifier.fillMaxSize())
                FilledTonalIconButton(
                    onClick = { showFullscreenDialog = false },
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)
                ) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(if (isFullscreen) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = { scope.launch { MediaDownloader.saveToGallery(context, videoUrl, isVideo = true) } },
                modifier = Modifier.size(36.dp)
            ) { Icon(Icons.Filled.Download, contentDescription = "Save Video", modifier = Modifier.size(18.dp)) }
            if (!isFullscreen) {
                FilledTonalIconButton(
                    onClick = { showFullscreenDialog = true },
                    modifier = Modifier.size(36.dp)
                ) { Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen", modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
fun ZoomableImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1.2f) { scale = 1f; offset = Offset.Zero } else { scale = 2.8f }
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale > 1f) Offset(offset.x + pan.x, offset.y + pan.y) else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Zoomable Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalIconButton(onClick = { scope.launch { MediaDownloader.saveToGallery(context, imageUrl, isVideo = false) } }) {
                    Icon(Icons.Filled.Download, contentDescription = "Save Image")
                }
                FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        }
    }
}

@Composable
fun ExternalRedirectWarningDialog(url: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error) },
        title = { Text("Leaving bitX", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This post links to an external website. Open in your browser?")
                Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(url, color = colors.primary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Open Website") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
fun BlueskyLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: (BskySession) -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var handle by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = { Icon(Icons.Filled.Cloud, contentDescription = null, tint = colors.primary) },
        title = { Text("Sign in to Bluesky", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use your Bluesky handle (e.g. name.bsky.social) and password or App Password.", fontSize = 13.sp, color = colors.onSurfaceVariant)
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Handle or Email") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password / App Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(16.dp)
                )
                if (err != null) {
                    Text(err!!, color = colors.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && handle.isNotBlank() && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        loading = true
                        err = null
                        BlueskyClient.login(handle, password)
                            .onSuccess { onSuccess(it) }
                            .onFailure { err = it.message }
                        loading = false
                    }
                }
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Sign In")
            }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NativeBitXApp(
    xAuthToken: String?,
    xCt0: String?,
    bskyJwt: String?,
    bskyHandle: String?,
    appStyle: AppStyle,
    activeIcon: AppIconOption,
    allowNsfw: Boolean,
    onChangeStyle: (AppStyle) -> Unit,
    onChangeIcon: (AppIconOption) -> Unit,
    onToggleNsfw: (Boolean) -> Unit,
    onOpenXLogin: () -> Unit,
    onSaveManualXCookies: (String, String) -> Unit,
    onLogoutX: () -> Unit,
    onSaveBskySession: (BskySession) -> Unit,
    onLogoutBsky: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val isXLoggedIn = !xAuthToken.isNullOrBlank() && !xCt0.isNullOrBlank()
    val isBskyLoggedIn = !bskyJwt.isNullOrBlank()

    var activeSource by remember {
        mutableStateOf(
            when {
                isBskyLoggedIn -> FeedSource.BLUESKY
                isXLoggedIn -> FeedSource.X
                else -> FeedSource.PUBLIC
            }
        )
    }
    var selectedTab by remember { mutableStateOf(BitXTab.HOME) }
    var showSettings by remember { mutableStateOf(false) }
    var showBskyLoginDialog by remember { mutableStateOf(false) }
    var activeCommentsTweet by remember { mutableStateOf<NativeTweet?>(null) }
    var zoomedImageUrl by remember { mutableStateOf<String?>(null) }
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    var tweets by remember { mutableStateOf<List<NativeTweet>>(emptyList()) }
    var savedPosts by remember { mutableStateOf(SavedPostsStore.load(context)) }
    var likedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    var videosOnly by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Spinning animation for refresh button while loading
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        label = "spin"
    )

    fun toggleSavePost(tweet: NativeTweet) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val exists = savedPosts.any { it.id == tweet.id }
        val updated = if (exists) savedPosts.filterNot { it.id == tweet.id } else listOf(tweet) + savedPosts
        savedPosts = updated
        SavedPostsStore.save(context, updated)
    }

    fun loadTab(tab: BitXTab, q: String = searchQuery, forceFresh: Boolean = false) {
        activeJob?.cancel()
        if (tab == BitXTab.SAVED) {
            isLoading = false
            errorMsg = null
            return
        }
        if (tab == BitXTab.SEARCH && q.isBlank()) {
            isLoading = false
            errorMsg = null
            tweets = emptyList()
            return
        }
        activeJob = scope.launch {
            isLoading = true
            errorMsg = null
            val result = when (activeSource) {
                FeedSource.BLUESKY -> BlueskyClient.fetchFeed(bskyJwt, tab, q)
                FeedSource.X -> XApiClient.fetchFeed(xAuthToken, xCt0, tab, q)
                FeedSource.PUBLIC -> PublicGuestClient.fetchFeed(tab, q, forceFresh = forceFresh)
            }
            result
                .onSuccess { list ->
                    tweets = list
                    errorMsg = null
                }
                .onFailure { err ->
                    errorMsg = err.message
                }
            isLoading = false
        }
    }

    fun executeSearch() {
        val trimmed = searchQuery.trim()
        if (trimmed.isNotBlank()) {
            hasSearched = true
            keyboardController?.hide()
            loadTab(BitXTab.SEARCH, trimmed)
        }
    }

    LaunchedEffect(selectedTab, activeSource, isBskyLoggedIn, isXLoggedIn) {
        if (selectedTab == BitXTab.SEARCH) {
            if (hasSearched && searchQuery.isNotBlank()) loadTab(BitXTab.SEARCH, searchQuery)
            else { tweets = emptyList(); errorMsg = null; isLoading = false }
        } else if (selectedTab != BitXTab.SAVED) {
            loadTab(selectedTab)
        }
    }

    if (showBskyLoginDialog) {
        BlueskyLoginDialog(
            onDismiss = { showBskyLoginDialog = false },
            onSuccess = { session ->
                onSaveBskySession(session)
                showBskyLoginDialog = false
                activeSource = FeedSource.BLUESKY
            }
        )
    }

    if (zoomedImageUrl != null) {
        ZoomableImageDialog(imageUrl = zoomedImageUrl!!, onDismiss = { zoomedImageUrl = null })
    }

    if (pendingRedirectUrl != null) {
        val target = pendingRedirectUrl!!
        ExternalRedirectWarningDialog(
            url = target,
            onCancel = { pendingRedirectUrl = null },
            onConfirm = {
                pendingRedirectUrl = null
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) } catch (_: Exception) {}
            }
        )
    }

    if (activeCommentsTweet != null) {
        BackHandler { activeCommentsTweet = null }
        CommentsScreen(
            parentTweet = activeCommentsTweet!!,
            bskyJwt = bskyJwt,
            appStyle = appStyle,
            allowNsfw = allowNsfw,
            savedIds = savedPosts.map { it.id }.toSet(),
            likedIds = likedIds,
            onToggleLike = { id -> likedIds = if (id in likedIds) likedIds - id else likedIds + id },
            onToggleSave = ::toggleSavePost,
            onImageClick = { zoomedImageUrl = it },
            onExternalLinkClick = { pendingRedirectUrl = it },
            onBack = { activeCommentsTweet = null }
        )
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            isXLoggedIn = isXLoggedIn,
            isBskyLoggedIn = isBskyLoggedIn,
            bskyHandle = bskyHandle,
            appStyle = appStyle,
            activeIcon = activeIcon,
            allowNsfw = allowNsfw,
            onChangeStyle = onChangeStyle,
            onChangeIcon = onChangeIcon,
            onToggleNsfw = onToggleNsfw,
            onClearCache = {
                XApiClient.clearCache()
                showSettings = false
                loadTab(selectedTab, forceFresh = true)
            },
            onOpenXLogin = { showSettings = false; onOpenXLogin() },
            onSaveManualXCookies = { token, csrf -> onSaveManualXCookies(token, csrf); activeSource = FeedSource.X; showSettings = false },
            onLogoutX = onLogoutX,
            onOpenBskyLogin = { showBskyLoginDialog = true },
            onLogoutBsky = onLogoutBsky,
            onBack = { showSettings = false }
        )
        return
    }

    val savedIdSet = remember(savedPosts) { savedPosts.map { it.id }.toSet() }
    val bgBrush = when (appStyle) {
        AppStyle.LIQUID_GLASS -> Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF090D16), Color(0xFF05070C)))
        else -> Brush.verticalGradient(listOf(colors.surface, colors.surface))
    }

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sleek Header + Live Feed Switcher Pill
            Surface(
                color = if (appStyle == AppStyle.LIQUID_GLASS) colors.surfaceContainer.copy(alpha = 0.72f) else colors.surfaceContainer,
                tonalElevation = 4.dp,
                border = if (appStyle == AppStyle.LIQUID_GLASS) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = colors.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                                Text(
                                    "bitX",
                                    color = colors.onPrimaryContainer,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(selectedTab.label, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectedTab == BitXTab.HOME || (selectedTab == BitXTab.SEARCH && hasSearched)) {
                                FilledTonalIconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    loadTab(selectedTab, forceFresh = true)
                                }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Refresh",
                                        modifier = Modifier.rotate(if (isLoading) spinAngle else 0f)
                                    )
                                }
                            }
                            FilledTonalIconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    }

                    // Animated Feed Source Switcher (Public | Bluesky | X)
                    if (selectedTab != BitXTab.SAVED) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = colors.surfaceContainerHighest.copy(alpha = 0.7f),
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FeedSource.entries.forEach { src ->
                                    val selected = activeSource == src
                                    val bgColor by animateColorAsState(
                                        targetValue = if (selected) colors.primaryContainer else Color.Transparent,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "src_bg"
                                    )
                                    val textColor by animateColorAsState(
                                        targetValue = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                        label = "src_txt"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(CircleShape)
                                            .background(bgColor)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                activeSource = src
                                            }
                                            .padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(src.label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Search Bar (only on Search tab)
            AnimatedVisibility(visible = selectedTab == BitXTab.SEARCH) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search ${activeSource.label}...") },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                        trailingIcon = {
                            IconButton(onClick = { executeSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FilterChip(
                        selected = videosOnly,
                        onClick = { videosOnly = !videosOnly },
                        label = { Text("Direct Videos Only") },
                        leadingIcon = if (videosOnly) {
                            { Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }

            // Animated Tab Content
            AnimatedContent(
                targetState = Triple(selectedTab, activeSource, hasSearched),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 16 }) togetherWith fadeOut(tween(150))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "tab_content"
            ) { (tab, source, searched) ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        tab == BitXTab.SAVED -> {
                            if (savedPosts.isEmpty()) {
                                Column(
                                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("No saved posts yet", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    Text("Tap Save on any post to keep it here.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 108.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(savedPosts, key = { it.id }) { tweet ->
                                        MaterialYouTweetCard(
                                            tweet = tweet,
                                            appStyle = appStyle,
                                            allowNsfw = allowNsfw,
                                            isSaved = true,
                                            isLiked = tweet.id in likedIds,
                                            onToggleLike = { likedIds = if (tweet.id in likedIds) likedIds - tweet.id else likedIds + tweet.id },
                                            onToggleSave = { toggleSavePost(tweet) },
                                            onOpenComments = { activeCommentsTweet = tweet },
                                            onImageClick = { zoomedImageUrl = it },
                                            onExternalLinkClick = { pendingRedirectUrl = it }
                                        )
                                    }
                                }
                            }
                        }
                        tab == BitXTab.SEARCH && !searched -> {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                        isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = colors.primary)
                        }
                        errorMsg != null -> {
                            ElevatedCard(
                                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                            ) {
                                Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (source == FeedSource.BLUESKY && !isBskyLoggedIn) "Connect Bluesky"
                                        else if (source == FeedSource.X && !isXLoggedIn) "Connect X"
                                        else "Feed Notice",
                                        color = colors.onSurface,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(errorMsg!!, color = colors.onSurfaceVariant, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (source == FeedSource.BLUESKY && !isBskyLoggedIn) {
                                            Button(onClick = { showBskyLoginDialog = true }) { Text("Sign in to Bluesky") }
                                        } else if (source == FeedSource.X && !isXLoggedIn) {
                                            Button(onClick = onOpenXLogin) { Text("Sign in to X") }
                                        } else {
                                            Button(onClick = { loadTab(tab, forceFresh = true) }) { Text("Retry") }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            val displayed = if (tab == BitXTab.SEARCH && videosOnly) {
                                tweets.filter { !it.videoUrl.isNullOrBlank() }
                            } else tweets

                            if (displayed.isEmpty()) {
                                Text(
                                    text = if (videosOnly) "No direct video posts found for this search." else "No posts found.",
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 108.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(displayed, key = { "${source.name}_${it.id}" }) { tweet ->
                                        MaterialYouTweetCard(
                                            tweet = tweet,
                                            appStyle = appStyle,
                                            allowNsfw = allowNsfw,
                                            isSaved = savedIdSet.contains(tweet.id),
                                            isLiked = tweet.id in likedIds,
                                            onToggleLike = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                likedIds = if (tweet.id in likedIds) likedIds - tweet.id else likedIds + tweet.id
                                            },
                                            onToggleSave = { toggleSavePost(tweet) },
                                            onOpenComments = { activeCommentsTweet = tweet },
                                            onImageClick = { zoomedImageUrl = it },
                                            onExternalLinkClick = { pendingRedirectUrl = it }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Spring-Animated Pill Dock
        Surface(
            color = if (appStyle == AppStyle.LIQUID_GLASS) colors.surfaceContainerHigh.copy(alpha = 0.82f) else colors.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = if (appStyle == AppStyle.LIQUID_GLASS || appStyle == AppStyle.AMOLED) BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)) else null,
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BitXTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    val pillScale by animateFloatAsState(
                        targetValue = if (selected) 1.04f else 0.96f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        label = "dock_scale"
                    )
                    val bgColor by animateColorAsState(
                        targetValue = if (selected) colors.primaryContainer else Color.Transparent,
                        animationSpec = tween(220),
                        label = "dock_bg"
                    )
                    Row(
                        modifier = Modifier
                            .scale(pillScale)
                            .clip(CircleShape)
                            .background(bgColor)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = tab
                            }
                            .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label,
                            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        if (selected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tab.label, color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommentsScreen(
    parentTweet: NativeTweet,
    bskyJwt: String?,
    appStyle: AppStyle,
    allowNsfw: Boolean,
    savedIds: Set<String>,
    likedIds: Set<String>,
    onToggleLike: (String) -> Unit,
    onToggleSave: (NativeTweet) -> Unit,
    onImageClick: (String) -> Unit,
    onExternalLinkClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var replies by remember { mutableStateOf<List<NativeTweet>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(parentTweet.id) {
        isLoading = true
        errorMsg = null
        val res = when (parentTweet.source) {
            FeedSource.BLUESKY -> {
                if (!bskyJwt.isNullOrBlank()) BlueskyClient.fetchReplies(bskyJwt, parentTweet.id)
                else Result.failure(Exception("Sign in to Bluesky to load replies."))
            }
            FeedSource.PUBLIC -> PublicGuestClient.fetchReplies(parentTweet.id)
            FeedSource.X -> Result.success(emptyList())
        }
        res.onSuccess { replies = it }.onFailure { errorMsg = it.message }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Comments", color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                MaterialYouTweetCard(
                    tweet = parentTweet,
                    appStyle = appStyle,
                    allowNsfw = allowNsfw,
                    isSaved = savedIds.contains(parentTweet.id),
                    isLiked = parentTweet.id in likedIds,
                    onToggleLike = { onToggleLike(parentTweet.id) },
                    onToggleSave = { onToggleSave(parentTweet) },
                    onOpenComments = {},
                    onImageClick = onImageClick,
                    onExternalLinkClick = onExternalLinkClick
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("Replies (${replies.size})", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (isLoading) {
                item { Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.primary) } }
            } else if (errorMsg != null) {
                item { Text(errorMsg!!, color = colors.error, modifier = Modifier.padding(16.dp)) }
            } else if (replies.isEmpty()) {
                item { Text("No comments yet.", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            } else {
                items(replies, key = { it.id }) { reply ->
                    MaterialYouTweetCard(
                        tweet = reply,
                        appStyle = appStyle,
                        allowNsfw = allowNsfw,
                        isSaved = savedIds.contains(reply.id),
                        isLiked = reply.id in likedIds,
                        onToggleLike = { onToggleLike(reply.id) },
                        onToggleSave = { onToggleSave(reply) },
                        onOpenComments = {},
                        onImageClick = onImageClick,
                        onExternalLinkClick = onExternalLinkClick
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    isXLoggedIn: Boolean,
    isBskyLoggedIn: Boolean,
    bskyHandle: String?,
    appStyle: AppStyle,
    activeIcon: AppIconOption,
    allowNsfw: Boolean,
    onChangeStyle: (AppStyle) -> Unit,
    onChangeIcon: (AppIconOption) -> Unit,
    onToggleNsfw: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onOpenXLogin: () -> Unit,
    onSaveManualXCookies: (String, String) -> Unit,
    onLogoutX: () -> Unit,
    onOpenBskyLogin: () -> Unit,
    onLogoutBsky: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "3.0.0" }
        catch (_: Exception) { "3.0.0" }
    }
    var updateStatus by remember { mutableStateOf("Tap to check GitHub Releases") }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var manualAuthToken by remember { mutableStateOf("") }
    var manualCt0 by remember { mutableStateOf("") }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Paste X Session Cookies", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Copy auth_token and ct0 from x.com in Chrome DevTools to bypass login rate limits.", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    OutlinedTextField(value = manualAuthToken, onValueChange = { manualAuthToken = it.trim() }, label = { Text("auth_token") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(value = manualCt0, onValueChange = { manualCt0 = it.trim() }, label = { Text("ct0") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                }
            },
            confirmButton = {
                Button(enabled = manualAuthToken.isNotBlank() && manualCt0.isNotBlank(), onClick = {
                    showCookieDialog = false
                    onSaveManualXCookies(manualAuthToken, manualCt0)
                }) { Text("Save & Connect") }
            },
            dismissButton = { TextButton(onClick = { showCookieDialog = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Settings", color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Styles Selector
            Text("App Style", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AppStyle.entries) { style ->
                    val selected = appStyle == style
                    ElevatedCard(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerLow
                        ),
                        modifier = Modifier.width(160.dp).clickable { onChangeStyle(style) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    style.title,
                                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                style.subtitle,
                                color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            // App Icon Selector
            Text("App Icon", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AppIconOption.entries) { iconOpt ->
                    val selected = activeIcon == iconOpt
                    ElevatedCard(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerLow
                        ),
                        modifier = Modifier.width(126.dp).clickable { onChangeIcon(iconOpt) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(iconOpt.bgHex))
                                    .border(2.dp, Color(iconOpt.accentHex), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("b", color = Color(iconOpt.accentHex), fontWeight = FontWeight.Black, fontSize = 24.sp)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                iconOpt.title,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Content Toggle
            Text("Content", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggleNsfw(!allowNsfw) }.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show NSFW / Sensitive Media", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("Always unhide sensitive posts and videos", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Switch(checked = allowNsfw, onCheckedChange = onToggleNsfw)
                }
            }

            // Accounts (Bluesky & X)
            Text("Accounts (Bluesky & X)", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Bluesky row
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { if (isBskyLoggedIn) onLogoutBsky() else onOpenBskyLogin() }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isBskyLoggedIn) "Bluesky Connected (@$bskyHandle)" else "Sign in to Bluesky",
                                color = if (isBskyLoggedIn) colors.primary else colors.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                if (isBskyLoggedIn) "Tap to sign out of Bluesky" else "Full native feed, search, replies & videos",
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Icon(if (isBskyLoggedIn) Icons.AutoMirrored.Filled.Logout else Icons.Filled.Cloud, contentDescription = null, tint = colors.primary)
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 18.dp))

                    // X Login row
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { if (isXLoggedIn) onLogoutX() else onOpenXLogin() }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isXLoggedIn) "X Account Connected" else "Sign in to X",
                                color = if (isXLoggedIn) colors.primary else colors.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                if (isXLoggedIn) "Tap to sign out of X" else "Web auth flow for X timeline",
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Icon(if (isXLoggedIn) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login, contentDescription = null, tint = colors.secondary)
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showCookieDialog = true }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paste X Session Cookies", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Import auth_token & ct0 to bypass login limits", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.Key, contentDescription = null, tint = colors.primary)
                    }
                }
            }

            // Updates & Engine
            Text("Updates & Engine", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !checkingUpdate) {
                                scope.launch {
                                    checkingUpdate = true
                                    updateStatus = "Checking github.com/cashhwocky/bitX..."
                                    val info = UpdateManager.checkForUpdate(currentVersion)
                                    if (info != null) {
                                        availableUpdate = info
                                        updateStatus = "Update v${info.versionTag} available!"
                                    } else {
                                        updateStatus = "You're on the latest build (v$currentVersion)"
                                    }
                                    checkingUpdate = false
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Check for Updates", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(updateStatus, color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                        if (checkingUpdate) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        else Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = colors.primary)
                    }

                    if (availableUpdate != null) {
                        Button(
                            onClick = { scope.launch { updateStatus = "Downloading APK..."; UpdateManager.downloadAndInstall(context, availableUpdate!!.apkUrl) } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Download & Install v${availableUpdate!!.versionTag}") }
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onClearCache() }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Refresh GraphQL Endpoints", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Clears cached X query IDs", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Icon(Icons.Filled.Cached, contentDescription = null, tint = colors.secondary)
                    }
                }
            }

            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = colors.primaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("bitX", color = colors.onPrimaryContainer, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("Version $currentVersion - Built by Alex Desrosier (@cashhwocky)", color = colors.onPrimaryContainer.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun MaterialYouTweetCard(
    tweet: NativeTweet,
    appStyle: AppStyle,
    allowNsfw: Boolean,
    isSaved: Boolean,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onToggleSave: () -> Unit,
    onOpenComments: () -> Unit,
    onImageClick: (String) -> Unit,
    onExternalLinkClick: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var manuallyRevealed by remember(tweet.id) { mutableStateOf(false) }
    val showMedia = !tweet.isSensitive || allowNsfw || manuallyRevealed

    val cardBg = when (appStyle) {
        AppStyle.LIQUID_GLASS -> colors.surfaceContainerLow.copy(alpha = 0.68f)
        else -> colors.surfaceContainerLow
    }
    val cardBorder = when (appStyle) {
        AppStyle.LIQUID_GLASS -> BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        AppStyle.AMOLED -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.16f))
        AppStyle.TIDAL -> BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        AppStyle.MATERIAL_YOU -> null
    }
    val cardShape = if (appStyle == AppStyle.TIDAL) RoundedCornerShape(16.dp) else RoundedCornerShape(26.dp)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            .clickable { onOpenComments() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = tweet.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, colors.primary.copy(alpha = 0.45f), CircleShape)
                        .background(colors.secondaryContainer)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tweet.authorName, color = colors.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tweet.handle, color = colors.primary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (tweet.isSensitive) {
                    Surface(color = colors.errorContainer, shape = CircleShape) {
                        Text("NSFW", color = colors.onErrorContainer, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }

            if (tweet.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(tweet.text, color = colors.onSurface, fontSize = 15.sp, lineHeight = 22.sp)
            }

            if (!tweet.videoUrl.isNullOrBlank() || tweet.mediaUrls.isNotEmpty() || !tweet.externalUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                if (!showMedia) {
                    Surface(
                        color = colors.surfaceContainerHighest,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().clickable { manuallyRevealed = true }
                    ) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = colors.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(tweet.spoilerText.ifBlank { "Sensitive / NSFW Media" }, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Tap to reveal (or enable NSFW in Settings)", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                } else if (!tweet.videoUrl.isNullOrBlank()) {
                    NativeVideoPlayer(
                        videoUrl = tweet.videoUrl,
                        autoPlay = true,
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp))
                    )
                } else if (!tweet.externalUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp)).clickable { onExternalLinkClick(tweet.externalUrl) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (tweet.mediaUrls.isNotEmpty()) {
                            AsyncImage(model = tweet.mediaUrls.first(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainerHighest))
                        }
                        Surface(color = colors.secondaryContainer.copy(alpha = 0.92f), shape = CircleShape, shadowElevation = 6.dp) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "External Link", tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("External Link (Tap to Review)", color = colors.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (tweet.mediaUrls.size == 1) {
                    val imgUrl = tweet.mediaUrls.first()
                    AsyncImage(
                        model = imgUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).clip(RoundedCornerShape(18.dp)).clickable { onImageClick(imgUrl) }
                    )
                } else if (tweet.mediaUrls.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(tweet.mediaUrls) { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(260.dp).height(220.dp).clip(RoundedCornerShape(18.dp)).clickable { onImageClick(url) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatPill(Icons.Outlined.ChatBubbleOutline, tweet.replies.toString(), onClick = onOpenComments)
                StatPill(Icons.Outlined.Repeat, tweet.retweets.toString())
                StatPill(
                    icon = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    label = (tweet.likes + if (isLiked) 1 else 0).toString(),
                    highlighted = isLiked,
                    onClick = onToggleLike
                )
                StatPill(
                    icon = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    label = if (isSaved) "Saved" else "Save",
                    highlighted = isSaved,
                    onClick = onToggleSave
                )
            }
        }
    }
}

@Composable
fun StatPill(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pill_bounce"
    )
    val bg by animateColorAsState(
        targetValue = if (highlighted) colors.primaryContainer else colors.surfaceContainerHighest,
        label = "pill_bg"
    )
    val fg by animateColorAsState(
        targetValue = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
        label = "pill_fg"
    )
    Surface(
        color = bg,
        shape = CircleShape,
        modifier = Modifier
            .scale(scale)
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable { onClick() } else Modifier)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
