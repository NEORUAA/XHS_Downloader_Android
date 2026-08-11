package com.neoruaa.xhsdn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.miuixBackdropSource
import com.neoruaa.xhsdn.ui.rememberMiuixTopBarBackdrop
import com.neoruaa.xhsdn.ui.rememberWindowLayoutInfo
import com.neoruaa.xhsdn.ui.navigation.AppRoute
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.ui.res.stringResource
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.squircle.squircleSurface

class WebViewActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(lightScrim = android.graphics.Color.TRANSPARENT, darkScrim = android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(lightScrim = android.graphics.Color.TRANSPARENT, darkScrim = android.graphics.Color.TRANSPARENT)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !isNightMode

        val initialUrl = intent?.getStringExtra("url")
        val taskId = intent?.getLongExtra("task_id", -1L) ?: -1L

        setContent {
            val controller = ThemeController(ColorSchemeMode.System)
            val localInitialUrl = initialUrl // Capture the variable in the composition scope
            val activity = this@WebViewActivity
            MiuixTheme(controller = controller) {
                WebViewScreen(
                    initialUrl = localInitialUrl,
                    onBack = { finish() },
                    onResult = { urls, content, taskId ->
                        val resultIntent = Intent().apply {
                            putStringArrayListExtra("image_urls", ArrayList(urls))
                            if (content.isNotEmpty()) {
                                putExtra("content_text", content)
                            }
                            putExtra("url", localInitialUrl ?: "")
                            taskId?.let { id -> putExtra("task_id", id) }
                        }
                        activity.setResult(RESULT_OK, resultIntent)
                        activity.finish()
                    }
                )
            }
        }
    }
}

@Composable
internal fun WebViewRoute(
    route: AppRoute.WebView,
    onBack: () -> Unit,
    onResult: (List<String>, String, Long?, String) -> Unit
) {
    WebViewScreen(
        initialUrl = route.url,
        onBack = onBack,
        onResult = { urls, content, taskId ->
            onResult(urls, content, taskId, route.url.orEmpty())
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewScreen(
    initialUrl: String?,
    onBack: () -> Unit,
    onResult: (List<String>, String, Long?) -> Unit
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf(TextFieldValue(initialUrl ?: "")) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    val topBarState = rememberTopAppBarState()
    val scrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior(state = topBarState)
    val windowLayoutInfo = rememberWindowLayoutInfo()
    val topBarBackdrop = rememberMiuixTopBarBackdrop()

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 允许混合内容（HTTP和HTTPS）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            // 允许明文内容
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccessFromFileURLs = true
            setInitialScale(80)
        }
    }
    
    // Set to store sniffed video URLs
    val sniffedVideoUrls = remember { mutableSetOf<String>() }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.webview_title),
                isWideScreen = windowLayoutInfo.isWideScreen,
                backdrop = topBarBackdrop,
                navigationIcon = {
                    TopAppBarIconButton(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back_content_description),
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 4.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .miuixBackdropSource(topBarBackdrop)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .background(MiuixTheme.colorScheme.surface)
                .padding(top = padding.calculateTopPadding())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = windowLayoutInfo.contentStartPadding + 12.dp,
                        top = 12.dp,
                        end = windowLayoutInfo.contentEndPadding + 12.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.webview_enter_url),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { loadUrl(webView, urlText.text) })
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { loadUrl(webView, urlText.text) },
                        modifier = Modifier.weight(1f),
                        enabled = urlText.text.isNotBlank(),
                    ) {
                        Text(
                            text = stringResource(R.string.webview_go)
                        )
                    }
                    Button(
                        onClick = {
                            extractImages(context, webView, sniffedVideoUrls, onResult)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !loading,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(
                            text = stringResource(R.string.webview_crawl),
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        start = windowLayoutInfo.contentStartPadding + 12.dp,
                        end = windowLayoutInfo.contentEndPadding + 12.dp,
                        bottom = 12.dp
                    )
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.background,
                        cornerRadius = 18.dp
                    )
            ) {


                if (loading) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                } else {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { webView.apply { layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) } },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(ContinuousRoundedRectangle(18.dp)),

                        update = { }
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                url?.let { urlText = TextFieldValue(it) }
                // Clear sniffed URLs on new page load
                sniffedVideoUrls.clear()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request?.url?.scheme == "http" || request?.url?.scheme == "https") {
                    view?.let { loadUrl(it, request.url.toString()) }
                } else {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                url?.let {
                    // Sniff XHS video URLs
                    if ((it.contains("sns-video") && it.contains("xhscdn.com")) || 
                        it.endsWith(".mp4") || 
                        it.contains("masterUrl")) {
                        
                        // Avoid duplicates
                        if (!sniffedVideoUrls.contains(it)) {
                            sniffedVideoUrls.add(it)
                            android.util.Log.d("WebViewActivity", "Sniffed video URL: $it")
                        }
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
        
        if (!initialUrl.isNullOrBlank()) {
            loadUrl(webView, initialUrl)
        } else {
            webView.loadUrl("about:blank")
        }
        onDispose { }
    }
}

private fun loadUrl(webView: WebView, raw: String) {
    var url = raw.trim()
    if (url.isEmpty()) return
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "https://$url"
    }
    webView.loadUrl(url)
}

private fun applyDefaultZoom(webView: WebView) {
    val targetScale = 0.8f
    webView.post {
        runCatching { webView.setInitialScale((targetScale * 100).toInt()) }
        // 通过放大 viewport 宽度来实现 50% 视觉缩放，同时保持内容铺满
        val js = """
            (function() {
                try {
                    var scale = $targetScale;
                    var width = Math.floor(window.innerWidth / scale);
                    var meta = document.querySelector('meta[name="viewport"]');
                    var content = 'width=' + width + ', initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no';
                    if (meta) {
                        meta.setAttribute('content', content);
                    } else {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = content;
                        document.head.appendChild(meta);
                    }
                    // 清理之前的 transform/zoom 以防冲突
                    var reset = function(el) {
                        el.style.transform = '';
                        el.style.transformOrigin = '';
                        el.style.width = '';
                        el.style.height = '';
                        el.style.zoom = '';
                        el.style.margin = '';
                        el.style.padding = '';
                    };
                    reset(document.documentElement);
                    reset(document.body);
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}

private fun extractImages(
    context: android.content.Context,
    webView: WebView,
    sniffedUrls: Set<String>,
    onResult: (List<String>, String, Long?) -> Unit
) {
    webView.postDelayed({
        val jsCode = readAssetFile(context, "xhs_extractor.js") ?: run {
            Toast.makeText(context, context.getString(R.string.no_urls_found_javascript_null), Toast.LENGTH_SHORT).show()
            return@postDelayed
        }
        webView.evaluateJavascript(jsCode) { result ->
            try {
                if (result == null || result == "null" || result.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.no_urls_found_javascript_null), Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }
                var cleanResult = result
                if (cleanResult.startsWith("\"") && cleanResult.endsWith("\"")) {
                    cleanResult = cleanResult.substring(1, cleanResult.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                }
                val json = org.json.JSONObject(cleanResult)
                val urlsArray = json.getJSONArray("urls")
                val contentObj = json.optJSONObject("content")
                val contentText = contentObj?.optString("content", "") ?: ""

                val allUrls = mutableListOf<String>()
                for (i in 0 until urlsArray.length()) {
                    val url = urlsArray.getString(i)
                    if (url.isNullOrEmpty()) continue
                    if (url.startsWith("http") && !url.startsWith("blob:") && !url.startsWith("data:")) {
                        allUrls.add(url)
                    }
                }

                // Removed clipboard copy logic as per user request

                // Merge extracted URLs with sniffed URLs
                allUrls.addAll(sniffedUrls)

                if (allUrls.isNotEmpty()) {
                    // Create a task for the web crawl
                    val taskId = com.neoruaa.xhsdn.data.tasks.TaskManager.createTask(
                        noteUrl = webView.url ?: "",
                        noteTitle = webView.title ?: "",
                        noteType = com.neoruaa.xhsdn.data.NoteType.UNKNOWN,
                        totalFiles = allUrls.size,
                        noteContent = contentText // Include the content that was copied to clipboard
                    )

                    // Update the task status to DOWNLOADING immediately
                    com.neoruaa.xhsdn.data.tasks.TaskManager.updateTaskStatus(taskId, com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING)

                    // Debug: Show that URLs were found and task was created
//                    Toast.makeText(context, "找到${allUrls.size}个URL, 任务ID: $taskId", Toast.LENGTH_SHORT).show()
                    onResult(allUrls, contentText, taskId)
                } else {
                    Toast.makeText(context, context.getString(R.string.no_accessible_urls_found), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.error_parsing_urls, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }, 10)
}

private fun readAssetFile(context: android.content.Context, fileName: String): String? {
    return try {
        context.assets.open(fileName).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }
}
