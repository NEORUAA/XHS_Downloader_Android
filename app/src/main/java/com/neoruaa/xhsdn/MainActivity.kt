package com.neoruaa.xhsdn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import com.neoruaa.xhsdn.utils.UrlUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import com.neoruaa.xhsdn.utils.detectMediaType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import android.util.Size
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import java.io.File
import android.util.LruCache
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalConfiguration
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.TabRowDefaults
import com.neoruaa.xhsdn.ui.TabRowWithContour
import com.neoruaa.xhsdn.ui.SelectableMediaPreview
import com.neoruaa.xhsdn.ui.rememberWindowLayoutInfo
import com.neoruaa.xhsdn.viewmodels.MainUiState
import com.neoruaa.xhsdn.viewmodels.MainViewModel
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import com.neoruaa.xhsdn.viewmodels.SelectiveDownloadPhase
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.icon.extended.More
import androidx.compose.ui.res.stringResource
import android.util.Log
import androidx.compose.ui.text.font.FontWeight
import com.kyant.capsule.ContinuousRoundedRectangle
import com.neoruaa.xhsdn.ui.rememberOffsetPopupPositionProvider
import kotlinx.coroutines.awaitCancellation
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleSurface

// 缩略图内存缓存（最多缓存 50 张缩略图）
private val thumbnailCache = object : LruCache<String, ImageBitmap>(50) {}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val _autoDownloadIntentUrl = mutableStateOf<String?>(null)
    private var context: Context =  this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _autoDownloadIntentUrl.value = intent.getStringExtra("auto_download_url")
        intent.removeExtra("auto_download_url")

        if (Build.VERSION.SDK_INT >= 33) { // Android 13
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 200)
            }
        }

        enableEdgeToEdge()
        com.neoruaa.xhsdn.data.TaskManager.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 防止自动锁屏
        val prefs = getSharedPreferences("XHSDownloaderPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("keep_screen_on", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContent {
            val controller = ThemeController(ColorSchemeMode.System)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val topBarState = rememberTopAppBarState()
            val scrollBehavior = MiuixScrollBehavior(state = topBarState)
            
            // 处理自动下载
            val autoUrl by _autoDownloadIntentUrl
            LaunchedEffect(autoUrl) {
                autoUrl?.let { url ->
                     if (url.isNotEmpty()) {
                        viewModel.updateUrl(url)
                        ensureStoragePermission { 
                            val selectiveDownload = getSharedPreferences("XHSDownloaderPrefs", MODE_PRIVATE)
                                .getBoolean("selective_download", false)
                            if (selectiveDownload) {
                                viewModel.startSelectiveDownload { showToast(it) }
                            } else {
                                viewModel.startDownload { showToast(it) }
                            }
                        }
                     }
                     _autoDownloadIntentUrl.value = null // 消费完毕
                }
            }
            
            // 剪贴板检测相关状态
            context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("XHSDownloaderPrefs", MODE_PRIVATE) }
            var detectedXhsLink by remember { mutableStateOf<String?>(null) }
            var manualInputLinks by remember { mutableStateOf(prefs.getBoolean("manual_input_links", false)) }
            var selectiveDownload by remember { mutableStateOf(prefs.getBoolean("selective_download", false)) }

            // 监听SharedPreferences变化，确保UI能够实时响应设置更改
            LaunchedEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "manual_input_links") {
                        manualInputLinks = prefs.getBoolean("manual_input_links", false)
                    } else if (key == "selective_download") {
                        selectiveDownload = prefs.getBoolean("selective_download", false)
                    } else if (key == "keep_screen_on") {
                        if (prefs.getBoolean("keep_screen_on", false)) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)

                // 清理监听器
                try {
                    awaitCancellation()
                } finally {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
            
            // 监听生命周期 ON_RESUME 和 ON_PAUSE 进行剪贴板监听器管理
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

            // 提取核心检测逻辑为可复用函数
            fun checkClipboard() {
                // 1. Refresh Preferences
                val currentAutoRead = prefs.getBoolean("auto_read_clipboard", false)
                val currentShowBubble = prefs.getBoolean("show_clipboard_bubble", true)

                Log.d("XHS_Debug", "checkClipboard: AutoRead=$currentAutoRead, ShowBubble=$currentShowBubble, ManualInput=$manualInputLinks")

                // 2. Access Clipboard
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (clipboard.hasPrimaryClip()) {
                    val clipData = clipboard.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val clipText = clipData.getItemAt(0).text?.toString() ?: ""
                        Log.d("XHS_Debug", "ClipText: $clipText")
                        
                        val url = UrlUtils.extractFirstUrl(clipText)
                        Log.d("XHS_Debug", "Extracted URL: $url")
                        
                        if (url != null && UrlUtils.isXhsLink(url)) {
                            // 3. Logic Branching
                            
                            if (currentAutoRead) {
                                // A. Auto Download Priority
                                viewModel.updateUrl(clipText)
                                
                                // ... (Auto download logic)
                                Log.d("XHS_Debug", "Triggering Auto Download")
                                
                                // Trigger Download
                                if (selectiveDownload) {
                                    viewModel.startSelectiveDownload { showToast(it) }
                                } else {
                                    viewModel.startDownload { showToast(it) }
                                }
                                
                                // Show Notification with Full Content
                                com.neoruaa.xhsdn.utils.NotificationHelper.showDownloadNotification(
                                    context,
                                    System.currentTimeMillis().toInt(),
                                    getString(R.string.preparing_download),
                                    clipText, // Full content
                                    false
                                )
                                
                                // Clear Clipboard
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                
                                // Ensure bubble is dismissed
                                detectedXhsLink = null
                                
                            } else if (currentShowBubble) {
                                // B. Show Bubble
                                Log.d("XHS_Debug", "Showing Bubble")
                                detectedXhsLink = clipText 
                            } else {
                                Log.d("XHS_Debug", "Bubble disabled in settings")
                            }
                        } else {
                            // Link invalid or not detected -> Disappear
                            Log.d("XHS_Debug", "Not XHS link or null -> Hide Bubble")
                            detectedXhsLink = null
                        }
                    } else {
                        // Clipboard empty -> Disappear
                        Log.d("XHS_Debug", "Clipboard empty/null data -> Hide Bubble")
                        detectedXhsLink = null
                    }
                } else {
                    // No clipboard -> Disappear
                    Log.d("XHS_Debug", "No Primary Clip -> Hide Bubble")
                    detectedXhsLink = null
                }
            }
            
            val scope = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycleScope
            
            val clipboardListener = remember {
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                     // 延迟检测，解决 listener 触发时 ClipData 可能尚未准备好的问题
                     scope.launch {
                         kotlinx.coroutines.delay(300) // 300ms 延迟
                         checkClipboard()
                     }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        // 注册监听器
                        val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.addPrimaryClipChangedListener(clipboardListener)
                        // 延迟检测：Android 10+ 需要等待窗口焦点才能访问剪贴板
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            checkClipboard()
                        }
                    } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                        // 移除监听器
                        val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.removePrimaryClipChangedListener(clipboardListener)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.removePrimaryClipChangedListener(clipboardListener)
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MiuixTheme(controller = controller) {
                // 手动输入链接对话框状态
                var showInputDialog by remember { mutableStateOf(false) }

                MainScreen(
                    uiState = uiState,
                    manualInputLinks = manualInputLinks,
                    showInputDialog = showInputDialog,
                    onShowInputDialogChange = { showInputDialog = it },
                    scrollBehavior = scrollBehavior,
                    onDownload = {
                        if (!manualInputLinks) {
                            ensureStoragePermission {
                                // 先读取剪贴板
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                // 提取有效链接
                                val url = UrlUtils.extractFirstUrl(clipText)
                                if (UrlUtils.isXhsLink(url)) {
                                    viewModel.updateUrl(clipText)

                                    if (selectiveDownload) {
                                        viewModel.startSelectiveDownload { showToast(it) }
                                    } else {
                                        // 先开始下载（创建任务）
                                        viewModel.startDownload { showToast(it) }

                                        // 然后获取笔记文案并保存到刚创建的任务中
                                        viewModel.copyDescription(
                                            onResult = { _ ->
                                                // 文案已保存到任务中
                                            },
                                            onError = { _ ->
                                                // 即使获取文案失败，也不影响下载
                                            }
                                        )
                                    }
                                } else {
                                    showToast(getString(R.string.clipboard_no_xhs_link))
                                }
                            }
                        }
                    },
                    onCopyText = { 
                        // 先读取剪贴板
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clipText.isNotEmpty()) {
                            viewModel.updateUrl(clipText)
                        }
                        ensureStoragePermission { viewModel.copyDescription({ showToast(getString(R.string.copied_description)) }, { showToast(it) }) } 
                    },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onWebCrawlFromClipboard = {
                        // 先读取剪贴板
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clipText.isNotEmpty()) {
                            // Clean the URL using the same method as other places
                            val cleanUrl = UrlUtils.extractFirstUrl(clipText)
                            if (cleanUrl != null) {
                                val webViewIntent = Intent(this, WebViewActivity::class.java).apply {
                                    putExtra("url", cleanUrl)
                                    // Don't pass task_id here - let WebViewActivity create the task when user clicks "爬取"
                                }
                                startActivityForResult(webViewIntent, WEBVIEW_REQUEST_CODE)

                                detectedXhsLink = null
                            } else {
                                showToast(getString(R.string.invalid_link_please_reenter))
                            }
                        }
                    },
                    onMediaClick = { openFile(it) },
                    onCopyUrl = { url ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xhs_url", url))
                        showToast(getString(R.string.link_copied))
                    },
                    onBrowseUrl = { url ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                try {
                                    // 使用通用URL提取
                                    val cleanUrl = UrlUtils.extractFirstUrl(url)
                                    if (cleanUrl != null) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
                                        startActivity(intent)
                                    } else {
                                        showToast(getString(R.string.no_valid_link_found))
                                    }
                                } catch (e: Exception) {
                                    showToast(getString(R.string.unable_to_open_browser, e.message))
                                }
                            }
                        }
                    },
                    onRetryTask = { task ->
                        ensureStoragePermission {
                            viewModel.retryTask(task) { showToast(it) }
                        }
                    },
                    onDeleteTask = { task ->
                        com.neoruaa.xhsdn.data.TaskManager.deleteTask(task.id)
                    },
                    onContinueTask = { task -> 
                        viewModel.continueTask(task)
                    },
                    onWebCrawlTask = { task ->
                        viewModel.updateUrl(task.noteUrl)
                        launchWebView(task.noteUrl, task.id)
                    },
                    onStopTask = { task ->
                         if (viewModel.currentTaskId == task.id) {
                             viewModel.cancelCurrentDownload()
                         }
                         com.neoruaa.xhsdn.data.BackgroundDownloadManager.stopTask(this, task.id)
                    },
                    onClearHistory = { viewModel.clearHistory() },
                    onManualInputDownload = { inputLink ->
                        ensureStoragePermission {
                            viewModel.updateUrl(inputLink)
                            if (selectiveDownload) {
                                viewModel.startSelectiveDownload { showToast(it) }
                            } else {
                                viewModel.startDownload { showToast(it) }

                                // 获取笔记文案
                                viewModel.copyDescription(
                                    onResult = { _ ->
                                        // 文案已保存到任务中
                                    },
                                    onError = { _ ->
                                        // 即使获取文案失败，也不影响下载
                                    }
                                )
                            }
                        }
                    },
                    detectedXhsLink = detectedXhsLink,
                    onDismissPrompt = { detectedXhsLink = null },
                    onCancelSelectiveDownload = viewModel::cancelSelectiveDownload,
                    onSaveSelectedMedia = { viewModel.saveSelectedMedia { showToast(it) } },
                    onToggleSelectiveItem = viewModel::toggleSelectiveItem
                )

                // 检测到"重试同一链接但解析数量不一致"时，提示是否导出诊断日志
                val inconsistentRetry = uiState.inconsistentRetry
                if (inconsistentRetry.show) {
                    WindowDialog(
                        title = stringResource(R.string.retry_inconsistent_dialog_title),
                        summary = stringResource(
                            R.string.retry_inconsistent_dialog_message,
                            inconsistentRetry.previousCount,
                            inconsistentRetry.currentCount
                        ),
                        show = true,
                        onDismissRequest = { viewModel.dismissInconsistentRetryDialog() }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            TextButton(
                                text = stringResource(R.string.cancel),
                                onClick = { viewModel.dismissInconsistentRetryDialog() },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                text = stringResource(R.string.retry_inconsistent_save_button),
                                onClick = {
                                    viewModel.saveInconsistentRetryLogs(
                                        onResult = { showToast(it) },
                                        onError = { showToast(it) }
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }
            }
        }
    }


    private fun launchWebView(input: String, taskId: Long? = null) {
        val cleanUrl = UrlUtils.extractFirstUrl(input)
        if (cleanUrl == null) {
            showToast(getString(R.string.invalid_link_please_reenter))
            return
        }
        viewModel.resetWebCrawlFlag()
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra("url", cleanUrl)
        if (taskId != null && taskId > 0) {
            intent.putExtra("task_id", taskId)
        }
        startActivityForResult(intent, WEBVIEW_REQUEST_CODE)
    }



    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("auto_download_url")?.let {
            _autoDownloadIntentUrl.value = it
            intent.removeExtra("auto_download_url")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // 供旧 Java 下载逻辑回调调用，提示用户切换到网页模式
    fun showWebCrawlOption() {
        runOnUiThread {
            viewModel.notifyWebCrawlSuggestion()
        }
    }

    private fun ensureStoragePermission(onReady: () -> Unit) {
        if (hasStoragePermission()) {
            onReady()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            showToast(getString(R.string.grant_all_files_access_retry))
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showToast(getString(R.string.storage_permission_granted_continue))
            } else {
                showToast(getString(R.string.storage_permission_missing_unable_save))
            }
        }
    }

    private fun openFile(item: MediaItem) {
        val file = File(item.path)
        if (!file.exists()) {
            showToast(getString(R.string.file_does_not_exist, item.path))
            return
        }
        val mimeType = when (item.type) {
            MediaType.VIDEO -> "video/*"
            MediaType.IMAGE -> "image/*"
            MediaType.OTHER -> "*/*"
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kotlin.runCatching { startActivity(intent) }.onFailure {
            showToast(getString(R.string.unable_to_open_file_error, it.message))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WEBVIEW_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Debug: Show that we received the result
//            showToast("收到WebView结果")

            val urls = data.getStringArrayListExtra("image_urls") ?: emptyList()
            val content = data.getStringExtra("content_text")
            val taskId = data.getLongExtra("task_id", -1L).takeIf { it > 0 }

            if (urls.isNotEmpty()) {
                // Debug: Show how many URLs were received
//                showToast("收到${urls.size}个URL")

                // Check if a task ID was passed from WebViewActivity (meaning task was already created)
                val taskToUse = if (taskId != null) {
                    // Task was already created in WebViewActivity
                    taskId
                } else {
                    // Create a new task when URLs are returned from WebViewActivity
                    val webViewUrl = data.getStringExtra("url") ?: "Unknown URL"
                    val newTaskId = com.neoruaa.xhsdn.data.TaskManager.createTask(
                        noteUrl = webViewUrl,
                        noteTitle = null,
                        noteType = com.neoruaa.xhsdn.data.NoteType.UNKNOWN,
                        totalFiles = urls.size
                    )

                    // Update the task status to DOWNLOADING immediately since we have the URLs
                    com.neoruaa.xhsdn.data.TaskManager.updateTaskStatus(newTaskId, com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING)

                    // Debug: Show that task was created
//                    showToast("已创建任务ID: $newTaskId")
                    newTaskId
                }

//                showToast("开始爬取，请等待任务完成")
//                showToast("准备调用viewModel.onWebCrawlResult，URL数量: ${urls.size}")
                viewModel.onWebCrawlResult(urls, content, taskToUse)
            } else {
                showToast(getString(R.string.no_accessible_urls_found))
            }
        } else {
            // Debug: Show that result was not as expected
            if (requestCode == WEBVIEW_REQUEST_CODE) {
//                showToast("WebView返回结果异常: resultCode=$resultCode")
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 3001
        const val WEBVIEW_REQUEST_CODE = 3002
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    uiState: MainUiState,
    manualInputLinks: Boolean = false,
    showInputDialog: Boolean = false,
    onShowInputDialogChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCopyText: () -> Unit,
    onOpenSettings: () -> Unit,
    onWebCrawlFromClipboard: () -> Unit,
    onClearHistory: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onCopyUrl: (String) -> Unit,
    onBrowseUrl: (String) -> Unit,
    onRetryTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onStopTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onDeleteTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onContinueTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onWebCrawlTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onManualInputDownload: (String) -> Unit,
    scrollBehavior: ScrollBehavior,
    detectedXhsLink: String?,
    onDismissPrompt: () -> Unit,
    onCancelSelectiveDownload: () -> Unit,
    onSaveSelectedMedia: () -> Unit,
    onToggleSelectiveItem: (String) -> Unit
) {
    val windowLayoutInfo = rememberWindowLayoutInfo()
    val statusListState = rememberLazyListState()

    // 清除历史记录确认对话框状态
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showClearHistoryDialog) {
            WindowDialog(
                title = stringResource(R.string.clear_history_dialog_title),
                summary = stringResource(R.string.clear_history_dialog_message),
                show = true,
                onDismissRequest = { showClearHistoryDialog = false }
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showClearHistoryDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(R.string.apply),
                        onClick = {
                            onClearHistory()
                            showClearHistoryDialog = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
            topBar = {
                val title = stringResource(R.string.app_full_name)
                AdaptiveTopAppBar(
                    title = title,
                    isWideScreen = windowLayoutInfo.isWideScreen,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Box(
                            modifier = Modifier.padding(end = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TopAppBarIconButton(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = stringResource(R.string.settings),
                                onClick = onOpenSettings
                            )
                        }
                    }
                )
            }
        ) { padding ->
            HistoryPage(
                uiState = uiState,
                manualInputLinks = manualInputLinks,
                showInputDialog = showInputDialog,
                onShowInputDialogChange = onShowInputDialogChange,
                statusListState = statusListState,
                onDownload = onDownload,
                onManualInputDownload = onManualInputDownload,
                onCopyText = onCopyText,
                onWebCrawlFromClipboard = onWebCrawlFromClipboard,
                onRequestClearHistory = { showClearHistoryDialog = true },
                onMediaClick = onMediaClick,
                onCopyUrl = onCopyUrl,
                onBrowseUrl = onBrowseUrl,
                onRetryTask = onRetryTask,
                onContinueTask = onContinueTask,
                onWebCrawlTask = onWebCrawlTask,
                onStopTask = onStopTask,
                onDeleteTask = onDeleteTask,
                detectedXhsLink = detectedXhsLink,
                onDismissPrompt = onDismissPrompt,
                contentStartPadding = windowLayoutInfo.contentStartPadding,
                contentEndPadding = windowLayoutInfo.contentEndPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                nestedScrollConnection = scrollBehavior.nestedScrollConnection
            )
        }

        SelectiveDownloadSheet(
            uiState = uiState,
            onCancel = onCancelSelectiveDownload,
            onSave = onSaveSelectedMedia,
            onToggleItem = onToggleSelectiveItem
        )
    }
}

@Composable
private fun SelectiveDownloadSheet(
    uiState: MainUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onToggleItem: (String) -> Unit
) {
    val selectiveState = uiState.selectiveDownload
    val canSave = selectiveState.phase == SelectiveDownloadPhase.Ready &&
        selectiveState.selectedPaths.isNotEmpty()
    val unknownProgress = stringResource(R.string.selective_download_unknown_progress)

    WindowBottomSheet(
        show = selectiveState.show,
        title = stringResource(R.string.selective_download),
        allowDismiss = false,
        onDismissRequest = {},
        backgroundColor = MiuixTheme.colorScheme.surface,
        startAction = {
            TopAppBarIconButton(
                imageVector = MiuixIcons.Close,
                contentDescription = stringResource(R.string.cancel),
                onClick = onCancel
            )
        },
        endAction = {
            TopAppBarIconButton(
                imageVector = MiuixIcons.Download,
                contentDescription = stringResource(R.string.download_button),
                onClick = onSave,
                enabled = canSave
            )
        }
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                    alignment = Alignment.TopCenter
                )
                .heightIn(max = 560.dp),
            //contentPadding = PaddingValues(horizontal = 12.dp), 这里不需要这个
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(
                key = "selective_download_status",
                span = StaggeredGridItemSpan.FullLine
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when (selectiveState.phase) {
                            SelectiveDownloadPhase.Caching -> stringResource(R.string.selective_download_caching)
                            SelectiveDownloadPhase.Ready -> stringResource(
                                R.string.selective_download_ready,
                                selectiveState.selectedPaths.size,
                                selectiveState.items.size
                            )
                            SelectiveDownloadPhase.Saving -> stringResource(R.string.selective_download_saving)
                            SelectiveDownloadPhase.Error -> selectiveState.errorMessage ?: stringResource(R.string.selective_download_error)
                            SelectiveDownloadPhase.Idle -> ""
                        },
                        fontWeight = FontWeight.Medium
                    )
                    if (selectiveState.phase == SelectiveDownloadPhase.Caching ||
                        selectiveState.phase == SelectiveDownloadPhase.Saving
                    ) {
                        LinearProgressIndicator(
                            progress = selectiveState.progress.coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = selectiveState.progressLabel.ifBlank { unknownProgress },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Text(
                                text = selectiveState.progressText,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
//                    if (selectiveState.status.isNotBlank() &&
//                        selectiveState.phase != SelectiveDownloadPhase.Ready
//                    ) {
//                        Text(
//                            text = selectiveState.status,
//                            fontSize = 12.sp,
//                            color = Color.Gray,
//                            maxLines = 2,
//                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
//                        )
//                    }
                }
            }

            if (selectiveState.phase == SelectiveDownloadPhase.Ready && selectiveState.items.isNotEmpty()) {
                items(
                    items = selectiveState.items,
                    key = { it.path }
                ) { item ->
                    SelectableMediaPreview(
                        item = item,
                        selected = item.path in selectiveState.selectedPaths,
                        onToggle = { onToggleItem(item.path) }
                    )
                }
            }

            item(
                key = "selective_download_bottom_spacer",
                span = StaggeredGridItemSpan.FullLine
            ) {
                Spacer(
                    modifier = Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun HistoryPage(
    uiState: MainUiState,
    manualInputLinks: Boolean = false,
    showInputDialog: Boolean = false,
    onShowInputDialogChange: (Boolean) -> Unit,
    statusListState: androidx.compose.foundation.lazy.LazyListState,
    onDownload: () -> Unit,
    onManualInputDownload: (String) -> Unit,
    onCopyText: () -> Unit,
    onWebCrawlFromClipboard: () -> Unit,
    onRequestClearHistory: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onCopyUrl: (String) -> Unit,
    onBrowseUrl: (String) -> Unit,

    onRetryTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onContinueTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onWebCrawlTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onStopTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onDeleteTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    detectedXhsLink: String?,
    onDismissPrompt: () -> Unit,
    contentStartPadding: androidx.compose.ui.unit.Dp,
    contentEndPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null
) {
    val tasks by com.neoruaa.xhsdn.data.TaskManager.getAllTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val activeTask = tasks.firstOrNull {
        it.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING || it.status == com.neoruaa.xhsdn.data.TaskStatus.QUEUED
    }
    val menuItems = listOf(
        stringResource(R.string.copy_description),
        stringResource(R.string.web_crawl_option),
        stringResource(R.string.clear_history)
    )
    var menuExpanded by remember { mutableStateOf(false) }

    var taskToDelete by remember { mutableStateOf<com.neoruaa.xhsdn.data.DownloadTask?>(null) }

    if (taskToDelete != null) {
        WindowDialog(
            title = stringResource(R.string.delete_task_dialog_title),
            summary = stringResource(R.string.delete_task_dialog_message),
            show = taskToDelete != null,
            onDismissRequest = { taskToDelete = null }
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { taskToDelete = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.apply),
                    onClick = {
                        taskToDelete?.let { onDeleteTask(it) }
                        taskToDelete = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 18.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 筛选标签栏
                var selectedFilter by remember { mutableStateOf(0) }
                val waitingCount = tasks.count { it.status == com.neoruaa.xhsdn.data.TaskStatus.WAITING_FOR_USER }
                val failedCount = tasks.count { it.status == com.neoruaa.xhsdn.data.TaskStatus.FAILED }
                val filterLabels = listOf(stringResource(R.string.tab_all), stringResource(R.string.tab_waiting_for_selection, waitingCount), stringResource(R.string.tab_failed, failedCount))
                TabRowWithContour(
                    tabs = filterLabels,
                    selectedTabIndex = selectedFilter,
                    fontSize = 14.sp,
                    height = 40.dp,
                    colors = TabRowDefaults.tabRowColors(
                       selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainer
                    ),
                    itemSpacing = 2.dp,
                    onTabSelected = { selectedFilter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = contentStartPadding + 12.dp,
                            top = 10.dp,
                            end = contentEndPadding + 12.dp,
                            bottom = 10.dp
                        )
                )

                // 根据筛选条件过滤任务
                val filteredTasks = when (selectedFilter) {
                    1 -> tasks.filter { it.status == com.neoruaa.xhsdn.data.TaskStatus.WAITING_FOR_USER }
                    2 -> tasks.filter { it.status == com.neoruaa.xhsdn.data.TaskStatus.FAILED }
                    else -> tasks
                }
                LaunchedEffect(filteredTasks.size) {
                    if (filteredTasks.isNotEmpty()) {
                        statusListState.animateScrollToItem(0)
                    }
                }

                LazyColumn(
                    state = statusListState,
                    contentPadding = PaddingValues(
                        start = contentStartPadding + 12.dp,
                        end = contentEndPadding + 12.dp
                    ),
                    modifier = if (nestedScrollConnection != null) {
                        Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    if (filteredTasks.isEmpty()) {
                        item(key = "history_empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .squircleBackground(
                                        color = MiuixTheme.colorScheme.surfaceVariant,
                                        cornerRadius = 18.dp
                                    )
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = stringResource(R.string.no_downloaded_files),
                                    modifier = Modifier.size(48.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.no_downloaded_files),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.ready_to_download),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    } else {
                        itemsIndexed(filteredTasks, key = { _, task -> task.id }) { _, task ->
                            val context = LocalContext.current
                            TaskCell(
                                task = task,
                                // 只有正在下载的任务才使用 uiState.mediaItems
                                mediaItems = if (task.filePaths.isNotEmpty()) {
                                    task.filePaths.map { MediaItem(it, detectMediaType(it)) }
                                } else if (task.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING && uiState.mediaItems.isNotEmpty()) {
                                    uiState.mediaItems
                                } else {
                                    emptyList()
                                },

                                onCopyUrl = { onCopyUrl(task.noteUrl) },
                                onBrowseUrl = { onBrowseUrl(task.noteUrl) },
                                onRetry = { onRetryTask(task) },
                                onContinue = { onContinueTask(task) },
                                onWebCrawl = { onWebCrawlTask(task) },
                                onStop = { onStopTask(task) },
                                onDelete = { taskToDelete = task },
                                onMediaClick = onMediaClick,
                                onClick = {
                                    val detailIntent = DetailActivity.newIntent(
                                        context,
                                        task.id.toString(),
                                        task.noteTitle ?: task.noteUrl,
                                        task.filePaths,
                                        task.noteContent,
                                        task.noteUrl  // Pass the note URL
                                    )
                                    context.startActivity(detailIntent)
                                },
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    item(key = "history_bottom_spacer") {
                        Spacer(
                            modifier = Modifier
                                .height(116.dp)
                                .navigationBarsPadding()
                        )
                    }
                }
            }
        }

        // Floating bottom actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = contentStartPadding + 24.dp,
                    end = contentEndPadding + 24.dp,
                    bottom = navPadding + 16.dp
                )
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = if (!uiState.isDownloading) {
                    {
                        if (manualInputLinks) {
                            onShowInputDialogChange(true)
                        } else {
                            onDownload()
                        }
                    }
                } else {
                    null
                },
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(
                    color = if (uiState.isDownloading) {
                        MiuixTheme.colorScheme.disabledPrimaryButton
                    } else {
                        MiuixTheme.colorScheme.primary
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (manualInputLinks) MiuixIcons.Link else MiuixIcons.File,
                            contentDescription = stringResource(R.string.github_link),
                            modifier = Modifier.padding(end = 8.dp),
                            tint = MiuixTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = if (uiState.isDownloading) {
                                stringResource(R.string.downloading_files)
                            } else if (manualInputLinks) {
                                stringResource(R.string.manual_input_links)
                            } else {
                                stringResource(R.string.start_download_from_clipboard)
                            },
                            color = MiuixTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (uiState.isDownloading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activeTask?.noteTitle ?: activeTask?.noteUrl ?: " ",
                            color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    onClick = if (!uiState.isDownloading) {
                        { menuExpanded = !menuExpanded }
                    } else {
                        null
                    },
                    cornerRadius = 999.dp,
                    colors = CardDefaults.defaultColors(
                        color = if (uiState.isDownloading) {
                            MiuixTheme.colorScheme.disabledPrimaryButton
                        } else {
                            MiuixTheme.colorScheme.primary
                        }
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.more_options),
                            modifier = Modifier.fillMaxSize(0.4f),
                            tint = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                }

                WindowListPopup(
                    show = menuExpanded && !uiState.isDownloading,
                    popupPositionProvider = rememberOffsetPopupPositionProvider(
                        base = ListPopupDefaults.ContextMenuPositionProvider,
                        y = (-8).dp
                    ),
                    alignment = PopupPositionProvider.Align.BottomEnd,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    ListPopupColumn {
                        menuItems.forEachIndexed { index, item ->
                            DropdownImpl(
                                text = item,
                                optionSize = menuItems.size,
                                isSelected = false,
                                onSelectedIndexChange = {
                                    menuExpanded = false
                                    when (index) {
                                        0 -> onCopyText()
                                        1 -> onWebCrawlFromClipboard()
                                        2 -> onRequestClearHistory()
                                    }
                                },
                                index = index
                            )
                        }
                    }
                }
            }
        }

        // 剪贴板检测提示气泡（叠加层，靠近底部按钮）
        if (detectedXhsLink != null && !uiState.isDownloading && !manualInputLinks) {
            val promptColor = MiuixTheme.colorScheme.tertiaryContainer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = contentStartPadding + 24.dp,
                        end = contentEndPadding + 24.dp,
                        bottom = navPadding + 76.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismissPrompt,
                    cornerRadius = 18.dp,
                    colors = CardDefaults.defaultColors(
                        color = promptColor
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.clipboard_xhs_link_detected),
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = detectedXhsLink,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = stringResource(R.string.common_dismiss),
                            tint = MiuixTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // 三角形指针（紧贴卡片底部）
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(24.dp, 10.dp)
                ) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2, size.height)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = promptColor
                    )
                }
            }
        }

        // 输入分享链接对话框（放在 HistoryPage 内，避免键盘偏移异常）
        if (showInputDialog) {
            val context = LocalContext.current
            val manualInputTitle = stringResource(R.string.manual_input_links)
            val enterXhsUrl = stringResource(R.string.enter_xhs_url)
            val cancelText = stringResource(R.string.cancel)
            val downloadButtonText = stringResource(R.string.download_button)
            val pleaseEnterUrl = stringResource(R.string.please_enter_url)

            var inputLink by remember { mutableStateOf("") }

            WindowDialog(
                title = manualInputTitle,
                show = showInputDialog,
                summary = enterXhsUrl,
                onDismissRequest = {
                    onShowInputDialogChange(false)
                    inputLink = ""
                }
            ) {
                Column {
                    TextField(
                        value = inputLink,
                        onValueChange = { inputLink = it },
                        label = stringResource(R.string.main_url_example),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        TextButton(
                            text = cancelText,
                            onClick = {
                                onShowInputDialogChange(false)
                                inputLink = ""
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            text = downloadButtonText,
                            onClick = {
                                if (inputLink.isNotEmpty()) {
                                    // 执行手动输入下载
                                    onManualInputDownload(inputLink)

                                    // 关闭对话框并清空输入
                                    onShowInputDialogChange(false)
                                    inputLink = ""
                                } else {
                                    Toast.makeText(context, pleaseEnterUrl, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }

    }
}

/**
 * 任务 Cell 组件
 */
@Composable
private fun TaskCell(
    task: com.neoruaa.xhsdn.data.DownloadTask,
    mediaItems: List<MediaItem> = emptyList(),
    onCopyUrl: () -> Unit,
    onBrowseUrl: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onWebCrawl: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val statusColor = when (task.status) {
        com.neoruaa.xhsdn.data.TaskStatus.QUEUED -> Color(0xFF9E9E9E)       // 灰色
        com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING -> Color(0xFF2196F3)  // 蓝色
        com.neoruaa.xhsdn.data.TaskStatus.COMPLETED -> Color(0xFF56C75D)    // 绿色
        com.neoruaa.xhsdn.data.TaskStatus.FAILED -> Color(0xFFF44336)       // 红色
        com.neoruaa.xhsdn.data.TaskStatus.WAITING_FOR_USER -> Color(0xFFFF9800) // 橙色
    }
    
    val statusText = when (task.status) {
        com.neoruaa.xhsdn.data.TaskStatus.QUEUED -> stringResource(R.string.task_status_queued)
        com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING -> stringResource(R.string.task_status_downloading)
        com.neoruaa.xhsdn.data.TaskStatus.COMPLETED -> stringResource(R.string.task_status_completed)
        com.neoruaa.xhsdn.data.TaskStatus.FAILED -> stringResource(R.string.task_status_failed)
        com.neoruaa.xhsdn.data.TaskStatus.WAITING_FOR_USER -> stringResource(R.string.task_status_waiting_for_user)
    }
    
    val typeText = when (// Check if this is a web crawl task (created from WebViewActivity)
        task.noteType) {
        com.neoruaa.xhsdn.data.NoteType.UNKNOWN if (UrlUtils.isXhsLink(task.noteUrl) ||
                task.noteUrl.startsWith("http") && task.totalFiles > 0) -> stringResource(R.string.note_type_web_crawl)
        com.neoruaa.xhsdn.data.NoteType.IMAGE -> stringResource(R.string.note_type_image)
        com.neoruaa.xhsdn.data.NoteType.VIDEO -> stringResource(R.string.note_type_video)
        com.neoruaa.xhsdn.data.NoteType.UNKNOWN -> stringResource(R.string.note_type_unknown)
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 18.dp
            )
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onDelete
            )
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            // 顶部：时间 + 状态标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 创建时间
                Text(
                    text = formatTime(task.createdAt),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 2.dp)
                )

                // 状态标签
                Box(
                    modifier = Modifier
                        .clip(ContinuousRoundedRectangle(999.dp))
                        .background(statusColor.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 标题（最多两行）
            Text(
                text = task.noteTitle ?: task.noteUrl,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 类型 + 文件数量
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.task_info_format, typeText, task.totalFiles),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                if (task.failedFiles > 0) {
                    Text(
                        text = stringResource(R.string.failed_files_format, task.failedFiles),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 进度条（仅下载中显示）
            if (task.totalFiles > 0 && task.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING) {
                Column {
                    // 进度文本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.files_completed_format, task.completedFiles, task.totalFiles),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = stringResource(R.string.progress_format, (task.progress * 100).toInt()),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 进度条
                    LinearProgressIndicator(
                        progress = task.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 媒体预览网格（最后一个任务显示）
            if (mediaItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    mediaItems.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .squircleSurface(
                                    color = MiuixTheme.colorScheme.surface,
                                    cornerRadius = 8.dp
                                )
                                .clickable { onMediaClick(item) }
                        ) {
                            val bitmap = rememberThumbnail(item)
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        if (task.status != com.neoruaa.xhsdn.data.TaskStatus.COMPLETED) {
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isDownloading = task.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING ||
                                task.status == com.neoruaa.xhsdn.data.TaskStatus.QUEUED

            if (isDownloading) {
                 Button(
                     onClick = onStop,
                     modifier = Modifier.weight(1f),
                     colors = ButtonDefaults.buttonColorsPrimary()
                 ) {
                     Text(
                         text = stringResource(R.string.common_stop),
                         color = MiuixTheme.colorScheme.onPrimary
                     )
                 }
            } else {

                // 等待用户选择状态 (显示 坚持下载/网页爬取)
                if (task.status == com.neoruaa.xhsdn.data.TaskStatus.WAITING_FOR_USER) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 提示语
                        Text(
                            text = stringResource(R.string.official_limitation_tip),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary()
                            ) {
                                Text(
                                    text = stringResource(R.string.main_continue_download),
                                    color = MiuixTheme.colorScheme.onPrimary
                                )
                            }
                            Button(
                                onClick = onWebCrawl,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    MiuixTheme.colorScheme.surface,
                                    MiuixTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(stringResource(R.string.web_crawl_option), color = MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    }
                } else {
//                    // 复制链接按钮
//                    TextButton(
//                        text = "复制链接",
//                        onClick = onCopyUrl,
//                        modifier = Modifier.weight(1f)
//                    )
//
//                    // 爬取按钮（通过网页爬取功能打开）
//                    TextButton(
//                        text = "网页爬取",
//                        onClick = onWebCrawl,
//                        modifier = Modifier.weight(1f)
//                    )

                    // 重试按钮（仅失败任务显示）
                    if (task.status == com.neoruaa.xhsdn.data.TaskStatus.FAILED) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(
                                text = stringResource(R.string.retry),
                                color = MiuixTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }

    }
}

/**
 * 格式化时间戳为可读字符串
 */
private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
private fun rememberThumbnail(item: MediaItem): ImageBitmap? {
    // 先检查缓存
    val cachedBitmap = thumbnailCache.get(item.path)
    if (cachedBitmap != null) {
        return cachedBitmap
    }
    
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = item.path) {
        value = withContext(Dispatchers.IO) {
            // 再次检查缓存（可能在等待期间被其他协程加载）
            thumbnailCache.get(item.path)?.let { return@withContext it }
            
            val file = File(item.path)
            if (!file.exists()) return@withContext null
            val bitmap = runCatching {
                when (item.type) {
                    MediaType.IMAGE -> decodeSampledBitmap(file.path, 200, 200)?.asImageBitmap()
                    MediaType.VIDEO -> createVideoThumbnail(file)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
            
            // 存入缓存
            bitmap?.let { thumbnailCache.put(item.path, it) }
            bitmap
        }
    }
    return state.value
}


private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return android.graphics.BitmapFactory.decodeFile(path, options)
}

private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun createVideoThumbnail(file: File): android.graphics.Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        android.media.ThumbnailUtils.createVideoThumbnail(
            file,
            Size(640, 360),
            null
        )
    } else {
        @Suppress("DEPRECATION")
        android.media.ThumbnailUtils.createVideoThumbnail(
            file.path,
            android.provider.MediaStore.Video.Thumbnails.MINI_KIND
        )
    }
}
