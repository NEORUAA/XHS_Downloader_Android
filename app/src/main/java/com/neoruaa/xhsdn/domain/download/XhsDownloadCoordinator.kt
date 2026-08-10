package com.neoruaa.xhsdn.domain.download

import android.util.Log
import com.neoruaa.xhsdn.DownloadCallback
import com.neoruaa.xhsdn.FileDownloader
import java.io.File
import java.util.LinkedHashSet

/** Result returned by one media download attempt. */
data class XhsDownloadResult(
    val success: Boolean,
    val file: File? = null,
    val failure: DownloadFailure? = null,
)

/**
 * Coordinates retries and fallback CDN URLs for a single XHS download
 * session. The coordinator is deliberately synchronous because the existing
 * public API is synchronous and callers already run it off the main thread.
 */
class XhsDownloadCoordinator(
    private val fileDownloader: FileDownloader,
    private val callback: DownloadCallback?,
    private val transformUrl: (String) -> String,
    private val stopRequested: () -> Boolean,
) {
    companion object {
        private const val TAG = "XhsDownloadCoordinator"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 350L
    }

    fun download(
        mediaUrl: String,
        originalUrl: String = mediaUrl,
        filename: String,
        timestamp: String,
        cacheDirectory: File? = null,
    ): XhsDownloadResult {
        val candidates = buildCandidates(mediaUrl, originalUrl)
        if (candidates.isEmpty()) {
            val failure = DownloadFailure.InvalidInput
            notifyFailure(mediaUrl, failure)
            return XhsDownloadResult(false, failure = failure)
        }

        candidates.forEach { candidate ->
            repeat(MAX_ATTEMPTS) { attemptIndex ->
                if (stopRequested()) {
                    return XhsDownloadResult(false, failure = DownloadFailure.Cancelled)
                }
                val cachedFile = if (cacheDirectory != null) {
                    fileDownloader.downloadFileToDirectory(candidate, filename, timestamp, cacheDirectory)
                } else {
                    null
                }
                val success = cachedFile?.exists() == true ||
                    (cacheDirectory == null && fileDownloader.downloadFile(candidate, filename, timestamp, false))
                if (success) {
                    if (candidate != mediaUrl) Log.d(TAG, "Download succeeded via fallback URL: $candidate")
                    return XhsDownloadResult(true, cachedFile)
                }
                if (attemptIndex + 1 < MAX_ATTEMPTS && !sleepBeforeRetry(attemptIndex + 1)) {
                    return XhsDownloadResult(false, failure = DownloadFailure.Cancelled)
                }
            }
        }
        val failure = DownloadFailure.Exhausted(candidates.size * MAX_ATTEMPTS)
        notifyFailure(originalUrl, failure)
        return XhsDownloadResult(false, failure = failure)
    }

    fun cancel() {
        fileDownloader.cancel()
    }

    private fun sleepBeforeRetry(attempt: Int): Boolean {
        return try {
            Thread.sleep(RETRY_BACKOFF_MS * attempt)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun notifyFailure(url: String, failure: DownloadFailure) {
        callback?.onDownloadError(
            when (failure) {
                is DownloadFailure.Exhausted -> "Failed to download after ${failure.attempts} attempts"
                else -> "No valid media URL"
            },
            url,
        )
    }

    private fun buildCandidates(mediaUrl: String, originalUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val transformedMedia = transformUrl(mediaUrl)
        if (isLikelyMediaUrl(transformedMedia)) candidates += transformedMedia
        if (isLikelyMediaUrl(mediaUrl)) candidates += mediaUrl
        if (isLikelyMediaUrl(originalUrl)) {
            candidates += originalUrl
            val transformedOriginal = transformUrl(originalUrl)
            if (isLikelyMediaUrl(transformedOriginal)) candidates += transformedOriginal
        }
        return candidates.filter(String::isNotBlank)
    }

    private fun isLikelyMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val normalized = url.lowercase()
        return normalized.contains("xhscdn.com") || normalized.contains("ci.xiaohongshu.com") ||
            normalized.contains(".jpg") || normalized.contains(".jpeg") || normalized.contains(".png") ||
            normalized.contains(".webp") || normalized.contains(".gif") || normalized.contains(".mp4") ||
            normalized.contains(".mov") || normalized.contains("imageview2") || normalized.contains("sns-video") ||
            normalized.contains("/spectrum/")
    }
}
