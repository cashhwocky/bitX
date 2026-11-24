package com.cashhwocky.bitx

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

enum class BitXTab(
    val label: String,
    val url: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", "https://x.com/home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Search", "https://x.com/explore", Icons.Filled.Search, Icons.Outlined.Search),
    SAVED("Saved", "https://x.com/i/bookmarks", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    CLIPS("Clips", "https://x.com/i/immersive_timeline", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircleOutline)
}

fun Color.toHex(): String = String.format("#%06X", 0xFFFFFF and this.toArgb())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            BitXMaterialYouTheme {
                BitXApp()
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
            onPrimary = Color(0xFF002A77),
            primaryContainer = Color(0xFF28438A),
            onPrimaryContainer = Color(0xFFDBE1FF),
            secondaryContainer = Color(0xFF3E4559),
            onSecondaryContainer = Color(0xFFDCE2F9),
            tertiaryContainer = Color(0xFF5A3D59),
            onTertiaryContainer = Color(0xFFFFD6FA),
            background = Color(0xFF111318),
            surface = Color(0xFF111318)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BitXApp() {
    val colors = MaterialTheme.colorScheme
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tabs = BitXTab.entries

    val bgHex = colors.surface.toHex()
    val cardHex = colors.surfaceContainer.toHex()
    val primaryHex = colors.primary.toHex()
    val onPrimaryHex = colors.onPrimary.toHex()
    val outlineHex = colors.outlineVariant.toHex()

    // Injects live Material You palette into X + removes duplicate web bottom bar
    val materialYouCssScript = remember(bgHex, primaryHex) {
        """
        (function() {
            const id = 'bitx-m3-theme';
            let style = document.getElementById(id);
            if (!style) {
                style = document.createElement('style');
                style.id = id;
                document.head.appendChild(style);
            }
            style.innerHTML = `
                html, body, #react-root, main, [data-testid="primaryColumn"] {
                    background-color: $bgHex !important;
                }
                [data-testid="BottomBar"], nav[aria-label="Primary"] {
                    display: none !important;
                }
                article[data-testid="tweet"] {
                    background-color: $cardHex !important;
                    border-radius: 20px !important;
                    margin: 6px 10px !important;
                    border: 1px solid $outlineHex !important;
                }
                [data-testid="SideNav_NewTweet_Button"],
                [data-testid="tweetButtonInline"],
                [data-testid="tweetButton"] {
                    background-color: $primaryHex !important;
                    color: $onPrimaryHex !important;
                }
                a[role="tab"][aria-selected="true"] div {
                    color: $primaryHex !important;
                }
                body { padding-bottom: 96px !important; }
            `;
        })();
        """.trimIndent()
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tonal Material You Top Pill Bar
            Surface(
                color = colors.surfaceContainer,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = colors.primaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "bitX",
                                color = colors.onPrimaryContainer,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = tabs[selectedTabIndex].label,
                            color = colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }

                    FilledTonalIconButton(
                        onClick = { webViewRef?.reload() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            // Material You Progress Indicator
            AnimatedVisibility(
                visible = loadProgress > 0f && loadProgress < 1f,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    progress = { loadProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest
                )
            }

            // WebView Engine
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(colors.surface.toArgb())
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress / 100f
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(materialYouCssScript, null)
                            }
                        }

                        loadUrl(tabs[0].url)
                        webViewRef = this
                    }
                }
            )
        }

        // Expressive Material You Compose FAB
        FloatingActionButton(
            onClick = { webViewRef?.loadUrl("https://x.com/compose/post") },
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 20.dp, bottom = 92.dp)
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "New Post")
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTabIndex == index
                    val pillColor = if (selected) colors.primaryContainer else Color.Transparent
                    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pillColor)
                            .clickable {
                                selectedTabIndex = index
                                webViewRef?.loadUrl(tab.url)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                        if (selected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.label,
                                color = contentColor,
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
