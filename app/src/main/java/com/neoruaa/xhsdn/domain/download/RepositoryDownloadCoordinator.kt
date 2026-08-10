package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.xhs.XhsContentRepository
import com.neoruaa.xhsdn.data.xhs.XhsResolveException
import com.neoruaa.xhsdn.data.storage.StorageDestination
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/** Saves one resolved media item and returns its persisted location. */
fun interface ResolvedMediaSink {
    suspend fun save(taskId: Long, mediaIndex: Int, media: ResolvedMedia): String

    /** Typed storage seam; the default keeps old implementations source-compatible. */
    suspend fun saveStored(
        taskId: Long,
        mediaIndex: Int,
        media: ResolvedMedia,
        destination: StorageDestination = StorageDestination.DefaultMediaStore,
    ): StoredMediaRef = StoredMediaRef.fromLegacyPath(save(taskId, mediaIndex, media))
}

/**
 * Domain-level download pipeline used by new feature ViewModels.
 *
 * The existing downloader facade can be replaced incrementally while new code consumes typed
 * events immediately. Coroutine cancellation is deliberately propagated to the collector so
 * network and storage implementations can release all in-flight resources.
 */
class RepositoryDownloadCoordinator(
    private val contentRepository: XhsContentRepository,
    private val mediaSink: ResolvedMediaSink
) : DownloadCoordinator {
    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Resolving)
        val note = contentRepository.resolve(request.rawInput)
        emit(DownloadEvent.Resolved(note))

        if (request.requireSelection && request.selectedUrls.isEmpty()) {
            emit(DownloadEvent.WaitingForSelection(note))
            return@flow
        }

        val media = note.mediaItems().filter { item ->
            request.selectedUrls.isEmpty() || item.matchesAny(request.selectedUrls)
        }
        if (media.isEmpty()) {
            emit(DownloadEvent.Failed(DownloadFailure.NoMedia))
            return@flow
        }

        media.forEachIndexed { index, item ->
            emit(
                DownloadEvent.Progress(
                    completedFiles = index,
                    totalFiles = media.size,
                    currentFileFraction = 0f
                )
            )
            val ref = try {
                mediaSink.saveStored(
                    taskId = request.taskId,
                    mediaIndex = index,
                    media = item,
                    destination = request.storageDestination,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                emit(DownloadEvent.Failed(DownloadFailure.Storage(error)))
                return@flow
            }
            emit(DownloadEvent.Saved(ref))
            emit(
                DownloadEvent.Progress(
                    completedFiles = index + 1,
                    totalFiles = media.size,
                    currentFileFraction = 1f
                )
            )
        }
        emit(DownloadEvent.Completed)
    }.catch { error ->
        if (error is CancellationException) throw error
        val failure = (error as? XhsResolveException)?.failure
            ?: DownloadFailure.Parsing(error)
        emit(DownloadEvent.Failed(failure))
    }
}

private fun ResolvedNote.mediaItems(): List<ResolvedMedia> = buildList {
    addAll(images)
    addAll(videos)
    addAll(livePhotos)
}

private fun ResolvedMedia.matchesAny(selectedUrls: Set<String>): Boolean = when (this) {
    is ResolvedMedia.Image -> sourceUrl in selectedUrls || originalUrl in selectedUrls
    is ResolvedMedia.Video -> sourceUrl in selectedUrls || originalUrl in selectedUrls
    is ResolvedMedia.LivePhoto ->
        image.sourceUrl in selectedUrls || image.originalUrl in selectedUrls ||
            video.sourceUrl in selectedUrls || video.originalUrl in selectedUrls
}
