package com.neoruaa.xhsdn.feature.detail

import com.neoruaa.xhsdn.core.model.*
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.data.tasks.*
import com.neoruaa.xhsdn.domain.download.MediaTransferProgress
import com.neoruaa.xhsdn.domain.download.NoteOutput
import com.neoruaa.xhsdn.viewmodels.MediaItem
import com.neoruaa.xhsdn.viewmodels.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.decodeFromString

/** A stable output slot, present before its local file exists. */
data class DetailMediaCard(
    val key: String,
    val stored: MediaItem? = null,
    val previewUrl: String = "",
    val type: MediaType = MediaType.IMAGE,
    val width: Int = 0,
    val height: Int = 0,
    val live: Boolean = false,
    val transferIds: List<String> = emptyList(),
    val checkpoint: Map<String, MediaTransferProgress> = emptyMap(),
    val taskStatus: TaskStatus = TaskStatus.QUEUED,
    val resourceFailed: Boolean = false,
)

fun observeDetailMediaCards(repository: TaskRepository, sessions: DownloadSessionDao, id: Long): Flow<List<DetailMediaCard>> =
    combine(repository.observeTask(id), sessions.observeSession(id), sessions.observeResources(id)) { task, session, resources ->
        task?.let { buildDetailMediaCards(it, session, resources) }.orEmpty()
    }

internal fun buildDetailMediaCards(task: DownloadTask, session: DownloadSessionEntity?, resources: List<DownloadResourceEntity>): List<DetailMediaCard> {
    val local = task.mediaRefs.associateBy { it.path }
    val used = mutableSetOf<String>()
    val records = resources.associateBy { it.mediaId }
    val settings = session?.let { runCatching { DownloadJson.decodeFromString<AppSettings>(it.settingsJson) }.getOrNull() }
    val note = session?.resolvedJson?.let { runCatching { DownloadJson.decodeFromString<ResolvedNote>(it) }.getOrNull() }
    val selected = session?.selectedJson?.let { runCatching { DownloadJson.decodeFromString<Set<String>>(it) }.getOrNull() }
    fun refs(record: DownloadResourceEntity?): List<StoredMediaRef> = record?.let {
        runCatching { DownloadJson.decodeFromString<List<StoredMediaRef>>(it.refsJson) }.getOrDefault(emptyList())
    }.orEmpty()
    return buildList {
        if (session != null && settings != null && note != null && !session.infoOnly) {
            val options = settings.downloadOptions
            val candidates = NoteOutput.eligible(note, options, forSelection = session.requireSelection)
                .filter { selected == null || it.id in selected }
            for (item in candidates) {
                val resource = records[item.id]
                val completed = resource?.state in setOf("COMPLETED", "SKIPPED")
                val hasOutputRecords = records.keys.any { it.startsWith("${item.id}:output:") }
                val image = when (item) { is ResolvedMedia.Image -> item; is ResolvedMedia.LivePhoto -> item.image; else -> null }
                val video = when (item) { is ResolvedMedia.Video -> item; is ResolvedMedia.LivePhoto -> item.video; else -> null }
                val videoSource = video?.let { media ->
                    val firstUrl = NoteOutput.videoUrls(media, options.videoPreference).firstOrNull()
                    media.candidates.firstOrNull { it.url == firstUrl }
                }
                fun addOutput(suffix: String, parts: List<String>, type: MediaType, live: Boolean = false) {
                    val output = records["${item.id}:output:$suffix"]
                    val saved = refs(output).firstOrNull()?.let { local[it.path] }
                        ?: refs(resource).takeIf { !hasOutputRecords }?.firstOrNull { it.path !in used && it.path in local }?.let { local[it.path] }
                    // Deleted local files stay deleted; checkpoints must not resurrect them.
                    if (saved == null && (completed || (output != null && resource?.state != "DOWNLOADING"))) return
                    if (saved != null) used += saved.path
                    val checkpoint = parts.associateWith { part ->
                        records["$part:transfer"]?.let { MediaTransferProgress(it.bytesDownloaded, it.totalBytes,
                            complete = it.state == "TRANSFERRED", failed = it.state == "TRANSFER_FAILED") } ?: MediaTransferProgress()
                    }
                    add(DetailMediaCard("${item.id}:$suffix", saved?.let(::MediaItem), item.previewUrl, type,
                        if (type == MediaType.VIDEO) videoSource?.width ?: 0 else image?.width ?: 0,
                        if (type == MediaType.VIDEO) videoSource?.height ?: 0 else image?.height ?: 0,
                        live, parts, checkpoint, task.status, resource?.state == "FAILED"))
                }
                when (item) {
                    is ResolvedMedia.Image -> addOutput("main", listOf(item.id), MediaType.IMAGE)
                    is ResolvedMedia.Video -> addOutput("main", listOf(item.id), MediaType.VIDEO)
                    is ResolvedMedia.LivePhoto -> {
                        val still = options.imageDownload
                        val motion = options.videoDownload && options.livePhotoMode != LivePhotoMode.STILL
                        val fallback = records.containsKey("${item.id}:output:main") || records.containsKey("${item.id}:output:motion")
                        if (still && motion && options.livePhotoMode == LivePhotoMode.MERGED && !fallback) {
                            addOutput("live", listOf(item.image.id, item.video.id), MediaType.IMAGE, live = true)
                            if (options.livePhotoFormat == LivePhotoFormat.VIVO_LEGACY) {
                                addOutput("vivo_motion", listOf(item.video.id), MediaType.VIDEO)
                            }
                        } else {
                            if (still) addOutput("main", listOf(item.image.id), MediaType.IMAGE)
                            if (motion) addOutput("motion", listOf(item.video.id), MediaType.VIDEO)
                        }
                    }
                }
            }
        }
        // Legacy history and note-information exports have no source media slot.
        task.mediaRefs.filter { it.path !in used }.forEach { add(DetailMediaCard(it.path, stored = MediaItem(it))) }
    }
}
