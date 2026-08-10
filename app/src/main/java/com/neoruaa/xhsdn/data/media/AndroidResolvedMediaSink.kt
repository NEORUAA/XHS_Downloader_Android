package com.neoruaa.xhsdn.data.media

import android.content.Context
import com.neoruaa.xhsdn.FileDownloader
import com.neoruaa.xhsdn.LivePhotoCreator
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.data.storage.StorageDestination
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.domain.download.ResolvedMediaSink
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Cancellable cache-to-MediaStore sink for the typed download pipeline. */
class AndroidResolvedMediaSink(context: Context) : ResolvedMediaSink {
    private val appContext = context.applicationContext

    override suspend fun save(taskId: Long, mediaIndex: Int, media: ResolvedMedia): String =
        saveStored(taskId, mediaIndex, media).path

    override suspend fun saveStored(
        taskId: Long,
        mediaIndex: Int,
        media: ResolvedMedia,
        destination: StorageDestination,
    ): StoredMediaRef =
        suspendCancellableCoroutine { continuation ->
            val downloader = FileDownloader(appContext, null, destination)
            continuation.invokeOnCancellation { downloader.cancel() }
            Dispatchers.IO.dispatch(continuation.context) {
                val result = runCatching {
                    saveBlocking(
                        taskId = taskId,
                        mediaIndex = mediaIndex,
                        media = media,
                        downloader = downloader,
                    )
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    private fun saveBlocking(
        taskId: Long,
        mediaIndex: Int,
        media: ResolvedMedia,
        downloader: FileDownloader
    ): StoredMediaRef {
        val cacheDirectory = File(appContext.cacheDir, "resolved_media/$taskId")
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Unable to create typed download cache")
        }
        val baseName = "task_${taskId}_${mediaIndex + 1}"
        return try {
            when (media) {
                is ResolvedMedia.Image -> saveSingle(
                    downloader = downloader,
                    cacheDirectory = cacheDirectory,
                    url = media.sourceUrl,
                    baseName = baseName
                )
                is ResolvedMedia.Video -> saveSingle(
                    downloader = downloader,
                    cacheDirectory = cacheDirectory,
                    url = media.sourceUrl,
                    baseName = baseName
                )
                is ResolvedMedia.LivePhoto -> saveLivePhoto(
                    downloader = downloader,
                    cacheDirectory = cacheDirectory,
                    media = media,
                    baseName = baseName
                )
            }
        } finally {
            cacheDirectory.listFiles()?.takeIf { it.isEmpty() }?.let { cacheDirectory.delete() }
        }
    }

    private fun saveSingle(
        downloader: FileDownloader,
        cacheDirectory: File,
        url: String,
        baseName: String
    ): StoredMediaRef {
        val cached = downloader.downloadFileToDirectory(url, baseName, "", cacheDirectory)
            ?: throw IOException("Unable to download resolved media")
        return try {
            downloader.copyCachedFileToStorage(cached)
                ?: throw IOException("Unable to persist resolved media")
        } finally {
            cached.delete()
        }
    }

    private fun saveLivePhoto(
        downloader: FileDownloader,
        cacheDirectory: File,
        media: ResolvedMedia.LivePhoto,
        baseName: String
    ): StoredMediaRef {
        var imageFile: File? = null
        var videoFile: File? = null
        val outputFile = File(cacheDirectory, "xhs_${baseName}_live.jpg")
        try {
            imageFile = downloader.downloadFileToDirectory(
                media.image.sourceUrl,
                "${baseName}_image",
                "",
                cacheDirectory
            ) ?: throw IOException("Unable to download live photo image")
            videoFile = downloader.downloadFileToDirectory(
                media.video.sourceUrl,
                "${baseName}_video",
                "",
                cacheDirectory
            ) ?: throw IOException("Unable to download live photo video")
            if (!LivePhotoCreator.createLivePhoto(imageFile, videoFile, outputFile, null)) {
                throw IOException("Unable to create live photo")
            }
            return downloader.copyCachedFileToStorage(outputFile)
                ?: throw IOException("Unable to persist live photo")
        } finally {
            imageFile?.delete()
            videoFile?.delete()
            outputFile.delete()
        }
    }
}
