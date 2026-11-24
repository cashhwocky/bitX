package com.cashhwocky.bitx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class NativeTweet(
    val id: String,
    val authorName: String,
    val handle: String,
    val avatarUrl: String,
    val text: String,
    val mediaUrl: String?,
    val likes: Int,
    val retweets: Int,
    val replies: Int,
    val isVideo: Boolean
)

enum class BitXTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Search", Icons.Filled.Search, Icons.Outlined.Search),
    SAVED("Saved", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    CLIPS("Clips", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircleOutline)
}

object XApiClient {
    private val client = OkHttpClient()
    // Public X web bearer token used by x.com
    private const val BEARER = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

    private const val FEATURES = """{"rweb_tipjar_consumption_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_enhance_cards_enabled":false}"""

    suspend fun fetchFeed(
        authToken: String,
        ct0: String,
        tab: BitXTab,
        query: String = ""
    ): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            val url = when (tab) {
                BitXTab.HOME, BitXTab.CLIPS -> {
                    val vars = URLEncoder.encode("""{"count":30,"includePromotedContent":false,"latestControlAvailable":true}""", "UTF-8")
                    val feat = URLEncoder.encode(FEATURES, "UTF-8")
                    "https://x.com/i/api/graphql/W4Tvz1PbNkbyboX4wJqEfA/HomeTimeline?variables=$vars&features=$feat"
                }
                BitXTab.SAVED -> {
                    val vars = URLEncoder.encode("""{"count":30,"includePromotedContent":false}""", "UTF-8")
                    val feat = URLEncoder.encode(FEATURES, "UTF-8")
                    "https://x.com/i/api/graphql/xLjCVOEWOFflkcrGOWPczw/Bookmarks?variables=$vars&features=$feat"
                }
                BitXTab.SEARCH -> {
                    val q = query.ifBlank { "Android OR Pixel" }
                    val vars = URLEncoder.encode("""{"rawQuery":"$q","count":25,"querySource":"typed_query","product":"Top"}""", "UTF-8")
                    val feat = URLEncoder.encode(FEATURES, "UTF-8")
                    "https://x.com/i/api/graphql/gkjsKepM6gl_HmFWoWKfgg/SearchTimeline?variables=$vars&features=$feat"
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("authorization", BEARER)
                .header("x-csrf-token", ct0)
                .header("x-twitter-auth-type", "OAuth2Session")
                .header("x-twitter-active-user", "yes")
                .header("cookie", "auth_token=$authToken; ct0=$ct0")
                .header("user-agent", "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: X rotated endpoint or session expired"))
                }
                val parsed = parseTweetsDeep(body)
                val finalTweets = if (tab == BitXTab.CLIPS) {
                    parsed.filter { it.mediaUrl != null }
                } else {
                    parsed
                }
                Result.success(finalTweets)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Recursively extracts tweet_results objects from any X GraphQL response structure
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
                            val text = legacy.optString("full_text")
                            if (id.isNotBlank() && text.isNotBlank() && seenIds.add(id)) {
                                val userLegacy = node.getJSONObject("core")
                                    .getJSONObject("user_results")
                                    .getJSONObject("result")
                                    .getJSONObject("legacy")
                                val mediaArr = legacy.optJSONObject("extended_entities")?.optJSONArray("media")
                                val firstMedia = mediaArr?.optJSONObject(0)
                                val mediaUrl = firstMedia?.optString("media_url_https")?.takeIf { it.isNotBlank() }
                                val isVideo = firstMedia?.optString("type") in listOf("video", "animated_gif")

                                results.add(
                                    NativeTweet(
                                        id = id,
                                        authorName = userLegacy.optString("name", "User"),
                                        handle = "@${userLegacy.optString("screen_name", "user")}",
                                        avatarUrl = userLegacy.optString("profile_image_url_https", "").replace("_normal", "_bigger"),
                                        text = text,
                                        mediaUrl = mediaUrl,
                                        likes = legacy.optInt("favorite_count", 0),
                                        retweets = legacy.optInt("retweet_count", 0),
                                        replies = legacy.optInt("reply_count", 0),
                                        isVideo = isVideo
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is org.json.JSONArray -> {
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
        setContent {
            BitXMaterialYouTheme {
                BitXRoot()
            }
        }
    }
}

@Composable
fun BitXMaterialYouTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun BitXRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bitx_auth", Context.MODE_PRIVATE) }
    var authToken by remember { mutableStateOf(prefs.getString("auth_token", null)) }
    var ct0 by remember { mutableStateOf(prefs.getString("ct0", null)) }

    if (authToken.isNullOrBlank() || ct0.isNullOrBlank()) {
        XNativeAuthScreen(
            onAuthenticated = { token, csrf ->
                prefs.edit().putString("auth_token", token).putString("ct0", csrf).apply()
                authToken = token
                ct0 = csrf
            }
        )
    } else {
        NativeBitXApp(
            authToken = authToken!!,
            ct0 = ct0!!,
            onLogout = {
                prefs.edit().clear().apply()
                CookieManager.getInstance().removeAllCookies(null)
                authToken = null
                ct0 = null
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XNativeAuthScreen(onAuthenticated: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Surface(
            color = colors.primaryContainer,
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Sign in to bitX", color = colors.onPrimaryContainer, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("One-time login to grab your X session. Once signed in, the app switches to 100% native Material You.", color = colors.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val cookies = CookieManager.getInstance().getCookie("https://x.com") ?: return
                            val map = cookies.split(";").associate {
                                val parts = it.trim().split("=", limit = 2)
                                parts[0] to (parts.getOrNull(1) ?: "")
                            }
                            val token = map["auth_token"]
                            val csrf = map["ct0"]
                            if (!token.isNullOrBlank() && !csrf.isNullOrBlank()) {
                                onAuthenticated(token, csrf)
                            }
                        }
                    }
                    loadUrl("https://x.com/i/flow/login")
                }
            }
        )
    }
}

@Composable
fun NativeBitXApp(authToken: String, ct0: String, onLogout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(BitXTab.HOME) }
    var tweets by remember { mutableStateOf<List<NativeTweet>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    fun loadTab(tab: BitXTab, q: String = searchQuery) {
        scope.launch {
            isLoading = true
            errorMsg = null
            XApiClient.fetchFeed(authToken, ct0, tab, q)
                .onSuccess { tweets = it }
                .onFailure { errorMsg = it.message }
            isLoading = false
        }
    }

    LaunchedEffect(selectedTab) { loadTab(selectedTab) }

    Box(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Heavy Material You Top Bar
            Surface(
                color = colors.surfaceContainer,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = colors.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Text(
                                "bitX",
                                color = colors.onPrimaryContainer,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(selectedTab.label, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { loadTab(selectedTab) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        FilledTonalIconButton(onClick = onLogout) {
                            Icon(Icons.Filled.Logout, contentDescription = "Log out")
                        }
                    }
                }
            }

            // Native Search Bar on Search Tab
            if (selectedTab == BitXTab.SEARCH) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search X natively...") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    trailingIcon = {
                        IconButton(onClick = { loadTab(BitXTab.SEARCH, searchQuery) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Go")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            // Main Native Content Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = colors.primary
                        )
                    }
                    errorMsg != null -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Feed Error", color = colors.onErrorContainer, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(errorMsg!!, color = colors.onErrorContainer, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(onClick = { loadTab(selectedTab) }) { Text("Retry") }
                            }
                        }
                    }
                    selectedTab == BitXTab.CLIPS -> {
                        if (tweets.isEmpty()) {
                            Text("No media clips in timeline yet.", color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                        } else {
                            val pagerState = rememberPagerState(pageCount = { tweets.size })
                            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                val clip = tweets[page]
                                Box(modifier = Modifier.fillMaxSize().padding(bottom = 90.dp)) {
                                    AsyncImage(
                                        model = clip.mediaUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
                                    )
                                    Surface(
                                        color = colors.surfaceContainerHigh.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text("${clip.authorName} ${clip.handle}", color = colors.primary, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(clip.text, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(tweets, key = { it.id }) { tweet ->
                                MaterialYouTweetCard(tweet)
                            }
                        }
                    }
                }
            }
        }

        // Floating Material You Pill Navigation Bar
        Surface(
            color = colors.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BitXTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.primaryContainer else Color.Transparent)
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                            Text(
                                tab.label,
                                color = colors.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MaterialYouTweetCard(tweet: NativeTweet) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = tweet.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(colors.secondaryContainer)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(tweet.authorName, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(tweet.handle, color = colors.primary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(tweet.text, color = colors.onSurface, fontSize = 15.sp, lineHeight = 21.sp)

            if (!tweet.mediaUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                AsyncImage(
                    model = tweet.mediaUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatPill(Icons.Outlined.ChatBubbleOutline, tweet.replies)
                StatPill(Icons.Outlined.Repeat, tweet.retweets)
                StatPill(Icons.Outlined.FavoriteBorder, tweet.likes)
            }
        }
    }
}

@Composable
fun StatPill(icon: ImageVector, count: Int) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHighest, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(count.toString(), color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
