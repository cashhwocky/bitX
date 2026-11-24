package com.cashhwocky.bitx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
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
import kotlinx.coroutines.delay
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
    private const val BEARER = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

    private const val FEATURES = """{"rweb_tipjar_consumption_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_enhance_cards_enabled":false}"""

    private val queryIdCache = mutableMapOf<String, String>()

    fun clearCache() {
        queryIdCache.clear()
    }

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
                    if (match != null) {
                        queryIdCache[op] = match.groupValues[1]
                    }
                }
            }
            if (queryIdCache.size >= 3) break
        }
    }

    suspend fun fetchFeed(
        authToken: String,
        ct0: String,
        tab: BitXTab,
        query: String = ""
    ): Result<List<NativeTweet>> = withContext(Dispatchers.IO) {
        try {
            resolveQueryIds(authToken, ct0)

            val (opName, varsJson) = when (tab) {
                BitXTab.HOME, BitXTab.CLIPS -> {
                    val op = if (queryIdCache.containsKey("HomeTimeline")) "HomeTimeline" else "HomeLatestTimeline"
                    op to """{"count":30,"includePromotedContent":false,"latestControlAvailable":true}"""
                }
                BitXTab.SAVED -> "Bookmarks" to """{"count":30,"includePromotedContent":false}"""
                BitXTab.SEARCH -> {
                    val q = query.ifBlank { "Android OR Pixel" }.replace("\"", "")
                    "SearchTimeline" to """{"rawQuery":"$q","count":25,"querySource":"typed_query","product":"Top"}"""
                }
            }

            val qId = queryIdCache[opName]
                ?: return@withContext Result.failure(Exception("Could not resolve live $opName endpoint from x.com"))

            val vars = URLEncoder.encode(varsJson, "UTF-8")
            val feat = URLEncoder.encode(FEATURES, "UTF-8")
            val url = "https://x.com/i/api/graphql/$qId/$opName?variables=$vars&features=$feat"

            val req = Request.Builder()
                .url(url)
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
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${body.take(120)}"))
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
            BitXRoot()
        }
    }
}

@Composable
fun BitXMaterialYouTheme(amoledBlack: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val baseScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    val finalScheme = if (amoledBlack) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF0F0F0F)
        )
    } else baseScheme

    MaterialTheme(colorScheme = finalScheme, content = content)
}

@Composable
fun BitXRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bitx_auth", Context.MODE_PRIVATE) }
    var authToken by remember { mutableStateOf(prefs.getString("auth_token", null)) }
    var ct0 by remember { mutableStateOf(prefs.getString("ct0", null)) }
    var amoledBlack by remember { mutableStateOf(prefs.getBoolean("amoled_black", false)) }
    var showLoginScreen by remember { mutableStateOf(false) }

    BitXMaterialYouTheme(amoledBlack = amoledBlack) {
        if (showLoginScreen) {
            BackHandler { showLoginScreen = false }
            XNativeAuthScreen(
                onAuthenticated = { token, csrf ->
                    prefs.edit().putString("auth_token", token).putString("ct0", csrf).apply()
                    authToken = token
                    ct0 = csrf
                    showLoginScreen = false
                },
                onSkip = { showLoginScreen = false }
            )
        } else {
            NativeBitXApp(
                authToken = authToken,
                ct0 = ct0,
                amoledBlack = amoledBlack,
                onToggleAmoled = { enabled ->
                    amoledBlack = enabled
                    prefs.edit().putBoolean("amoled_black", enabled).apply()
                },
                onOpenLogin = { showLoginScreen = true },
                onSaveManualCookies = { token, csrf ->
                    prefs.edit().putString("auth_token", token).putString("ct0", csrf).apply()
                    authToken = token
                    ct0 = csrf
                    XApiClient.clearCache()
                },
                onLogout = {
                    prefs.edit().remove("auth_token").remove("ct0").apply()
                    CookieManager.getInstance().removeAllCookies(null)
                    XApiClient.clearCache()
                    authToken = null
                    ct0 = null
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XNativeAuthScreen(
    onAuthenticated: (String, String) -> Unit,
    onSkip: () -> Unit
) {
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
        Surface(
            color = colors.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onSkip) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onPrimaryContainer)
                    }
                    Column {
                        Text("Sign in to X", color = colors.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Optional - returns to native feed automatically", color = colors.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
                FilledTonalButton(onClick = { checkCookies() }) {
                    Text("Done")
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
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
                        override fun onPageFinished(view: WebView?, url: String?) {
                            checkCookies()
                        }
                    }
                    loadUrl("https://x.com/login")
                }
            }
        )
    }
}

@Composable
fun NativeBitXApp(
    authToken: String?,
    ct0: String?,
    amoledBlack: Boolean,
    onToggleAmoled: (Boolean) -> Unit,
    onOpenLogin: () -> Unit,
    onSaveManualCookies: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isLoggedIn = !authToken.isNullOrBlank() && !ct0.isNullOrBlank()
    var selectedTab by remember { mutableStateOf(BitXTab.HOME) }
    var showSettings by remember { mutableStateOf(false) }
    var tweets by remember { mutableStateOf<List<NativeTweet>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    fun loadTab(tab: BitXTab, q: String = searchQuery) {
        scope.launch {
            isLoading = true
            errorMsg = null
            val result = if (isLoggedIn) {
                XApiClient.fetchFeed(authToken!!, ct0!!, tab, q)
            } else {
                PublicGuestClient.fetchFeed(tab, q)
            }
            result
                .onSuccess { tweets = it }
                .onFailure { errorMsg = it.message }
            isLoading = false
        }
    }

    LaunchedEffect(selectedTab, isLoggedIn) { loadTab(selectedTab) }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            isLoggedIn = isLoggedIn,
            amoledBlack = amoledBlack,
            onToggleAmoled = onToggleAmoled,
            onClearCache = {
                XApiClient.clearCache()
                showSettings = false
                loadTab(selectedTab)
            },
            onOpenLogin = {
                showSettings = false
                onOpenLogin()
            },
            onSaveManualCookies = { token, csrf ->
                onSaveManualCookies(token, csrf)
                showSettings = false
            },
            onLogout = onLogout,
            onBack = { showSettings = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        if (isLoggedIn) {
                            FilledTonalIconButton(onClick = { loadTab(selectedTab) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                        } else {
                            FilledTonalButton(onClick = onOpenLogin) {
                                Text("Sign In", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        FilledTonalIconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                }
            }

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
fun GuestModePlaceholder(
    tab: BitXTab,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().padding(bottom = 72.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = colors.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tab.selectedIcon,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Guest Mode ? ${tab.label}",
                    color = colors.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "X requires an account session to load live feeds. Sign in whenever you're ready, or paste your browser cookies in Settings to skip the login rate limit.",
                    color = colors.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onSignIn,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in to X")
                }
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Paste Cookies in Settings")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    isLoggedIn: Boolean,
    amoledBlack: Boolean,
    onToggleAmoled: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onOpenLogin: () -> Unit,
    onSaveManualCookies: (String, String) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.0.0"
        } catch (_: Exception) { "2.0.0" }
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
                    Text(
                        "Copy auth_token and ct0 from x.com in Chrome DevTools (Application -> Cookies) to bypass login rate limits.",
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualAuthToken,
                        onValueChange = { manualAuthToken = it.trim() },
                        label = { Text("auth_token") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = manualCt0,
                        onValueChange = { manualCt0 = it.trim() },
                        label = { Text("ct0") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = manualAuthToken.isNotBlank() && manualCt0.isNotBlank(),
                    onClick = {
                        showCookieDialog = false
                        onSaveManualCookies(manualAuthToken, manualCt0)
                    }
                ) {
                    Text("Save & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) {
                    Text("Cancel")
                }
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Settings", color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Appearance", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAmoled(!amoledBlack) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pure AMOLED Black", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("True #000000 canvas with dynamic Material You accents", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Switch(checked = amoledBlack, onCheckedChange = onToggleAmoled)
                }
            }

            Text("Updates & Engine", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)
            ) {
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
                        if (checkingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        } else {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = colors.primary)
                        }
                    }

                    if (availableUpdate != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    updateStatus = "Downloading APK..."
                                    UpdateManager.downloadAndInstall(context, availableUpdate!!.apkUrl)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Download & Install v${availableUpdate!!.versionTag}")
                        }
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 18.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClearCache() }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Refresh GraphQL Endpoints", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Clears cached X query IDs and re-fetches live hashes", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Icon(Icons.Filled.Cached, contentDescription = null, tint = colors.secondary)
                    }
                }
            }

            Text("Account", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCookieDialog = true }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paste Session Cookies", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Import auth_token & ct0 to bypass login rate limits", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Icon(Icons.Filled.Key, contentDescription = null, tint = colors.primary)
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 18.dp))

                    if (isLoggedIn) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLogout() }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Sign Out", color = colors.error, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("Clears local session cookies", color = colors.onSurfaceVariant, fontSize = 13.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = colors.error)
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLogin() }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Sign In to X", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("Optional - log in via X web flow", color = colors.onSurfaceVariant, fontSize = 13.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, tint = colors.primary)
                        }
                    }
                }
            }

            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.primaryContainer)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("bitX", color = colors.onPrimaryContainer, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("Version $currentVersion ? Built by Alex Desrosier (@cashhwocky)", color = colors.onPrimaryContainer.copy(alpha = 0.85f), fontSize = 13.sp)
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

