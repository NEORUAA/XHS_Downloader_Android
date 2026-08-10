package com.neoruaa.xhsdn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.DetailMediaWaterfall
import com.neoruaa.xhsdn.ui.rememberWindowLayoutInfo
import com.neoruaa.xhsdn.viewmodels.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.text.selection.SelectionContainer
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import com.neoruaa.xhsdn.utils.detectMediaType
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.createVideoThumbnail
import com.neoruaa.xhsdn.utils.deleteStoredMedia
import com.neoruaa.xhsdn.utils.storedMediaExists
import com.neoruaa.xhsdn.data.tasks.TaskManager
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Play
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.neoruaa.xhsdn.ui.rememberOffsetPopupPositionProvider
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleSurface

data class DetailUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val taskTitle: String = "",
    val isDownloading: Boolean = false,
    val noteContent: String? = null
)

class DetailActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_TITLE = "task_title"
        private const val EXTRA_FILE_PATHS = "file_paths"
        private const val EXTRA_NOTE_CONTENT = "note_content"
        private const val EXTRA_NOTE_URL = "note_url"

        fun newIntent(context: Context, taskId: String, taskTitle: String, filePaths: List<String>, noteContent: String? = null, noteUrl: String? = null): Intent {
            return Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_TITLE, taskTitle)
                putStringArrayListExtra(EXTRA_FILE_PATHS, ArrayList(filePaths))
                putExtra(EXTRA_NOTE_CONTENT, noteContent)
                putExtra(EXTRA_NOTE_URL, noteUrl)
            }
        }

        fun newIntent(context: Context, taskId: Long, taskTitle: String, filePaths: List<String>, noteContent: String? = null, noteUrl: String? = null): Intent {
            return newIntent(context, taskId.toString(), taskTitle, filePaths, noteContent, noteUrl)
        }
    }

    private val viewModel: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }
    private var taskId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            )
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !isNightMode

        // 获取传递的数据
        val filePaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
        taskId = intent.getStringExtra(EXTRA_TASK_ID)?.toLongOrNull()
        val noteContent = intent.getStringExtra(EXTRA_NOTE_CONTENT)
        val noteUrl = intent.getStringExtra(EXTRA_NOTE_URL) // Get the note URL

        // 构建媒体项列表
        val mediaItems = taskId
            ?.let(TaskManager::getTaskById)
            ?.mediaRefs
            ?.map(::MediaItem)
            ?.takeIf { it.isNotEmpty() }
            ?: filePaths.map { path -> MediaItem(path, detectMediaType(path)) }

        // 更新UI状态
        viewModel.updateState(
            DetailUiState(
                mediaItems = mediaItems,
                taskTitle = getString(R.string.download_detail_title), // Use context to get string resource
                isDownloading = false,
                noteContent = noteContent
            )
        )

        setContent {
            val controller = ThemeController(ColorSchemeMode.System)
            val uiState by viewModel.state.collectAsStateWithLifecycle()
            val topBarState = rememberTopAppBarState()
            MiuixTheme(controller = controller) {
                DetailScreen(
                    uiState = uiState,
                    onBack = { finish() },
                    onMediaClick = { openFile(it) },
                    onDeleteMedia = { mediaItem ->
                        if (deleteStoredMedia(mediaItem.media)) {
                            taskId?.let { id -> TaskManager.removeMediaRef(id, mediaItem.path) }
                            viewModel.removeMediaItem(mediaItem)
                        } else {
                            showToast(getString(R.string.delete_file_failed, mediaItem.media.displayName))
                        }
                    },
                    onCopyUrl = {
                        // Copy URL to clipboard directly in DetailActivity
                        if (!noteUrl.isNullOrEmpty()) {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("xhs_url", noteUrl))
                            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onWebCrawl = {
                        // Launch web crawl directly - task will be created in WebViewActivity when user clicks "爬取"
                        if (!noteUrl.isNullOrEmpty()) {
                            val cleanUrl = com.neoruaa.xhsdn.utils.UrlUtils.extractFirstUrl(noteUrl)
                            if (cleanUrl != null) {
                                // Launch WebViewActivity for the web crawl
                                val webViewIntent = Intent(this, WebViewActivity::class.java).apply {
                                    putExtra("url", cleanUrl)
                                    // Don't pass task_id here - let WebViewActivity create the task when user clicks "爬取"
                                }
                                // Start WebViewActivity for result - DetailActivity will receive the result
                                // and then finish, allowing MainActivity's onActivityResult to eventually handle it
                                startActivityForResult(webViewIntent, MainActivity.WEBVIEW_REQUEST_CODE)
                                finish() // Close DetailActivity and return to MainActivity
                            } else {
                                Toast.makeText(this, R.string.no_valid_link_found, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    topBarState = topBarState
                )
            }
        }
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
            clipData = ClipData.newRawUri(item.media.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kotlin.runCatching { startActivity(intent) }.onFailure {
            showToast(getString(R.string.unable_to_open_file_error, it.message))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

}

@Composable
private fun DetailScreen(
    uiState: DetailUiState,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit,
    onCopyUrl: () -> Unit,
    onWebCrawl: () -> Unit,
    topBarState: TopAppBarState
) {
    val scrollBehavior = MiuixScrollBehavior(state = topBarState)
    val windowLayoutInfo = rememberWindowLayoutInfo()

    // Actions menu state
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        topBar = {
            AdaptiveTopAppBar(
                title = uiState.taskTitle,
                isWideScreen = windowLayoutInfo.isWideScreen,
                navigationIcon = {
                    TopAppBarIconButton(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back_content_description),
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 4.dp)
                    )
                },
                actions = {
                    Box(
                        modifier = Modifier.padding(end = 4.dp),
                            contentAlignment = Alignment.Center
                    ) {
                        TopAppBarIconButton(
                            imageVector = MiuixIcons.MoreCircle,
                            contentDescription = stringResource(R.string.more_options),
                            onClick = { menuExpanded = true }
                        )

                        val menuItems = listOf(
                            stringResource(R.string.copy_link),
//                            stringResource(R.string.web_crawl_action)
                        )

                        WindowListPopup(
                            show = menuExpanded,
                            popupPositionProvider = rememberOffsetPopupPositionProvider(x = (-20).dp),
                            alignment = PopupPositionProvider.Align.TopEnd,
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
                                            if (index == 0) {
                                                onCopyUrl()
                                            } else if (index == 1) {
                                                onWebCrawl()
                                            }
                                        },
                                        index = index
                                    )
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        FilesPage(
            uiState = uiState,
            onMediaClick = onMediaClick,
            onDeleteMedia = onDeleteMedia,
            topPadding = padding.calculateTopPadding(),
            contentStartPadding = windowLayoutInfo.contentStartPadding,
            contentEndPadding = windowLayoutInfo.contentEndPadding,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        )
    }
}

@Composable
private fun FilesPage(
    uiState: DetailUiState,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    contentStartPadding: androidx.compose.ui.unit.Dp,
    contentEndPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            top = topPadding,
            start = contentStartPadding + 12.dp,
            end = contentEndPadding + 12.dp
        ),
        modifier = modifier
    ) {

        // ===== 笔记文案（单列 / 满行）=====
        if (uiState.noteContent != null) {
            item(
                key = "note_content_title",
                span = StaggeredGridItemSpan.FullLine
            ) {
                SmallTitle(
                    text = stringResource(R.string.notes_content_title),
                    insideMargin = PaddingValues(start = 12.dp, top = 8.dp)
                )
            }
            item(
                key = "note_content",
                span = StaggeredGridItemSpan.FullLine
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    cornerRadius = 18.dp,
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.background
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = uiState.noteContent.replace("[话题]", ""),
                                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }

        // ===== 已下载文件标题（单列 / 满行）=====
        item(
            key = "downloaded_files_title",
            span = StaggeredGridItemSpan.FullLine
        ) {
            SmallTitle(
                text = stringResource(R.string.downloaded_files_title_lower),
                insideMargin = PaddingValues(12.dp, 0.dp)
            )
        }

        // ===== 空态（单列 / 满行）=====
        if (uiState.mediaItems.isEmpty()) {
            item(
                key = "downloaded_files_empty",
                span = StaggeredGridItemSpan.FullLine
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleBackground(
                            color = MiuixTheme.colorScheme.surfaceVariant,
                            cornerRadius = 18.dp
                        )
                ) {
                    Text(
                        text = stringResource(R.string.no_downloaded_files),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            item(
                key = "downloaded_files",
                span = StaggeredGridItemSpan.FullLine
            ) {
                DetailMediaWaterfall(
                    mediaItems = uiState.mediaItems,
                    onMediaClick = onMediaClick,
                    onDeleteMedia = onDeleteMedia
                )
            }
        }

        item(
            key = "detail_bottom_spacer",
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

@Composable
private fun MediaWaterfall(
    mediaItems: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit
) {
    val columns = rememberBalancedMediaColumns(mediaItems)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MediaColumn(
            items = columns.left,
            modifier = Modifier.weight(1f),
            onMediaClick = onMediaClick,
            onDeleteMedia = onDeleteMedia
        )
        MediaColumn(
            items = columns.right,
            modifier = Modifier.weight(1f),
            onMediaClick = onMediaClick,
            onDeleteMedia = onDeleteMedia
        )
    }
}

@Composable
private fun MediaColumn(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            MediaPreview(
                item = item,
                onClick = { onMediaClick(item) },
                onDelete = onDeleteMedia
            )
        }

        if (items.isEmpty()) {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

private data class MediaColumns(
    val left: List<MediaItem>,
    val right: List<MediaItem>
)

@Composable
private fun rememberBalancedMediaColumns(mediaItems: List<MediaItem>): MediaColumns {
    val state = produceState(initialValue = MediaColumns(mediaItems, emptyList()), mediaItems) {
        value = withContext(Dispatchers.IO) {
            distributeMediaItems(mediaItems)
        }
    }
    return state.value
}

private fun distributeMediaItems(mediaItems: List<MediaItem>): MediaColumns {
    var leftItems = mutableListOf<MediaItem>()
    var rightItems = mutableListOf<MediaItem>()
    var leftHeight = 0f
    var rightHeight = 0f

    mediaItems.forEach { item ->
        val estimatedHeight = estimateMediaCardHeight(item)
        if (leftHeight <= rightHeight) {
            leftItems.add(item)
            leftHeight += estimatedHeight
        } else {
            rightItems.add(item)
            rightHeight += estimatedHeight
        }

        if (rightHeight > leftHeight) {
            val oldLeftItems = leftItems
            leftItems = rightItems
            rightItems = oldLeftItems

            val oldLeftHeight = leftHeight
            leftHeight = rightHeight
            rightHeight = oldLeftHeight
        }
    }

    return MediaColumns(leftItems.toList(), rightItems.toList())
}

private fun estimateMediaCardHeight(item: MediaItem): Float {
    val aspectRatio = readAspectRatio(item)
    val previewHeightWeight = if (aspectRatio != null && aspectRatio > 0f) {
        1f / aspectRatio.coerceIn(0.4f, 2.5f)
    } else {
        1.33f
    }
    return previewHeightWeight + 0.34f
}


@Composable
private fun MediaPreview(item: MediaItem, onClick: () -> Unit, onDelete: (MediaItem) -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val bitmap = rememberThumbnail(item)
    val aspectRatio = rememberAspectRatio(item) ?: 0.75f
    val fileName = remember(item.path) { File(item.path).name }
    val overlayResId = remember(item.path, item.type) { previewOverlayResId(item) }

    Column(
        modifier = Modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 18.dp
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = item.path,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PlaceholderMedia(type = item.type)
            }

            if (bitmap != null && overlayResId != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = overlayResId),
                    contentDescription = null,
                    modifier = Modifier.size(60.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (item.type == MediaType.VIDEO) {
                    Icon(
                        imageVector = MiuixIcons.Play,
                        contentDescription = stringResource(R.string.play_content_description),
                        modifier = Modifier.size(20.dp)
                    )
                }
                val fileSize = remember(item.path) {
                    val file = File(item.path)
                    if (file.exists()) {
                        val size = file.length()
                        when {
                            size > 1024 * 1024 * 1024 -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
                            size > 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
                            size > 1024 -> "%.1f KB".format(size / 1024.0)
                            else -> "$size B"
                        }
                    } else {
                        "--"
                    }
                }
                Text(
                    text = fileSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = stringResource(R.string.delete_content_description),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showDeleteDialog = true }
            )
        }
    }

    if (showDeleteDialog) {
        WindowDialog(
            title = stringResource(R.string.delete_file_dialog_title),
            summary = stringResource(R.string.delete_file_dialog_message, fileName),
            show = true,
            onDismissRequest = { showDeleteDialog = false }
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.apply),
                    onClick = {
                        // 删除文件
                        val file = File(item.path)
                        if (file.exists()) {
                            file.delete()
                        }
                        onDelete(item) // 调用删除回调
                        showDeleteDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun PlaceholderMedia(
    type: MediaType
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (type == MediaType.VIDEO) MiuixIcons.Play else MiuixIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

private fun previewOverlayResId(item: MediaItem): Int? {
    return when {
        item.type == MediaType.VIDEO -> R.drawable.play_button_overlay
        isLivePhotoItem(item) -> R.drawable.live_photo_overlay
        else -> null
    }
}

private fun isLivePhotoItem(item: MediaItem): Boolean {
    if (item.type != MediaType.IMAGE) {
        return false
    }
    val fileName = File(item.path).name.lowercase()
    return "_live." in fileName || "_live_" in fileName
}

@Composable
private fun rememberThumbnail(item: MediaItem): ImageBitmap? {
    // 先检查缓存
    val cachedBitmap = thumbnailCache.get(item.path)
    if (cachedBitmap != null) {
        return cachedBitmap
    }

    val state = produceState<ImageBitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            // 再次检查缓存（可能在等待期间被其他协程加载）
            thumbnailCache.get(item.path)?.let { return@withContext it }

            val file = File(item.path)
            if (!file.exists()) return@withContext null
            val bitmap = runCatching {
                when (item.type) {
                    MediaType.IMAGE -> decodeSampledBitmap(file.path, 720, 720)?.asImageBitmap()
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

@Composable
private fun rememberAspectRatio(item: MediaItem): Float? {
    return remember(item.path) {
        readAspectRatio(item)
    }
}

private fun readAspectRatio(item: MediaItem): Float? {
    return runCatching {
        when (item.type) {
            MediaType.IMAGE -> {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(item.path, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight.toFloat()
                } else {
                    null
                }
            }

            MediaType.VIDEO -> {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.path)
                    val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                    val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                    if (width != null && height != null && height > 0f) width / height else null
                } finally {
                    retriever.release()
                }
            }

            MediaType.OTHER -> null
        }
    }.getOrNull()
}

// 缓存用于缩略图
private val thumbnailCache = object : LinkedHashMap<String, ImageBitmap>(100, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
        return size > 100
    }
}
