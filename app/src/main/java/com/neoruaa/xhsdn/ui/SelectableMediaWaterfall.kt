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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoruaa.xhsdn.feature.detail.DetailMediaCard
import com.neoruaa.xhsdn.domain.download.MediaTransferProgress
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.XHSApplication
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
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
import kotlin.math.roundToInt
import kotlin.math.max

private val waterfallThumbnailDispatcher = Dispatchers.IO.limitedParallelism(4)

@Composable
fun DetailMediaWaterfall(
    modifier: Modifier = Modifier,
    mediaItems: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit,
    cards: List<DetailMediaCard> = emptyList(),
    taskId: Long? = null,
) {
    val entries = remember(cards, mediaItems) { cards.ifEmpty { mediaItems.map { DetailMediaCard(it.path, stored = it) } } }
    StagedBalancedTwoLaneLayout(
        modifier = modifier,
        items = entries,
        itemKey = { it.key }
    ) { card, layoutReady, imageVisible, loadEpoch, onThumbnailLoadComplete ->
        val stored = card.stored
        if (stored != null) {
            DetailMediaPreview(
                item = stored,
                onClick = { onMediaClick(stored) },
                onDelete = { onDeleteMedia(stored) },
                thumbnailLayoutReady = layoutReady,
                thumbnailVisible = imageVisible,
                thumbnailLoadEpoch = loadEpoch,
                onThumbnailLoadComplete = onThumbnailLoadComplete
            )
        } else {
            PendingMediaPreview(card = card, taskId = taskId, onThumbnailLoadComplete = onThumbnailLoadComplete)
        }
    }
}

@Composable
private fun PendingMediaPreview(
    modifier: Modifier = Modifier,
    card: DetailMediaCard,
    taskId: Long?,
    onThumbnailLoadComplete: () -> Unit,
) {
    val thumbnail = rememberSelectableThumbnail(CachedMediaItem(card.key, "", card.type, card.previewUrl, card.width, card.height, card.live))
    val onLoaded by rememberUpdatedState(onThumbnailLoadComplete)
    LaunchedEffect(thumbnail.isComplete) { if (thumbnail.isComplete) onLoaded() }
    val ratio = if (card.width > 0 && card.height > 0) (card.width.toFloat() / card.height).coerceIn(0.5f, 2f)
        else thumbnail.bitmap.aspectRatioOrDefault()
    Column(modifier.squircleSurface(MiuixTheme.colorScheme.surfaceVariant, 18.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
            SelectablePlaceholderMedia(card.type)
            thumbnail.bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        MediaTransferFooter(card = card, taskId = taskId, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun MediaTransferFooter(modifier: Modifier = Modifier, card: DetailMediaCard, taskId: Long?) {
    val context = LocalContext.current
    val queue = remember(context) { (context.applicationContext as XHSApplication).appContainer.downloadQueue }
    val progress by remember(taskId, card.transferIds, card.checkpoint) {
        queue.mediaProgress.map { tasks ->
            val active = tasks[taskId]
            MediaTransferProgress.combine(card.transferIds.map { active?.get(it) ?: card.checkpoint[it] ?: MediaTransferProgress() })
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = MediaTransferProgress.combine(card.transferIds.map { card.checkpoint[it] ?: MediaTransferProgress() }))
    val failed = (card.resourceFailed && card.taskStatus == TaskStatus.PARTIAL) || progress.failed || card.taskStatus == TaskStatus.FAILED
    val paused = card.taskStatus == TaskStatus.PAUSED
    val cancelled = card.taskStatus == TaskStatus.CANCELLED
    val status = when {
        cancelled -> R.string.detail_transfer_cancelled
        paused -> R.string.detail_transfer_paused
        failed -> R.string.detail_transfer_failed
        card.taskStatus == TaskStatus.WAITING_FOR_USER -> R.string.detail_transfer_selection
        progress.complete -> if (card.live) R.string.detail_transfer_merging else R.string.detail_transfer_saving
        progress.downloaded > 0 -> R.string.detail_transfer_downloading
        else -> R.string.detail_transfer_waiting
    }
    val fraction = progress.fraction
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(status), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            if (fraction != null) Text(stringResource(R.string.detail_transfer_percent, (fraction * 100).toInt()), style = MiuixTheme.textStyles.footnote1)
        }
        if (fraction != null) {
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        } else if (!failed && !paused && !cancelled && card.taskStatus == TaskStatus.DOWNLOADING && progress.downloaded > 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (progress.downloaded > 0 || progress.total > 0) {
            val received = android.text.format.Formatter.formatShortFileSize(context, progress.downloaded)
            Text(if (progress.total > 0) stringResource(R.string.detail_transfer_bytes, received,
                android.text.format.Formatter.formatShortFileSize(context, progress.total)) else received,
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun SelectableMediaWaterfall(
    modifier: Modifier = Modifier,
    items: List<CachedMediaItem>,
    selectedPaths: Set<String>,
    onToggle: (String) -> Unit
) {
    val density = LocalDensity.current
    val contentDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    val primaryHeight = textMeasurer.measure(stringResource(R.string.selective_dimensions, 1200, 800),
        style = MiuixTheme.textStyles.body1).size.height
    val secondaryHeight = textMeasurer.measure(stringResource(R.string.selective_type_live),
        style = MiuixTheme.textStyles.footnote1).size.height
    val footerHeight = with(density) { (primaryHeight + secondaryHeight).toDp() }.coerceAtLeast(26.dp) + 20.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spacing = with(density) { 10.dp.roundToPx() }
        val columnWidth = (constraints.maxWidth - spacing).coerceAtLeast(0) / 2
        val footerPixels = with(density) { footerHeight.roundToPx() }
        val placement = remember(items, columnWidth, footerPixels, spacing) {
            calculateBalancedWaterfallPlacement(items.map {
                (columnWidth / selectableAspectRatio(it)).roundToInt() + footerPixels
            }, spacing)
        }
        // Native staggered-grid placement is greedy too. Mirroring its lane order
        // matches the detail page without composing offscreen thumbnails to measure them.
        val laneDirection = if (placement.swapVisualLanes) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides laneDirection) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                // Equal pixel widths keep measured heights consistent with the lane calculation.
                modifier = Modifier.width(with(density) { (columnWidth * 2 + spacing).toDp() }).miuixVerticalScrollEffects(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(items, key = { it.path }) { item ->
                    CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                        SelectableMediaPreview(
                            item = item,
                            selected = item.path in selectedPaths,
                            onToggle = { onToggle(item.path) },
                            fixedAspectRatio = selectableAspectRatio(item),
                            footerHeight = footerHeight,
                        )
                    }
                }
            }
        }
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
        val swapVisualLanes = placement.swapVisualLanes
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
) {
    val swapVisualLanes: Boolean get() = rightHeight > leftHeight
}

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
    var deleteAfterDismiss by remember { mutableStateOf(false) }
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

    WindowDialog(
        title = stringResource(R.string.delete_file_dialog_title),
        summary = stringResource(R.string.delete_file_dialog_message, fileName),
        show = showDeleteDialog,
        onDismissRequest = { showDeleteDialog = false },
        onDismissFinished = {
            if (deleteAfterDismiss) {
                deleteAfterDismiss = false
                onDelete()
            }
        }
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
                    deleteAfterDismiss = true
                    showDeleteDialog = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
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
    onThumbnailLoadComplete: () -> Unit = {},
    fixedAspectRatio: Float? = null,
    footerHeight: Dp? = null,
) {
    val thumbnailState = rememberSelectableThumbnail(item)
    val bitmap = thumbnailState.bitmap
    val currentLoadCompleteCallback by rememberUpdatedState(onThumbnailLoadComplete)
    LaunchedEffect(thumbnailState.isComplete, item.path, thumbnailLoadEpoch) {
        if (thumbnailState.isComplete) currentLoadCompleteCallback()
    }
    val aspectRatio = fixedAspectRatio ?: if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.5f, 2f) else if (thumbnailLayoutReady) bitmap.aspectRatioOrDefault() else 0.75f
    val overlayResId = remember(item.path, item.type) { selectableOverlayResId(item) }
    val context = LocalContext.current
    val sourceSize by produceState(item.sizeBytes, item.path, item.sizeUrl, item.sizeBytes) {
        if (value <= 0 && item.sizeUrl.isNotBlank()) {
            value = remoteMediaSize(context, item.sizeUrl) ?: 0
        }
    }
    val fileSize = when {
        sourceSize > 0 -> android.text.format.Formatter.formatShortFileSize(context, sourceSize)
        item.width > 0 && item.height > 0 -> stringResource(R.string.selective_dimensions, item.width, item.height)
        else -> stringResource(R.string.selective_size_unknown)
    }
    val mediaType = stringResource(when {
        item.live -> R.string.selective_type_live
        item.cover -> R.string.selective_type_cover
        item.type == MediaType.VIDEO -> R.string.selective_type_video
        else -> R.string.selective_type_image
    })

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
                            contentDescription = item.displayName,
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
                .then(if (footerHeight != null) Modifier.height(footerHeight) else Modifier)
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
                Column(Modifier.weight(1f)) {
                    Text(text = fileSize, maxLines = 1, style = MiuixTheme.textStyles.body1)
                    Text(text = mediaType, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
                modifier = Modifier.size(26.dp)
            )
        }
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
    val context = LocalContext.current
    val state = produceState(ThumbnailLoadState(false, null), item.path, item.previewUrl) {
        val bitmap = withContext(waterfallThumbnailDispatcher) {
            if (item.previewUrl.isNotBlank()) remoteThumbnail(context, item.previewUrl)?.asImageBitmap()
            else runCatching {
                val file = File(item.path)
                if (!file.exists()) null else when (item.type) {
                    MediaType.IMAGE -> decodeSampledBitmap(file.path, 600, 600)?.asImageBitmap()
                    MediaType.VIDEO -> createVideoThumbnail(file, 600, 600)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
        }
        value = ThumbnailLoadState(true, bitmap)
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
    if (item.live) return true
    if (item.type != MediaType.IMAGE) {
        return false
    }
    val fileName = File(item.path).name.lowercase()
    return "_live." in fileName || "_live_" in fileName
}

internal fun selectableAspectRatio(item: CachedMediaItem): Float =
    if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.5f, 2f) else 0.75f
