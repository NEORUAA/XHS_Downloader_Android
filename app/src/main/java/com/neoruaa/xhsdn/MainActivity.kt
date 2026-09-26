package com.neoruaa.xhsdn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import com.neoruaa.xhsdn.utils.detectMediaType
import com.neoruaa.xhsdn.utils.createVideoThumbnail
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.storedMediaExists
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.TabRowWithContour
import com.neoruaa.xhsdn.ui.SelectableMediaWaterfall
import com.neoruaa.xhsdn.ui.miuixBackdropSource
import com.neoruaa.xhsdn.ui.miuixVerticalScrollEffects
import com.neoruaa.xhsdn.ui.rememberMiuixTopBarBackdrop
import com.neoruaa.xhsdn.ui.rememberWindowLayoutInfo
import com.neoruaa.xhsdn.ui.navigation.AppRoute
import com.neoruaa.xhsdn.viewmodels.MainUiState
import com.neoruaa.xhsdn.viewmodels.MainViewModel
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import com.neoruaa.xhsdn.viewmodels.SelectiveDownloadPhase
import com.neoruaa.xhsdn.feature.history.HistoryFilter
import com.neoruaa.xhsdn.feature.history.HistoryUiState
import com.neoruaa.xhsdn.feature.history.HistoryViewModel
import com.neoruaa.xhsdn.data.settings.SettingsRepository
import com.neoruaa.xhsdn.data.xhs.XhsUrlParser
import top.yukonga.miuix.kmp.basic.DropdownItem
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import com.neoruaa.xhsdn.ui.ActionIconButton
import com.neoruaa.xhsdn.data.TaskStatus
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleSurface

// 缩略图内存缓存（最多缓存 50 张缩略图）
private val thumbnailCache = object : LruCache<String, ImageBitmap>(50) {}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val updateChecker get() = (application as XHSApplication).appContainer.updateChecker
    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.factory(
            (application as XHSApplication).appContainer.taskRepository
        )
    }
    private val settingsRepository: SettingsRepository by lazy {
        (application as XHSApplication).appContainer.settingsRepository
    }
    private val pendingRoute = mutableStateOf<AppRoute?>(null)
    private val _autoDownloadIntentUrl = mutableStateOf<String?>(null)
    private var context: Context =  this
    private var pendingStorageAction: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.isNotEmpty() && grants.values.all { it }
        showToast(
            getString(
                if (granted) {
                    R.string.storage_permission_granted_continue
                } else {
                    R.string.storage_permission_missing_unable_save
                }
            )
        )
        if (granted) {
            pendingStorageAction?.also { action ->
                pendingStorageAction = null
                action()
            }
        } else {
            pendingStorageAction = null
        }
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingStorageAction
        pendingStorageAction = null
        if (hasAllFilesAccess()) {
            action?.invoke()
        } else if (action != null) {
            showToast(getString(R.string.settings_check_existing_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateChecker.checkOnStartup()
        _autoDownloadIntentUrl.value = consumeDownloadIntent(intent)

        if (Build.VERSION.SDK_INT >= 33) { // Android 13
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val controller = ThemeController(ColorSchemeMode.System)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val historyUiState by historyViewModel.uiState.collectAsStateWithLifecycle()
            val appSettings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = settingsRepository.currentSettings
            )
            val topBarState = rememberTopAppBarState()
            val scrollBehavior = MiuixScrollBehavior(state = topBarState)

            LaunchedEffect(appSettings.keepScreenOn) {
                if (appSettings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            
            // 处理自动下载
            val autoUrl by _autoDownloadIntentUrl
            LaunchedEffect(autoUrl, appSettings.selectiveDownload, appSettings.xhsLinksEnabled) {
            autoUrl?.let { url ->
                     settingsRepository.awaitReady()
                     val snapshot = settingsRepository.currentSettings
                     if (url.isNotEmpty() && snapshot.xhsLinksEnabled) {
                        viewModel.updateUrl(url)
                        ensureStoragePermission {
                            if (snapshot.selectiveDownload) {
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
            var detectedXhsLink by remember { mutableStateOf<String?>(null) }
            val manualInputLinks = appSettings.manualInputLinks
            val xhsLinksEnabled = appSettings.xhsLinksEnabled
            val selectiveDownload = appSettings.selectiveDownload
            
            // 监听生命周期 ON_RESUME 和 ON_PAUSE 进行剪贴板监听器管理
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

            // 提取核心检测逻辑为可复用函数
            fun checkClipboard() {
                if (!xhsLinksEnabled || manualInputLinks) {
                    detectedXhsLink = null
                    return
                }
                val currentAutoRead = appSettings.autoReadClipboard
                val currentShowBubble = appSettings.showClipboardBubble

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

                                ensureStoragePermission {
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
                                }
                                
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
            val checkClipboardState = rememberUpdatedState(newValue = { checkClipboard() })
            
            val clipboardListener = remember {
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                     // 延迟检测，解决 listener 触发时 ClipData 可能尚未准备好的问题
                     scope.launch {
                         kotlinx.coroutines.delay(300) // 300ms 延迟
                         checkClipboardState.value()
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
                            checkClipboardState.value()
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
                val backStack = rememberNavBackStack(AppRoute.Main)
                val navigateBack = remember(backStack) {
                    { if (backStack.size > 1) backStack.removeLastOrNull() }
                }
                val navigateTo = remember(backStack) {
                    { route: AppRoute ->
                        if (backStack.lastOrNull() != route) backStack.add(route)
                    }
                }
                val requestedRoute = pendingRoute.value
                LaunchedEffect(requestedRoute) {
                    requestedRoute?.let {
                        if (it == AppRoute.Main) {
                            while (backStack.size > 1) backStack.removeLastOrNull()
                        } else navigateTo(it)
                        pendingRoute.value = null
                    }
                }
                val transitionEffects = remember {
                    NavDisplayTransitionEffects(
                        enableCornerClip = true,
                        dimAmount = 0.5f,
                        blockInputDuringTransition = false
                    )
                }

                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surface),
                    onBack = navigateBack,
                    transitionEffects = transitionEffects,
                    entryProvider = { route ->
                        NavEntry(route) { destination ->
                            when (val appRoute = destination as AppRoute) {
                                AppRoute.Main -> {
                                    var showInputDialog by remember { mutableStateOf(false) }

                                    MainScreen(
                    uiState = uiState,
                    historyUiState = historyUiState,
                    downloadSpeeds = viewModel.downloadSpeeds,
                    manualInputLinks = manualInputLinks,
                    showInputDialog = showInputDialog,
                    onShowInputDialogChange = { showInputDialog = it },
                    scrollBehavior = scrollBehavior,
                    onDownload = {
                        if (!manualInputLinks && xhsLinksEnabled) {
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


                                    }
                                } else {
                                    showToast(getString(R.string.clipboard_no_xhs_link))
                                }
                            }
                        }
                    },
                    onCopyText = { inputLink ->
                        viewModel.updateUrl(inputLink)
                        ensureStoragePermission {
                            viewModel.copyDescription({ showToast(getString(R.string.copied_description)) }, { showToast(it) })
                        }
                    },
                    onOpenSettings = { navigateTo(AppRoute.Settings) },
                    onWebCrawl = { inputLink ->
                        val cleanUrl = UrlUtils.extractFirstUrl(inputLink)
                        if (cleanUrl != null) {
                            viewModel.resetWebCrawlFlag()
                            navigateTo(AppRoute.WebView(cleanUrl))
                            detectedXhsLink = null
                        } else {
                            showToast(getString(R.string.invalid_link_please_reenter))
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
                        viewModel.deleteTask(task.id)
                    },
                    onContinueTask = { task -> 
                        ensureStoragePermission {
                            viewModel.continueTask(task)
                        }
                    },
                    onWebCrawlTask = { task ->
                        viewModel.updateUrl(task.noteUrl)
                        val cleanUrl = UrlUtils.extractFirstUrl(task.noteUrl)
                        if (cleanUrl != null) {
                            viewModel.resetWebCrawlFlag()
                            navigateTo(AppRoute.WebView(cleanUrl, task.id))
                        } else {
                            showToast(getString(R.string.invalid_link_please_reenter))
                        }
                    },
                    onCancelTask = { task -> viewModel.cancelTask(task.id) },
                    onLoadMore = historyViewModel::loadMore,
                    onSaveInfo = { inputLink ->
                        viewModel.updateUrl(inputLink)
                        ensureStoragePermission { viewModel.saveNoteInformation { showToast(it) } }
                    },
                    onStopTask = { task ->
                        viewModel.pauseTask(task.id)
                    },
                    onClearHistory = { viewModel.clearHistory() },
                    onManualInputDownload = { inputLink ->
                        ensureStoragePermission {
                            viewModel.updateUrl(inputLink)
                            if (selectiveDownload) {
                                viewModel.startSelectiveDownload { showToast(it) }
                            } else {
                                viewModel.startDownload { showToast(it) }


                            }
                        }
                    },
                    detectedXhsLink = detectedXhsLink,
                    onDismissPrompt = { detectedXhsLink = null },
                    onCancelSelectiveDownload = viewModel::cancelSelectiveDownload,
                    onSaveSelectedMedia = {
                        ensureStoragePermission {
                            viewModel.saveSelectedMedia { showToast(it) }
                        }
                    },
                    onToggleSelectiveItem = viewModel::toggleSelectiveItem,
                    onHistoryQueryChange = historyViewModel::updateQuery,
                    onHistoryQueryClear = historyViewModel::clearQuery,
                    onHistoryFilterChange = historyViewModel::selectFilter,
                    onOpenDetail = { task ->
                        navigateTo(
                            AppRoute.Detail(
                                taskId = task.id.toString(),
                                taskTitle = task.displayTitle,
                                filePaths = task.filePaths,
                                noteContent = task.noteContent,
                                noteUrl = task.noteUrl
                            )
                        )
                    }
                )

                                }

                                AppRoute.Settings -> SettingsRoute(onBack = navigateBack, onOpenAdvanced = { navigateTo(AppRoute.DownloadSettings(it)) })
                                is AppRoute.DownloadSettings -> com.neoruaa.xhsdn.feature.settings.DownloadSettingsRoute(
                                    appRoute.section, onBack = navigateBack, onOpenBrowser = { navigateTo(AppRoute.WebView("https://www.xiaohongshu.com")) })

                                is AppRoute.Detail -> DetailRoute(
                                    route = appRoute,
                                    onBack = navigateBack,
                                    onOpenWebView = { url ->
                                        backStack[backStack.lastIndex] = AppRoute.WebView(url)
                                    }
                                )

                                is AppRoute.WebView -> WebViewRoute(
                                    route = appRoute,
                                    onBack = navigateBack,
                                    onResult = { urls, content, taskId, url ->
                                        handleWebViewResult(urls, content, taskId, url)
                                        navigateBack()
                                    }
                                )
                            }
                        }
                    }
                )
                com.neoruaa.xhsdn.feature.update.UpdateDialog(updateChecker)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDownloadIntent(intent)?.let {
            pendingRoute.value = AppRoute.Main
            _autoDownloadIntentUrl.value = it
        }
    }

    private fun consumeDownloadIntent(intent: Intent): String? {
        val link = intent.getStringExtra("auto_download_url")
            ?: intent.dataString?.takeIf(UrlUtils::isXhsLink)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { intent.action == Intent.ACTION_SEND }
        if (link != null) {
            // Configuration recreation must not resubmit a consumed share.
            intent.removeExtra("auto_download_url")
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.setDataAndType(null, intent.type)
        }
        return link
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
        val customTree = settingsRepository.currentSettings.customStorageTreeUri
        if (customTree != null) {
            if (hasCustomStorageAccess(Uri.parse(customTree))) {
                if (settingsRepository.currentSettings.checkExistingFilesBeforeSave) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                        requestAllFilesAccess(onReady)
                        return
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        !hasLegacyStoragePermission()
                    ) {
                        requestLegacyStoragePermission(onReady)
                        return
                    }
                }
                onReady()
            } else {
                showToast(getString(R.string.storage_location_access_lost_reselect))
                pendingRoute.value = AppRoute.Settings
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyStoragePermission()) {
            onReady()
            return
        }
        requestLegacyStoragePermission(onReady)
    }

    private fun requestLegacyStoragePermission(onReady: () -> Unit) {
        pendingStorageAction = onReady
        storagePermissionLauncher.launch(
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAllFilesAccess(onReady: () -> Unit) {
        pendingStorageAction = onReady
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            allFilesAccessLauncher.launch(appSpecificIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            val globalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            try {
                allFilesAccessLauncher.launch(globalIntent)
            } catch (_: android.content.ActivityNotFoundException) {
                pendingStorageAction = null
                showToast(getString(R.string.settings_check_existing_permission_unavailable))
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun hasLegacyStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasCustomStorageAccess(treeUri: Uri): Boolean {
        if (treeUri.scheme != "content" || treeUri.authority != "com.android.externalstorage.documents") {
            return false
        }
        val persisted = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        }
        if (!persisted) return false
        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
        }.getOrNull() ?: return false
        return runCatching {
            contentResolver.query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                val flags = if (flagsIndex >= 0) cursor.getInt(flagsIndex) else 0
                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun openFile(item: MediaItem) {
        if (!storedMediaExists(item.media)) {
            showToast(getString(R.string.file_does_not_exist, item.path))
            return
        }
        val mimeType = item.media.mimeType.takeUnless { it == "application/octet-stream" } ?: when (item.type) {
            MediaType.VIDEO -> "video/*"
            MediaType.IMAGE -> "image/*"
            MediaType.OTHER -> "*/*"
        }
        val uri = item.media.legacyPath?.let { path ->
            FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        } ?: item.media.androidUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = android.content.ClipData.newRawUri(item.media.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kotlin.runCatching { startActivity(intent) }.onFailure {
            showToast(getString(R.string.unable_to_open_file_error, it.message))
        }
    }

    private fun handleWebViewResult(
        urls: List<String>,
        content: String,
        taskId: Long?,
        webViewUrl: String
    ) {
        viewModel.updateUrl(webViewUrl)
        viewModel.onWebCrawlResult(emptyList(), null, taskId, content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    uiState: MainUiState,
    historyUiState: HistoryUiState,
    downloadSpeeds: StateFlow<Map<Long, Long>>,
    manualInputLinks: Boolean = false,
    showInputDialog: Boolean = false,
    onShowInputDialogChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCopyText: (String) -> Unit,
    onSaveInfo: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenSettings: () -> Unit,
    onWebCrawl: (String) -> Unit,
    onClearHistory: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onCopyUrl: (String) -> Unit,
    onBrowseUrl: (String) -> Unit,
    onRetryTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onStopTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onCancelTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onDeleteTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onContinueTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onWebCrawlTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onManualInputDownload: (String) -> Unit,
    scrollBehavior: ScrollBehavior,
    detectedXhsLink: String?,
    onDismissPrompt: () -> Unit,
    onCancelSelectiveDownload: () -> Unit,
    onSaveSelectedMedia: () -> Unit,
    onToggleSelectiveItem: (String) -> Unit,
    onHistoryQueryChange: (String) -> Unit,
    onHistoryQueryClear: () -> Unit,
    onHistoryFilterChange: (HistoryFilter) -> Unit,
    onOpenDetail: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit
) {
    val windowLayoutInfo = rememberWindowLayoutInfo()
    val topBarBackdrop = rememberMiuixTopBarBackdrop()
    val statusListState = rememberLazyListState()
    var searchExpanded by rememberSaveable {
        mutableStateOf(historyUiState.query.isNotEmpty())
    }
    val focusManager = LocalFocusManager.current
    val filterLabels = listOf(
        stringResource(R.string.tab_all),
        stringResource(
            R.string.tab_waiting_for_selection,
            historyUiState.waitingCount
        ),
        stringResource(R.string.tab_failed, historyUiState.failedCount)
    )

    // 清除历史记录确认对话框状态
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        WindowDialog(
            title = stringResource(R.string.clear_history_dialog_title),
            summary = stringResource(R.string.clear_history_dialog_message),
            show = showClearHistoryDialog,
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

        Scaffold(
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
            topBar = {
                val title = stringResource(R.string.app_full_name)
                AdaptiveTopAppBar(
                    title = title,
                    isWideScreen = windowLayoutInfo.isWideScreen,
                    backdrop = topBarBackdrop,
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
                    },
                    bottomContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SearchBar(
                                inputField = {
                                    InputField(
                                        query = historyUiState.query,
                                        onQueryChange = onHistoryQueryChange,
                                        onSearch = { focusManager.clearFocus() },
                                        expanded = searchExpanded,
                                        onExpandedChange = { searchExpanded = it },
                                        label = stringResource(R.string.main_search_history),
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MiuixIcons.Basic.Search,
                                                contentDescription = stringResource(
                                                    R.string.main_search_history
                                                ),
                                                modifier = Modifier.padding(
                                                    start = 16.dp,
                                                    end = 8.dp
                                                ),
                                                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh
                                            )
                                        },
                                        trailingIcon = {
                                            AnimatedVisibility(
                                                visible = historyUiState.query.isNotEmpty()
                                            ) {
                                                IconButton(
                                                    onClick = onHistoryQueryClear,
                                                    minHeight = 35.dp,
                                                    minWidth = 35.dp,
                                                    cornerRadius = 35.dp,
                                                    modifier = Modifier.padding(end = 8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = MiuixIcons.Basic.SearchCleanup,
                                                        contentDescription = stringResource(
                                                            R.string.common_clear_search
                                                        ),
                                                        tint = MiuixTheme.colorScheme
                                                            .onSurfaceContainerHighest
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                },
                                outsideEndAction = {
                                    Text(
                                        modifier = Modifier
                                            .padding(
                                                end = windowLayoutInfo.topBarContentEndPadding +
                                                    12.dp
                                            )
                                            .clickable(
                                                interactionSource = null,
                                                indication = null
                                            ) {
                                                searchExpanded = false
                                            },
                                        text = stringResource(R.string.cancel),
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                },
                                onExpandedChange = { searchExpanded = it },
                                expanded = searchExpanded,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = windowLayoutInfo.topBarContentStartPadding,
                                        end = windowLayoutInfo.topBarContentEndPadding,
                                        top = 10.dp
                                    )
                            ) {}

                            TabRowWithContour(
                                tabs = filterLabels,
                                selectedTabIndex = historyUiState.selectedFilter.ordinal,
                                fontSize = 14.sp,
                                height = 40.dp,
                                itemSpacing = 2.dp,
                                onTabSelected = { index ->
                                    HistoryFilter.entries.getOrNull(index)
                                        ?.let(onHistoryFilterChange)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = windowLayoutInfo.topBarContentStartPadding + 12.dp,
                                        top = 10.dp,
                                        end = windowLayoutInfo.topBarContentEndPadding + 12.dp,
                                        bottom = 10.dp
                                    )
                            )
                        }
                    }
                )
            }
        ) { padding ->
            HistoryPage(
                uiState = uiState,
                historyUiState = historyUiState,
                downloadSpeeds = downloadSpeeds,
                manualInputLinks = manualInputLinks,
                showInputDialog = showInputDialog,
                onShowInputDialogChange = onShowInputDialogChange,
                statusListState = statusListState,
                onDownload = onDownload,
                onManualInputDownload = onManualInputDownload,
                onCopyText = onCopyText,
                onWebCrawl = onWebCrawl,
                onRequestClearHistory = { showClearHistoryDialog = true },
                onMediaClick = onMediaClick,
                onCopyUrl = onCopyUrl,
                onBrowseUrl = onBrowseUrl,
                onRetryTask = onRetryTask,
                onContinueTask = onContinueTask,
                onWebCrawlTask = onWebCrawlTask,
                onStopTask = onStopTask,
                onCancelTask = onCancelTask,
                onLoadMore = onLoadMore,
                onSaveInfo = onSaveInfo,
                onDeleteTask = onDeleteTask,
                detectedXhsLink = detectedXhsLink,
                onDismissPrompt = onDismissPrompt,
                onOpenDetail = onOpenDetail,
                backdrop = topBarBackdrop,
                contentStartPadding = windowLayoutInfo.contentStartPadding,
                contentEndPadding = windowLayoutInfo.contentEndPadding,
                topContentPadding = padding.calculateTopPadding(),
                modifier = Modifier
                    .fillMaxSize(),
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
internal fun SelectiveDownloadSheet(
    uiState: MainUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onToggleItem: (String) -> Unit
) {
    val selectiveState = uiState.selectiveDownload
    val canSave = selectiveState.phase == SelectiveDownloadPhase.Ready &&
        selectiveState.selectedPaths.isNotEmpty()
    WindowBottomSheet(
        show = selectiveState.show,
        title = stringResource(R.string.selective_download),
        allowDismiss = true,
        onDismissRequest = onCancel,
        backgroundColor = MiuixTheme.colorScheme.surface,
        startAction = { TopAppBarIconButton(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel), onClick = onCancel) },
        endAction = { TopAppBarIconButton(imageVector = MiuixIcons.Regular.Download, contentDescription = stringResource(R.string.download_button), onClick = onSave, enabled = canSave) }
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.selective_download_ready, selectiveState.selectedPaths.size, selectiveState.items.size), modifier = Modifier.weight(1f))
            ActionIconButton(imageVector = MiuixIcons.Regular.SelectAll, contentDescription = stringResource(R.string.download_select_all), onClick = {
                val all = selectiveState.selectedPaths.size == selectiveState.items.size
                selectiveState.items.filter { all || it.path !in selectiveState.selectedPaths }.forEach { onToggleItem(it.path) }
            })
        }
        com.neoruaa.xhsdn.ui.SelectableMediaWaterfall(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            items = selectiveState.items,
            selectedPaths = selectiveState.selectedPaths,
            onToggle = onToggleItem,
        )
    }
}

private enum class LinkInputAction(val labelRes: Int) {
    DOWNLOAD(R.string.download_button),
    COPY_TEXT(R.string.copy_description),
    WEB_CRAWL(R.string.web_crawl_option),
    SAVE_INFO(R.string.download_info_only)
}

@Composable
private fun HistoryPage(
    uiState: MainUiState,
    historyUiState: HistoryUiState,
    downloadSpeeds: StateFlow<Map<Long, Long>>,
    modifier: Modifier = Modifier,
    manualInputLinks: Boolean = false,
    showInputDialog: Boolean = false,
    onShowInputDialogChange: (Boolean) -> Unit,
    statusListState: androidx.compose.foundation.lazy.LazyListState,
    onDownload: () -> Unit,
    onManualInputDownload: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onSaveInfo: (String) -> Unit,
    onLoadMore: () -> Unit,
    onWebCrawl: (String) -> Unit,
    onRequestClearHistory: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onCopyUrl: (String) -> Unit,
    onBrowseUrl: (String) -> Unit,

    onRetryTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onContinueTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onWebCrawlTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onStopTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onCancelTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    onDeleteTask: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    detectedXhsLink: String?,
    onDismissPrompt: () -> Unit,
    onOpenDetail: (com.neoruaa.xhsdn.data.DownloadTask) -> Unit,
    backdrop: LayerBackdrop?,
    contentStartPadding: androidx.compose.ui.unit.Dp,
    contentEndPadding: androidx.compose.ui.unit.Dp,
    topContentPadding: androidx.compose.ui.unit.Dp,
    nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null
) {
    val tasks = historyUiState.allTasks
    val filteredTasks = historyUiState.filteredTasks
    val firstFilteredTask = filteredTasks.firstOrNull()
    val firstFilteredTaskId = firstFilteredTask?.id
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val activeTask = tasks.firstOrNull {
        it.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING || it.status == com.neoruaa.xhsdn.data.TaskStatus.QUEUED
    }
    val linkActions = listOf(LinkInputAction.COPY_TEXT, LinkInputAction.WEB_CRAWL, LinkInputAction.SAVE_INFO)
    val menuItems = linkActions.map { stringResource(it.labelRes) } + stringResource(R.string.clear_history)
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingMenuIndex by remember { mutableStateOf<Int?>(null) }
    var inputAction by remember { mutableStateOf(LinkInputAction.DOWNLOAD) }
    var submittedLink by remember { mutableStateOf<String?>(null) }
    var lastScrollQuery by rememberSaveable { mutableStateOf(historyUiState.query) }
    var lastScrollFilterOrdinal by rememberSaveable {
        mutableStateOf(historyUiState.selectedFilter.ordinal)
    }
    var lastFirstFilteredTaskId by rememberSaveable {
        mutableStateOf(firstFilteredTaskId)
    }
    val newlyInsertedTaskId = firstFilteredTask
        ?.takeIf {
            it.id != lastFirstFilteredTaskId &&
                historyUiState.query == lastScrollQuery &&
                historyUiState.selectedFilter.ordinal == lastScrollFilterOrdinal &&
                System.currentTimeMillis() - it.createdAt in 0..5_000L
        }
        ?.id
    var lastDetectedXhsLink by remember { mutableStateOf(detectedXhsLink) }
    LaunchedEffect(detectedXhsLink) {
        detectedXhsLink?.let { lastDetectedXhsLink = it }
    }
    var taskToDelete by remember { mutableStateOf<com.neoruaa.xhsdn.data.DownloadTask?>(null) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = modifier,
            cornerRadius = 18.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surface
            )
        ) {
            LaunchedEffect(
                historyUiState.query,
                historyUiState.selectedFilter,
                firstFilteredTaskId
            ) {
                val scrollTriggerChanged =
                    lastScrollQuery != historyUiState.query ||
                        lastScrollFilterOrdinal != historyUiState.selectedFilter.ordinal ||
                        lastFirstFilteredTaskId != firstFilteredTaskId

                lastScrollQuery = historyUiState.query
                lastScrollFilterOrdinal = historyUiState.selectedFilter.ordinal
                lastFirstFilteredTaskId = firstFilteredTaskId

                if (scrollTriggerChanged && firstFilteredTaskId != null) {
                    statusListState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                state = statusListState,
                overscrollEffect = null,
                contentPadding = PaddingValues(top = topContentPadding),
                modifier = if (nestedScrollConnection != null) {
                    Modifier
                        .fillMaxSize()
                        .miuixBackdropSource(backdrop)
                        .miuixVerticalScrollEffects()
                        .nestedScroll(nestedScrollConnection)
                } else {
                    Modifier
                        .fillMaxSize()
                        .miuixBackdropSource(backdrop)
                        .miuixVerticalScrollEffects()
                }
            ) {
                    if (filteredTasks.isEmpty()) {
                        item(key = "history_empty") {
                            val hasNoHistory = tasks.isEmpty()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = contentStartPadding + 12.dp,
                                        end = contentEndPadding + 12.dp
                                    )
                                    .squircleBackground(
                                        color = MiuixTheme.colorScheme.surfaceVariant,
                                        cornerRadius = 18.dp
                                    )
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = stringResource(
                                        if (hasNoHistory) {
                                            R.string.no_downloaded_files
                                        } else {
                                            R.string.main_search_no_results
                                        }
                                    ),
                                    modifier = Modifier.size(48.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(
                                        if (hasNoHistory) {
                                            R.string.no_downloaded_files
                                        } else {
                                            R.string.main_search_no_results
                                        }
                                    ),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        if (hasNoHistory) {
                                            R.string.ready_to_download
                                        } else {
                                            R.string.main_search_adjust_query
                                        }
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    } else {
                        itemsIndexed(filteredTasks, key = { _, task -> task.id }) { _, task ->
                            val creationVisibility = remember(task.id) {
                                MutableTransitionState(task.id != newlyInsertedTaskId).apply {
                                    targetState = true
                                }
                            }
                            AnimatedVisibility(
                                visibleState = creationVisibility,
                                enter = fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        delayMillis = 30
                                    )
                                ) + scaleIn(
                                    animationSpec = spring(
                                        dampingRatio = 0.58f,
                                        stiffness = 420f
                                    ),
                                    initialScale = 0.86f
                                ) + expandVertically(
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 460f
                                    ),
                                    expandFrom = Alignment.Top,
                                    clip = false
                                ),
                                exit = fadeOut(animationSpec = tween(durationMillis = 120))
                            ) {
                                TaskCell(
                                    task = task,
                                    downloadSpeeds = downloadSpeeds,
                                    // 只有正在下载的任务才使用 uiState.mediaItems
                                    mediaItems = if (task.mediaRefs.isNotEmpty()) {
                                        task.mediaRefs.map(::MediaItem)
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
                                    onCancel = { onCancelTask(task) },
                                    onDelete = { taskToDelete = task },
                                    onMediaClick = onMediaClick,
                                    onClick = {
                                        onOpenDetail(task)
                                    },
                                    modifier = Modifier.padding(
                                        start = contentStartPadding + 12.dp,
                                        end = contentEndPadding + 12.dp,
                                        bottom = 12.dp
                                    )
                                )
                            }
                        }
                    }

                    if (historyUiState.canLoadMore) item(key = "history_load_more") {
                        TextButton(text = stringResource(R.string.history_load_more), onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
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

        // Floating bottom actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(1f)
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
                onClick = {
                    if (manualInputLinks) {
                        inputAction = LinkInputAction.DOWNLOAD
                        onShowInputDialogChange(true)
                    } else onDownload()
                },
                cornerRadius = 18.dp,
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.primary
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
                            imageVector = if (manualInputLinks) MiuixIcons.Regular.Link else MiuixIcons.File,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = MiuixTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = if (manualInputLinks) {
                                stringResource(R.string.manual_input_links)
                            } else {
                                stringResource(R.string.start_download_from_clipboard)
                            },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium
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
                    onClick = { menuExpanded = !menuExpanded },
                    cornerRadius = 999.dp,
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primary
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
                    show = menuExpanded,
                    popupPositionProvider = rememberOffsetPopupPositionProvider(
                        base = ListPopupDefaults.ContextMenuPositionProvider,
                        y = (-8).dp
                    ),
                    alignment = PopupPositionProvider.Align.BottomEnd,
                    onDismissRequest = { menuExpanded = false },
                    onDismissFinished = {
                        val selectedIndex = pendingMenuIndex
                        pendingMenuIndex = null
                        selectedIndex?.let { index ->
                            if (index in linkActions.indices) {
                                inputAction = linkActions[index]
                                onShowInputDialogChange(true)
                            } else {
                                onRequestClearHistory()
                            }
                        }
                    }
                ) {
                    ListPopupColumn {
                        menuItems.forEachIndexed { index, item ->
                            DropdownImpl(
                                item = DropdownItem(
                                    text = item,
                                    icon = if (index == linkActions.size) {
                                        { Icon(MiuixIcons.Regular.Delete, contentDescription = null, modifier = it) }
                                    } else null
                                ),
                                optionSize = menuItems.size,
                                isSelected = false,
                                hasSubmenu = index in linkActions.indices,
                                onSelectedIndexChange = {
                                    pendingMenuIndex = index
                                    menuExpanded = false
                                },
                                index = index
                            )
                        }
                    }
                }
            }
        }

        // 剪贴板检测提示气泡（叠加层，靠近底部按钮）
        val displayedXhsLink = detectedXhsLink ?: lastDetectedXhsLink
        AnimatedVisibility(
            visible = detectedXhsLink != null && !uiState.isDownloading && !manualInputLinks,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    start = contentStartPadding + 24.dp,
                    end = contentEndPadding + 24.dp,
                    bottom = navPadding + 76.dp
                ),
            enter = fadeIn(animationSpec = tween(160)) +
                slideInVertically(
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
                    initialOffsetY = { it / 3 }
                ) +
                scaleIn(
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(0.5f, 1f)
                ),
            exit = fadeOut(animationSpec = tween(140)) +
                slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = { it / 4 }
                ) +
                scaleOut(
                    animationSpec = tween(180),
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(0.5f, 1f)
                )
        ) {
            val promptColor = MiuixTheme.colorScheme.tertiaryContainer
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
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
                                text = displayedXhsLink.orEmpty(),
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

        // Keep the dialog composed until miuix finishes its exit animation.
        val context = LocalContext.current
        val manualInputTitle = stringResource(R.string.manual_input_links)
        val inputSummary = stringResource(
            if (inputAction == LinkInputAction.WEB_CRAWL) R.string.webview_enter_url else R.string.enter_xhs_url
        )
        val cancelText = stringResource(R.string.cancel)
        val confirmText = stringResource(inputAction.labelRes)
        val pleaseEnterUrl = stringResource(R.string.please_enter_url)
        val invalidLink = stringResource(R.string.invalid_link_please_reenter)

        var inputLink by remember { mutableStateOf("") }

        WindowDialog(
            title = manualInputTitle,
            show = showInputDialog,
            summary = inputSummary,
            onDismissRequest = { onShowInputDialogChange(false) },
            onDismissFinished = {
                val link = submittedLink
                submittedLink = null
                inputLink = ""
                // Navigation may remove this page, so dispatch only after the dialog closes.
                link?.let {
                    when (inputAction) {
                        LinkInputAction.DOWNLOAD -> onManualInputDownload(it)
                        LinkInputAction.COPY_TEXT -> onCopyText(it)
                        LinkInputAction.WEB_CRAWL -> onWebCrawl(it)
                        LinkInputAction.SAVE_INFO -> onSaveInfo(it)
                    }
                }
            }
        ) {
            Column {
                TextField(
                    value = inputLink,
                    onValueChange = { inputLink = it },
                    label = stringResource(
                        if (inputAction == LinkInputAction.WEB_CRAWL) R.string.enter_url_hint else R.string.main_url_example
                    ),
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
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = confirmText,
                        onClick = {
                            val input = inputLink.trim()
                            val hasLink = if (inputAction == LinkInputAction.WEB_CRAWL) {
                                UrlUtils.extractFirstUrl(input) != null
                            } else {
                                XhsUrlParser.extractLinks(input).isNotEmpty()
                            }
                            when {
                                input.isEmpty() -> Toast.makeText(context, pleaseEnterUrl, Toast.LENGTH_SHORT).show()
                                !hasLink -> Toast.makeText(context, invalidLink, Toast.LENGTH_SHORT).show()
                                else -> {
                                    submittedLink = input
                                    onShowInputDialogChange(false)
                                }
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

/**
 * 任务 Cell 组件
 */
@Composable
private fun TaskCell(
    task: com.neoruaa.xhsdn.data.DownloadTask,
    downloadSpeeds: StateFlow<Map<Long, Long>>,
    modifier: Modifier = Modifier,
    mediaItems: List<MediaItem> = emptyList(),
    onCopyUrl: () -> Unit,
    onBrowseUrl: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onWebCrawl: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    val statusColor = when (task.status) {
        TaskStatus.FAILED, TaskStatus.PARTIAL -> MiuixTheme.colorScheme.error
        TaskStatus.COMPLETED, TaskStatus.SKIPPED -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    var showActions by remember(task.id) { mutableStateOf(false) }
    var deleteAfterMenuDismiss by remember(task.id) { mutableStateOf(false) }
    val statusText = stringResource(when (task.status) {
        TaskStatus.QUEUED -> R.string.task_status_queued
        TaskStatus.RESOLVING -> R.string.download_resolving
        TaskStatus.DOWNLOADING -> R.string.task_status_downloading
        TaskStatus.COMPLETED -> R.string.task_status_completed
        TaskStatus.FAILED -> R.string.task_status_failed
        TaskStatus.WAITING_FOR_USER -> R.string.download_select
        TaskStatus.PAUSED -> R.string.download_paused
        TaskStatus.PARTIAL -> R.string.download_partial
        TaskStatus.CANCELLED -> R.string.download_cancelled
        TaskStatus.SKIPPED -> R.string.download_skipped
    })

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
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = 560f
                ),
                alignment = Alignment.TopStart
            )
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClickLabel = stringResource(R.string.more_options),
                onLongClick = { showActions = true }
            )
            .padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
                text = task.displayTitle,
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

            // 进度条（仅下载中显示）
            if (task.totalFiles > 0 && task.status == com.neoruaa.xhsdn.data.TaskStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
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
            }
        }

        // Keep the scroll viewport edge-to-edge and its content inset aligned with the text.
        if (mediaItems.isNotEmpty()) {
            val previewScroll = rememberScrollState()
            val cardColor = MiuixTheme.colorScheme.surfaceVariant
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .drawWithCache {
                        val edgeWidth = 16.dp.toPx().coerceAtMost(size.width / 2)
                        val edgeSize = androidx.compose.ui.geometry.Size(edgeWidth, size.height)
                        val transparent = cardColor.copy(alpha = 0f)
                        val leftFade = Brush.horizontalGradient(listOf(cardColor, transparent), endX = edgeWidth)
                        val rightStart = size.width - edgeWidth
                        val rightFade = Brush.horizontalGradient(listOf(transparent, cardColor), startX = rightStart, endX = size.width)
                        onDrawWithContent {
                            drawContent()
                            val before = previewScroll.value.toFloat()
                            val after = (previewScroll.maxValue - previewScroll.value).toFloat()
                            val leftOverflow = if (isRtl) after else before
                            val rightOverflow = if (isRtl) before else after
                            if (leftOverflow > 0) drawRect(leftFade, size = edgeSize,
                                alpha = (leftOverflow / edgeWidth).coerceIn(0f, 1f))
                            if (rightOverflow > 0) drawRect(rightFade, topLeft = Offset(rightStart, 0f), size = edgeSize,
                                alpha = (rightOverflow / edgeWidth).coerceIn(0f, 1f))
                        }
                    }
                    .horizontalScroll(previewScroll)
                    .padding(horizontal = 16.dp)
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

        val statusMessage = task.errorMessage?.takeIf(String::isNotBlank)
        val hasTaskActions = task.status != TaskStatus.COMPLETED && task.status != TaskStatus.SKIPPED
        if (statusMessage != null || hasTaskActions) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.status == TaskStatus.DOWNLOADING) {
                    TaskDownloadSpeed(task.id, downloadSpeeds, Modifier.weight(1f))
                } else if (statusMessage != null) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (hasTaskActions) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (task.isActive || task.status == TaskStatus.PAUSED) {
                            ActionIconButton(imageVector = MiuixIcons.Regular.Close, contentDescription = stringResource(R.string.download_cancel), onClick = onCancel)
                        }
                        if (task.status in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
                            ActionIconButton(imageVector = MiuixIcons.Regular.Pause, contentDescription = stringResource(R.string.download_pause), onClick = onStop)
                        }
                        if (task.status in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_FOR_USER)) {
                            ActionIconButton(imageVector = if (task.status == TaskStatus.WAITING_FOR_USER) MiuixIcons.Regular.SelectAll else MiuixIcons.Regular.Play,
                                contentDescription = stringResource(if (task.status == TaskStatus.WAITING_FOR_USER) R.string.download_select else R.string.download_resume), onClick = onContinue)
                        }
                        if (task.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.CANCELLED)) {
                            ActionIconButton(imageVector = MiuixIcons.Regular.Refresh, contentDescription = stringResource(R.string.retry), onClick = onRetry)
                            ActionIconButton(imageVector = MiuixIcons.Regular.Link, contentDescription = stringResource(R.string.web_crawl_option), onClick = onWebCrawl)
                        }
                    }
                }
            }
        }
        WindowListPopup(
            show = showActions,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showActions = false },
            onDismissFinished = {
                if (deleteAfterMenuDismiss) {
                    deleteAfterMenuDismiss = false
                    onDelete()
                }
            }
        ) {
            ListPopupColumn {
                val actions = listOf(
                    DropdownItem(
                        text = stringResource(R.string.common_copy_link),
                        icon = { Icon(MiuixIcons.Regular.Copy, contentDescription = null, modifier = it) }
                    ),
                    DropdownItem(
                        text = stringResource(R.string.delete_content_description),
                        icon = { Icon(MiuixIcons.Regular.Delete, contentDescription = null, modifier = it) }
                    )
                )
                actions.forEachIndexed { index, action ->
                    DropdownImpl(
                        item = action,
                        optionSize = actions.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showActions = false
                            if (index == 0) onCopyUrl() else deleteAfterMenuDismiss = true
                        }
                    )
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
private fun TaskDownloadSpeed(
    taskId: Long,
    downloadSpeeds: StateFlow<Map<Long, Long>>,
    modifier: Modifier = Modifier
) {
    val bytesPerSecond by remember(taskId, downloadSpeeds) {
        downloadSpeeds.map { it[taskId] ?: 0L }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0L)
    Text(
        text = stringResource(R.string.task_download_speed,
            android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytesPerSecond)),
        modifier = modifier,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

@Composable
private fun rememberThumbnail(item: MediaItem): ImageBitmap? {
    val context = LocalContext.current
    // 先检查缓存
    val cachedBitmap = thumbnailCache.get(item.path)
    if (cachedBitmap != null) {
        return cachedBitmap
    }
    
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = item.path) {
        value = withContext(Dispatchers.IO) {
            // 再次检查缓存（可能在等待期间被其他协程加载）
            thumbnailCache.get(item.path)?.let { return@withContext it }
            
            val bitmap = runCatching {
                when (item.type) {
                    MediaType.IMAGE -> context.decodeSampledBitmap(item.media, 200, 200)?.asImageBitmap()
                    MediaType.VIDEO -> context.createVideoThumbnail(item.media, 200, 200)?.asImageBitmap()
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
