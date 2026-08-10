package com.neoruaa.xhsdn.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.utils.createVideoThumbnail
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.readMediaAspectRatio
import com.neoruaa.xhsdn.utils.storedMediaSize
import com.neoruaa.xhsdn.viewmodels.CachedMediaItem
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import kotlinx.coroutines.Dispatchers
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

@Composable
fun DetailMediaWaterfall(
    modifier: Modifier = Modifier,
    mediaItems: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit
) {
    val columns = rememberDetailColumns(mediaItems)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DetailMediaColumn(
            items = columns.left,
            onMediaClick = onMediaClick,
            onDeleteMedia = onDeleteMedia,
            modifier = Modifier.weight(1f)
        )
        DetailMediaColumn(
            items = columns.right,
            onMediaClick = onMediaClick,
            onDeleteMedia = onDeleteMedia,
            modifier = Modifier.weight(1f)
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
    val columns = rememberSelectableColumns(items)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SelectableMediaColumn(
            items = columns.left,
            selectedPaths = selectedPaths,
            onToggle = onToggle,
            modifier = Modifier.weight(1f)
        )
        SelectableMediaColumn(
            items = columns.right,
            selectedPaths = selectedPaths,
            onToggle = onToggle,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DetailMediaColumn(
    items: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onDeleteMedia: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            DetailMediaPreview(
                item = item,
                onClick = { onMediaClick(item) },
                onDelete = { onDeleteMedia(item) }
            )
        }

        if (items.isEmpty()) {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun SelectableMediaColumn(
    items: List<CachedMediaItem>,
    selectedPaths: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            SelectableMediaPreview(
                item = item,
                selected = selectedPaths.contains(item.path),
                onToggle = { onToggle(item.path) }
            )
        }

        if (items.isEmpty()) {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
fun DetailMediaPreview(
    modifier: Modifier = Modifier,
    item: MediaItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val bitmap = rememberStoredThumbnail(item)
    val aspectRatio = rememberStoredAspectRatio(item) ?: 0.75f
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
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = item.path,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SelectablePlaceholderMedia(type = item.type)
            }

            if (bitmap != null && overlayResId != null) {
                Image(
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
    onToggle: () -> Unit
) {
    val bitmap = rememberSelectableThumbnail(item)
    val aspectRatio = rememberSelectableAspectRatio(item) ?: 0.75f
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
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = item.path,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SelectablePlaceholderMedia(type = item.type)
            }

            if (bitmap != null && overlayResId != null) {
                Image(
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

private data class DetailColumns(
    val left: List<MediaItem>,
    val right: List<MediaItem>
)

@Composable
private fun rememberDetailColumns(items: List<MediaItem>): DetailColumns {
    val context = LocalContext.current
    val state = produceState(initialValue = DetailColumns(items, emptyList()), items) {
        value = withContext(Dispatchers.IO) {
            var left = mutableListOf<MediaItem>()
            var right = mutableListOf<MediaItem>()
            var leftHeight = 0f
            var rightHeight = 0f
            items.forEach { item ->
                val ratio = context.readMediaAspectRatio(item.media, item.type)
                val height = if (ratio != null && ratio > 0f) {
                    1f / ratio.coerceIn(0.4f, 2.5f) + 0.34f
                } else {
                    1.67f
                }
                if (leftHeight <= rightHeight) {
                    left += item
                    leftHeight += height
                } else {
                    right += item
                    rightHeight += height
                }
                if (rightHeight > leftHeight) {
                    val oldLeft = left
                    left = right
                    right = oldLeft
                    val oldHeight = leftHeight
                    leftHeight = rightHeight
                    rightHeight = oldHeight
                }
            }
            DetailColumns(left.toList(), right.toList())
        }
    }
    return state.value
}

@Composable
private fun rememberStoredThumbnail(item: MediaItem): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (item.type) {
                    MediaType.IMAGE -> context.decodeSampledBitmap(item.media, 720, 720)?.asImageBitmap()
                    MediaType.VIDEO -> context.createVideoThumbnail(item.media)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
        }
    }
    return state.value
}

@Composable
private fun rememberStoredAspectRatio(item: MediaItem): Float? {
    val context = LocalContext.current
    val state = produceState<Float?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            context.readMediaAspectRatio(item.media, item.type)
        }
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

private data class SelectableColumns(
    val left: List<CachedMediaItem>,
    val right: List<CachedMediaItem>
)

@Composable
private fun rememberSelectableColumns(items: List<CachedMediaItem>): SelectableColumns {
    val state = produceState(initialValue = SelectableColumns(items, emptyList()), items) {
        value = withContext(Dispatchers.IO) {
            distributeSelectableItems(items)
        }
    }
    return state.value
}

private fun distributeSelectableItems(items: List<CachedMediaItem>): SelectableColumns {
    var leftItems = mutableListOf<CachedMediaItem>()
    var rightItems = mutableListOf<CachedMediaItem>()
    var leftHeight = 0f
    var rightHeight = 0f

    items.forEach { item ->
        val estimatedHeight = estimateSelectableCardHeight(item)
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

    return SelectableColumns(leftItems.toList(), rightItems.toList())
}

private fun estimateSelectableCardHeight(item: CachedMediaItem): Float {
    val aspectRatio = readSelectableAspectRatio(item)
    val previewHeightWeight = if (aspectRatio != null && aspectRatio > 0f) {
        1f / aspectRatio.coerceIn(0.4f, 2.5f)
    } else {
        1.33f
    }
    return previewHeightWeight + 0.34f
}

@Composable
private fun rememberSelectableThumbnail(item: CachedMediaItem): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            val file = File(item.path)
            if (!file.exists()) return@withContext null
            runCatching {
                when (item.type) {
                    MediaType.IMAGE -> decodeSampledBitmap(file.path, 720, 720)?.asImageBitmap()
                    MediaType.VIDEO -> createVideoThumbnail(file)?.asImageBitmap()
                    MediaType.OTHER -> null
                }
            }.getOrNull()
        }
    }
    return state.value
}

@Composable
private fun rememberSelectableAspectRatio(item: CachedMediaItem): Float? {
    val state = produceState<Float?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            readSelectableAspectRatio(item)
        }
    }
    return state.value
}

private fun readSelectableAspectRatio(item: CachedMediaItem): Float? {
    val file = File(item.path)
    if (!file.exists()) return null
    return runCatching {
        when (item.type) {
            MediaType.IMAGE -> {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight.toFloat()
                } else {
                    null
                }
            }
            MediaType.VIDEO -> {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.path)
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                retriever.release()
                if (width != null && height != null && height > 0) width / height else null
            }
            MediaType.OTHER -> null
        }
    }.getOrNull()
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
