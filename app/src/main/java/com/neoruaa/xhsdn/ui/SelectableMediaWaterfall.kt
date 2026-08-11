package com.neoruaa.xhsdn.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.utils.createVideoThumbnail
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.storedMediaSize
import com.neoruaa.xhsdn.viewmodels.CachedMediaItem
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlin.math.max

private val waterfallThumbnailDispatcher = Dispatchers.IO.limitedParallelism(4)

@Composable
fun DetailMediaWaterfall(
    modifier: Modifier = Modifier,
    mediaItems: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit
) {
    StagedBalancedTwoLaneLayout(
        modifier = modifier,
        items = mediaItems,
        itemKey = MediaItem::path
    ) { item, layoutReady, imageVisible, loadEpoch, onThumbnailLoadComplete ->
        DetailMediaPreview(
            item = item,
            onClick = { onMediaClick(item) },
            onDelete = { onDeleteMedia(item) },
            thumbnailLayoutReady = layoutReady,
            thumbnailVisible = imageVisible,
            thumbnailLoadEpoch = loadEpoch,
            onThumbnailLoadComplete = onThumbnailLoadComplete
        )
    }
}

@Composable
fun SelectableMediaWaterfall(
    modifier: Modifier = Modifier,
    items: List<CachedMediaItem>,
    selectedPaths: Set<String>,
    onToggle: (String) -> Unit
) {
    StagedBalancedTwoLaneLayout(
        modifier = modifier,
        items = items,
        itemKey = CachedMediaItem::path
    ) { item, layoutReady, imageVisible, loadEpoch, onThumbnailLoadComplete ->
        SelectableMediaPreview(
            item = item,
            selected = selectedPaths.contains(item.path),
            onToggle = { onToggle(item.path) },
            thumbnailLayoutReady = layoutReady,
            thumbnailVisible = imageVisible,
            thumbnailLoadEpoch = loadEpoch,
            onThumbnailLoadComplete = onThumbnailLoadComplete
        )
    }
}

@Composable
private fun <T> StagedBalancedTwoLaneLayout(
    modifier: Modifier = Modifier,
    items: List<T>,
    itemKey: (T) -> Any,
    itemContent: @Composable (
        item: T,
        layoutReady: Boolean,
        imageVisible: Boolean,
        loadEpoch: Any,
        onThumbnailLoadComplete: () -> Unit
    ) -> Unit
) {
    val itemKeys = remember(items) { items.map(itemKey) }
    val itemIndices = remember(itemKeys) {
        itemKeys.withIndex().associate { (index, key) -> key to index }
    }
    val loadEpoch = remember(itemKeys) { Any() }
    val completedKeys = remember(loadEpoch) { mutableStateMapOf<Any, Boolean>() }
    val allThumbnailsLoaded = itemKeys.all { completedKeys[it] == true }
    var revealedItemCount by remember(itemKeys) { mutableIntStateOf(0) }

    LaunchedEffect(allThumbnailsLoaded, itemKeys) {
        revealedItemCount = 0
        if (allThumbnailsLoaded && itemKeys.isNotEmpty()) {
            delay(70)
            val staggerMillis = (600L / itemKeys.size).coerceIn(12L, 55L)
            itemKeys.indices.forEach { index ->
                revealedItemCount = index + 1
                if (index < itemKeys.lastIndex) delay(staggerMillis)
            }
        }
    }

    BalancedTwoLaneLayout(
        modifier = modifier,
        items = items,
        itemKey = itemKey
    ) { item ->
        val key = itemKey(item)
        val index = itemIndices.getValue(key)
        itemContent(
            item,
            allThumbnailsLoaded,
            index < revealedItemCount,
            loadEpoch
        ) {
            completedKeys[key] = true
        }
    }
}

@Composable
private fun <T> BalancedTwoLaneLayout(
    modifier: Modifier = Modifier,
    items: List<T>,
    itemKey: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            items.forEach { item ->
                key(itemKey(item)) {
                    itemContent(item)
                }
            }
        }
    ) { measurables, constraints ->
        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            constraints.minWidth
        }
        val spacing = 10.dp.roundToPx()
        val columnWidth = ((layoutWidth - spacing).coerceAtLeast(0)) / 2
        val itemConstraints = Constraints.fixedWidth(columnWidth)
        val placeables = measurables.map { it.measure(itemConstraints) }
        val placement = calculateBalancedWaterfallPlacement(
            itemHeights = placeables.map { it.height },
            spacing = spacing
        )
        val swapVisualLanes = placement.rightHeight > placement.leftHeight
        val layoutHeight = max(placement.leftHeight, placement.rightHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            placeables.forEachIndexed { index, placeable ->
                val assignedLane = placement.lanes[index]
                val visualLane = if (swapVisualLanes) 1 - assignedLane else assignedLane
                val x = if (visualLane == 0) 0 else layoutWidth - columnWidth
                placeable.place(x, placement.yOffsets[index])
            }
        }
    }
}

internal data class WaterfallPlacement(
    val lanes: List<Int>,
    val yOffsets: List<Int>,
    val leftHeight: Int,
    val rightHeight: Int
)

internal fun calculateBalancedWaterfallPlacement(
    itemHeights: List<Int>,
    spacing: Int
): WaterfallPlacement {
    require(spacing >= 0)
    var leftHeight = 0
    var rightHeight = 0
    var leftCount = 0
    var rightCount = 0
    val lanes = ArrayList<Int>(itemHeights.size)
    val yOffsets = ArrayList<Int>(itemHeights.size)

    itemHeights.forEach { itemHeight ->
        require(itemHeight >= 0)
        if (leftHeight <= rightHeight) {
            val y = if (leftCount == 0) 0 else leftHeight + spacing
            lanes += 0
            yOffsets += y
            leftHeight = y + itemHeight
            leftCount++
        } else {
            val y = if (rightCount == 0) 0 else rightHeight + spacing
            lanes += 1
            yOffsets += y
            rightHeight = y + itemHeight
            rightCount++
        }
    }

    return WaterfallPlacement(
        lanes = lanes,
        yOffsets = yOffsets,
        leftHeight = leftHeight,
        rightHeight = rightHeight
    )
}

@Composable
fun DetailMediaPreview(
    modifier: Modifier = Modifier,
    item: MediaItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    thumbnailLayoutReady: Boolean = true,
    thumbnailVisible: Boolean = true,
    thumbnailLoadEpoch: Any = Unit,
    onThumbnailLoadComplete: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val thumbnailState = rememberStoredThumbnail(item)
    val bitmap = thumbnailState.bitmap
    val currentLoadCompleteCallback by rememberUpdatedState(onThumbnailLoadComplete)
    LaunchedEffect(thumbnailState.isComplete, item.path, thumbnailLoadEpoch) {
        if (thumbnailState.isComplete) currentLoadCompleteCallback()
    }
    val aspectRatio = if (thumbnailLayoutReady) bitmap.aspectRatioOrDefault() else 0.75f
    val overlayResId = remember(item.path, item.type) { storedOverlayResId(item) }
    val fileName = item.media.displayName
    val fileSize = rememberStoredFileSize(item)

    Column(
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 18.dp
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            SelectablePlaceholderMedia(type = item.type)
            androidx.compose.animation.AnimatedVisibility(
                visible = thumbnailVisible && bitmap != null,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(220)) +
                    scaleIn(animationSpec = tween(260), initialScale = 0.985f),
                exit = fadeOut(animationSpec = tween(100))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = item.path,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (bitmap != null && overlayResId != null) {
                        Image(
                            painter = painterResource(id = overlayResId),
                            contentDescription = null,
                            modifier = Modifier.size(60.dp)
                        )
                    }
                }
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
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
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
                    .size(24.dp)
                    .clickable { showDeleteDialog = true },
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
    }

    if (showDeleteDialog) {
        WindowDialog(
            title = stringResource(R.string.delete_file_dialog_title),
            summary = stringResource(R.string.delete_file_dialog_message, fileName),
            show = showDeleteDialog,
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
                Spacer(Modifier.size(12.dp))
                TextButton(
                    text = stringResource(R.string.apply),
                    onClick = {
                        onDelete()
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
fun SelectableMediaPreview(
    modifier: Modifier = Modifier,
    item: CachedMediaItem,
    selected: Boolean,
    onToggle: () -> Unit,
    thumbnailLayoutReady: Boolean = true,
    thumbnailVisible: Boolean = true,
    thumbnailLoadEpoch: Any = Unit,
    onThumbnailLoadComplete: () -> Unit = {}
) {
    val thumbnailState = rememberSelectableThumbnail(item)
    val bitmap = thumbnailState.bitmap
    val currentLoadCompleteCallback by rememberUpdatedState(onThumbnailLoadComplete)
    LaunchedEffect(thumbnailState.isComplete, item.path, thumbnailLoadEpoch) {
        if (thumbnailState.isComplete) currentLoadCompleteCallback()
    }
    val aspectRatio = if (thumbnailLayoutReady) bitmap.aspectRatioOrDefault() else 0.75f
    val overlayResId = remember(item.path, item.type) { selectableOverlayResId(item) }
    val fileSize = remember(item.path) { selectableFileSize(item.path) }

    Column(
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 18.dp
            )
            .clickable { onToggle() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            SelectablePlaceholderMedia(type = item.type)
            androidx.compose.animation.AnimatedVisibility(
                visible = thumbnailVisible && bitmap != null,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(220)) +
                    scaleIn(animationSpec = tween(260), initialScale = 0.985f),
                exit = fadeOut(animationSpec = tween(100))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = item.path,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (bitmap != null && overlayResId != null) {
                        Image(
                            painter = painterResource(id = overlayResId),
                            contentDescription = null,
                            modifier = Modifier.size(60.dp)
                        )
                    }
                }
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
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = fileSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

private fun selectableFileSize(path: String): String {
    val file = File(path)
    return if (file.exists()) {
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

@Composable
private fun SelectablePlaceholderMedia(type: MediaType) {
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

@Composable
private fun rememberStoredThumbnail(item: MediaItem): ThumbnailLoadState {
    val context = LocalContext.current
    val state = produceState(
        initialValue = ThumbnailLoadState(isComplete = false, bitmap = null),
        item.path,
        item.type
    ) {
        val bitmap = withContext(waterfallThumbnailDispatcher) {
            runCatching {
                when (item.type) {
                    MediaType.IMAGE -> context.decodeSampledBitmap(item.media, 720, 720)?.asImageBitmap()
                    MediaType.VIDEO -> context.createVideoThumbnail(item.media, 720, 720)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
        }
        value = ThumbnailLoadState(isComplete = true, bitmap = bitmap)
    }
    return state.value
}

@Composable
private fun rememberStoredFileSize(item: MediaItem): String {
    val context = LocalContext.current
    val state = produceState<Long?>(initialValue = item.media.sizeBytes.takeIf { it > 0L }, item.path) {
        value = withContext(Dispatchers.IO) { context.storedMediaSize(item.media) }
    }
    return formatFileSize(state.value)
}

private fun formatFileSize(size: Long?): String = when {
    size == null -> "--"
    size > 1024L * 1024L * 1024L -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
    size > 1024L * 1024L -> "%.1f MB".format(size / (1024.0 * 1024.0))
    size > 1024L -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}

private fun storedOverlayResId(item: MediaItem): Int? = when {
    item.type == MediaType.VIDEO -> R.drawable.play_button_overlay
    item.type == MediaType.IMAGE && (
        "_live." in item.media.displayName.lowercase() ||
            "_live_" in item.media.displayName.lowercase()
        ) -> R.drawable.live_photo_overlay
    else -> null
}

@Composable
private fun rememberSelectableThumbnail(item: CachedMediaItem): ThumbnailLoadState {
    val state = produceState(
        initialValue = ThumbnailLoadState(isComplete = false, bitmap = null),
        item.path,
        item.type
    ) {
        val bitmap = withContext(waterfallThumbnailDispatcher) {
            val file = File(item.path)
            if (!file.exists()) return@withContext null
            runCatching {
                when (item.type) {
                    MediaType.IMAGE -> decodeSampledBitmap(file.path, 720, 720)?.asImageBitmap()
                    MediaType.VIDEO -> createVideoThumbnail(file, 720, 720)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
        }
        value = ThumbnailLoadState(isComplete = true, bitmap = bitmap)
    }
    return state.value
}

private data class ThumbnailLoadState(
    val isComplete: Boolean,
    val bitmap: ImageBitmap?
)

private fun ImageBitmap?.aspectRatioOrDefault(): Float {
    val bitmap = this ?: return 0.75f
    return if (bitmap.width > 0 && bitmap.height > 0) {
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } else {
        0.75f
    }
}

private fun selectableOverlayResId(item: CachedMediaItem): Int? {
    return when {
        item.type == MediaType.VIDEO -> R.drawable.play_button_overlay
        isSelectableLivePhotoItem(item) -> R.drawable.live_photo_overlay
        else -> null
    }
}

private fun isSelectableLivePhotoItem(item: CachedMediaItem): Boolean {
    if (item.type != MediaType.IMAGE) {
        return false
    }
    val fileName = File(item.path).name.lowercase()
    return "_live." in fileName || "_live_" in fileName
}
