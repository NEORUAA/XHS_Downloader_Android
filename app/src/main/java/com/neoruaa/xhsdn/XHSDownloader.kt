package com.neoruaa.xhsdn

import android.content.Context
import android.os.Environment
import android.util.Log
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.data.xhs.ParsedXhsNote
import com.neoruaa.xhsdn.data.xhs.XhsLivePhoto
import com.neoruaa.xhsdn.data.xhs.XhsNoteMetadata
import com.neoruaa.xhsdn.data.xhs.XhsNoteParser
import com.neoruaa.xhsdn.data.xhs.XhsUrlParser
import com.neoruaa.xhsdn.domain.download.XhsDownloadCoordinator
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Compatibility facade for the downloader's historical public API.
 *
 * URL extraction, note parsing and retry/fallback downloading live in
 * [XhsUrlParser], [XhsNoteParser] and [XhsDownloadCoordinator]. Keeping this
 * facade stable lets existing ViewModels and share intents migrate gradually.
 */
class XHSDownloader @JvmOverloads constructor(
    context: Context,
    callback: DownloadCallback? = null,
) {
    companion object {
        private const val TAG = "XHSDownloader"
        private const val USER_AGENT_XHS_ANDROID =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu"
        private val NAMING_PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)\\}")
    }

    private val hostContext = context
    private val appContext = context.applicationContext
    private val httpClient: OkHttpClient = FileDownloader.getSharedHttpClient()
    private val successfulDownloads = AtomicInteger(0)
    private val urlMapping = ConcurrentHashMap<String, String>()
    private val cachedMediaFiles = Collections.synchronizedList(mutableListOf<CachedMediaFile>())
    private val fileDownloader: FileDownloader
    private val downloadCallback: DownloadCallback?
    private val coordinator: XhsDownloadCoordinator

    @Volatile private var cacheDestinationMode = false
    @Volatile private var cacheDestinationDir: File? = null
    @Volatile private var videosDetected = false
    @Volatile private var videoWarningShown = true
    @Volatile private var shouldStopOnVideo = false
    @Volatile private var shouldStopDownload = false

    private var livePhotoPairs: List<XhsLivePhoto> = emptyList()
    private var currentNoteMetadata: XhsNoteMetadata? = null
    private var customNamingEnabled = false
    private var customFormatTemplate: String? = null
    private var sessionTimestamp: String = ""
    private var sessionDownloadEpochSeconds: Long = 0L

    init {
        downloadCallback = callback?.let { delegate ->
            object : DownloadCallback {
                override fun onFileDownloaded(filePath: String) {
                    successfulDownloads.incrementAndGet()
                    delegate.onFileDownloaded(filePath)
                }

                override fun onDownloadProgress(status: String) = delegate.onDownloadProgress(status)

                override fun onDownloadProgressUpdate(downloaded: Long, total: Long) =
                    delegate.onDownloadProgressUpdate(downloaded, total)

                override fun onDownloadError(status: String, originalUrl: String) =
                    delegate.onDownloadError(status, originalUrl)

                override fun onVideoDetected() = delegate.onVideoDetected()

                override fun isCancelled(): Boolean = delegate.isCancelled()
            }
        }
        fileDownloader = FileDownloader(appContext, downloadCallback)
        coordinator = XhsDownloadCoordinator(
            fileDownloader = fileDownloader,
            callback = downloadCallback,
            transformUrl = { transformXhsCdnUrl(it).orEmpty() },
            stopRequested = ::shouldStop,
        )
    }

    class CachedMediaFile(
        @JvmField val path: String,
        @JvmField val displayName: String,
    )

    class SelectiveDownloadResult(
        @JvmField val success: Boolean,
        @JvmField val noteUrl: String,
        files: List<CachedMediaFile>,
    ) {
        @JvmField val files: List<CachedMediaFile> = files.toList()
    }

    fun downloadFile(url: String, filename: String): Boolean =
        downloadFile(url, filename, SimpleDateFormat("yyMMdd", Locale.getDefault()).format(Date()))

    fun downloadFile(url: String, filename: String, timestamp: String): Boolean =
        downloadMedia(url, filename, timestamp).success

    /** Downloads all posts in a share text and returns true when at least one file was saved. */
    fun downloadContent(inputUrl: String?): Boolean {
        successfulDownloads.set(0)
        var hasErrors = false
        try {
            if (shouldStop()) return false
            val urls = extractLinks(inputUrl)
            if (urls.isEmpty()) {
                Log.e(TAG, "No valid XHS URLs found")
                return false
            }

            val now = Date()
            sessionTimestamp = SimpleDateFormat("yyMMdd", Locale.getDefault()).format(now)
            sessionDownloadEpochSeconds = now.time / 1000L
            customNamingEnabled = shouldUseCustomNamingFormat()
            customFormatTemplate = getCustomNamingTemplate()

            urls.forEach { url ->
                if (shouldStop()) return@forEach
                val postId = extractPostId(url)
                if (postId.isNullOrBlank()) {
                    reportError("Could not extract post ID from URL: $url", url)
                    hasErrors = true
                    return@forEach
                }

                currentNoteMetadata = null
                val details = fetchPostDetails(url)
                if (details.isNullOrBlank()) {
                    reportError("Failed to fetch post details for: $url", url)
                    hasErrors = true
                    return@forEach
                }

                val parsed = parsePostDetailsResult(details)
                val mediaUrls = parsed.mediaUrls
                if (mediaUrls.isEmpty()) {
                    reportError("No media URLs found in post: $postId", url)
                    hasErrors = true
                    return@forEach
                }

                currentNoteMetadata = parsed.metadata
                if (parsed.containsVideo) {
                    videosDetected = true
                    notifyVideoDetectedIfNeeded()
                }
                livePhotoPairs = parsed.livePhotos

                val imageUrls = mediaUrls.filterNot(::isVideoUrl)
                val videoUrls = mediaUrls.filter(::isVideoUrl)
                val createLivePhotos = shouldCreateLivePhotos()
                if (createLivePhotos && imageUrls.isNotEmpty() && videoUrls.isNotEmpty() && livePhotoPairs.isNotEmpty()) {
                    hasErrors = createLivePhotos(postId, mediaUrls, sessionTimestamp) || hasErrors
                } else {
                    hasErrors = downloadMediaList(postId, imageUrls + videoUrls, sessionTimestamp) || hasErrors
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error in downloadContent: ${error.message}")
            hasErrors = true
        } finally {
            urlMapping.clear()
            livePhotoPairs = emptyList()
            currentNoteMetadata = null
        }
        return successfulDownloads.get() > 0
    }

    fun downloadContentToCache(inputUrl: String?, cacheDir: File): SelectiveDownloadResult {
        cacheDestinationMode = true
        cacheDestinationDir = cacheDir
        cachedMediaFiles.clear()
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return try {
            SelectiveDownloadResult(downloadContent(inputUrl), inputUrl.orEmpty(), cachedMediaFiles)
        } finally {
            cacheDestinationMode = false
            cacheDestinationDir = null
        }
    }

    fun extractLinks(input: String?): List<String> = XhsUrlParser.extractLinks(input, ::resolveShortUrl)

    fun extractPostId(url: String?): String? = XhsUrlParser.extractPostId(url)

    fun fetchPostDetails(url: String): String? {
        var call: okhttp3.Call? = null
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT_XHS_ANDROID)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=1.0,image/avif,image/webp,image/apng,*/*;q=1.0")
                .build()
            call = httpClient.newCall(request)
            activeCalls += call
            call.execute().use { response ->
                if (response.isSuccessful) response.body.string()
                else {
                    Log.e(TAG, "Failed to fetch post details. Response code: ${response.code}")
                    null
                }
            }
        } catch (error: IOException) {
            Log.e(TAG, "Error fetching post details: ${error.message}")
            null
        } finally {
            call?.let(activeCalls::remove)
        }
    }

    fun parsePostDetails(html: String?): List<String> = parsePostDetailsResult(html).mediaUrls

    fun transformXhsCdnUrl(originalUrl: String?): String? {
        if (originalUrl == null || !originalUrl.contains("xhscdn.com")) return originalUrl
        if (originalUrl.contains("video") || originalUrl.contains("sns-video")) return originalUrl
        val parts = originalUrl.split('/')
        if (parts.size <= 5) return originalUrl
        val token = parts.drop(5).joinToString("/").substringBefore('!').substringBefore('?')
        return "https://ci.xiaohongshu.com/$token"
    }

    fun getMediaCount(inputUrl: String?): Int {
        extractLinks(inputUrl).forEach { url ->
            if (extractPostId(url) == null) return@forEach
            val details = fetchPostDetails(url) ?: return@forEach
            val parsed = parsePostDetailsResult(details)
            var count = parsed.mediaUrls.size
            if (shouldCreateLivePhotos()) count -= parsed.livePhotos.size
            return count.coerceAtLeast(0)
        }
        return 0
    }

    fun getNoteDescription(inputUrl: String?): String? {
        extractLinks(inputUrl).forEach { url ->
            if (extractPostId(url) == null) return@forEach
            val details = fetchPostDetails(url) ?: return@forEach
            parsePostDetailsResult(details).description?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    fun hasVideosDetected(): Boolean = videosDetected

    fun resetVideosDetected() {
        videosDetected = false
        videoWarningShown = false
    }

    fun setShouldStopOnVideo(shouldStop: Boolean) {
        shouldStopOnVideo = shouldStop
    }

    fun stopDownload() {
        shouldStopDownload = true
        activeCalls.forEach { it.cancel() }
        coordinator.cancel()
        fileDownloader.cancel()
    }

    fun shouldStopDownload(): Boolean = shouldStopDownload

    fun resetStopDownload() {
        shouldStopDownload = false
    }

    protected fun checkForStop() {
        if (shouldStop()) throw InterruptedException("Download stopped by user request")
    }

    protected fun shouldStop(): Boolean = shouldStopDownload || Thread.currentThread().isInterrupted

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<okhttp3.Call>())

    private fun parsePostDetailsResult(html: String?): ParsedXhsNote {
        val parsed = XhsNoteParser(
            urlTransformer = { transformXhsCdnUrl(it).orEmpty() },
            logError = { Log.e(TAG, it) }
        ).parse(html)
        urlMapping.clear()
        urlMapping.putAll(parsed.originalUrlByTransformed)
        return parsed
    }

    private fun downloadMediaList(postId: String, mediaUrls: List<String>, timestamp: String): Boolean {
        if (mediaUrls.isEmpty()) return false
        val executor: ExecutorService? = if (mediaUrls.size > 1) Executors.newFixedThreadPool(mediaUrls.size.coerceAtMost(4)) else null
        val futures = mutableListOf<Future<Boolean>>()
        var hasErrors = false
        mediaUrls.forEachIndexed { index, mediaUrl ->
            val task = Callable {
                val filename = "${buildFileBaseName(postId, index + 1)}.${determineFileExtension(mediaUrl)}"
                val result = downloadMedia(mediaUrl, filename, timestamp)
                if (!result.success) Log.e(TAG, "Failed to download: $mediaUrl")
                result.success
            }
            if (executor != null) futures += executor.submit(task)
            else if (!task.call()) hasErrors = true
        }
        futures.forEachIndexed { index, future ->
            try {
                if (!future.get()) hasErrors = true
            } catch (error: Exception) {
                hasErrors = true
                reportError("Exception downloading: ${mediaUrls[index]}", urlMapping[mediaUrls[index]] ?: mediaUrls[index])
            }
        }
        executor?.shutdown()
        return hasErrors
    }

    private fun downloadMedia(mediaUrl: String, filename: String, timestamp: String): com.neoruaa.xhsdn.domain.download.XhsDownloadResult {
        val result = coordinator.download(
            mediaUrl = mediaUrl,
            originalUrl = urlMapping[mediaUrl] ?: mediaUrl,
            filename = filename,
            timestamp = timestamp,
            cacheDirectory = cacheDestinationDir.takeIf { cacheDestinationMode },
        )
        if (result.file != null && result.success) {
            cachedMediaFiles += CachedMediaFile(result.file.absolutePath, result.file.name)
        }
        return result
    }

    private fun reportError(status: String, originalUrl: String) {
        Log.e(TAG, status)
        downloadCallback?.onDownloadError(status, originalUrl)
        if (hostContext is MainActivity) hostContext.showWebCrawlOption()
    }

    private fun notifyVideoDetectedIfNeeded() {
        if (shouldStopOnVideo && !videoWarningShown) {
            videoWarningShown = true
            downloadCallback?.onVideoDetected()
        }
    }

    private fun determineFileExtension(url: String?): String {
        val value = url.orEmpty().lowercase()
        return when {
            ".jpg" in value || ".jpeg" in value -> "jpg"
            ".png" in value -> "png"
            ".gif" in value -> "gif"
            ".webp" in value -> "webp"
            ".mp4" in value || "video" in value || "masterurl" in value || "stream" in value -> "mp4"
            "xhscdn.com" in value && ("h264" in value || "stream" in value) -> "mp4"
            else -> "jpg"
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val value = url.lowercase()
        return ".mp4" in value || ".mov" in value || ".avi" in value || ".webm" in value ||
            "video" in value || "masterurl" in value || "stream" in value || "sns-video" in value || "/spectrum/" in value
    }

    private fun resolveShortUrl(shortUrl: String): String? {
        var call: okhttp3.Call? = null
        return try {
            val request = Request.Builder().url(shortUrl).addHeader("User-Agent", USER_AGENT_XHS_ANDROID).build()
            call = httpClient.newCall(request)
            activeCalls += call
            call.execute().use { response -> if (response.isSuccessful) response.request.url.toString() else null }
        } catch (error: Exception) {
            Log.e(TAG, "Error resolving short URL: ${error.message}")
            null
        } finally {
            call?.let(activeCalls::remove)
        }
    }

    private fun currentSettings(): AppSettings =
        (appContext as? XHSApplication)
            ?.appContainer
            ?.settingsRepository
            ?.currentSettings
            ?: AppSettings()

    private fun shouldCreateLivePhotos(): Boolean = currentSettings().createLivePhotos

    private fun shouldUseCustomNamingFormat(): Boolean = currentSettings().useCustomNamingFormat

    private fun getCustomNamingTemplate(): String {
        return currentSettings().customNamingTemplate
            .trim()
            .takeIf { it.isNotBlank() }
            ?: NamingFormat.DEFAULT_TEMPLATE
    }

    private fun buildFileBaseName(fallbackPostId: String, mediaIndex: Int): String {
        val indexPart = "%02d".format(Locale.getDefault(), mediaIndex.coerceAtLeast(1))
        if (customNamingEnabled && !customFormatTemplate.isNullOrBlank()) {
            applyCustomTemplate(customFormatTemplate.orEmpty(), fallbackPostId, mediaIndex, indexPart)?.let { return it }
        }
        return "${fallbackPostId}_$indexPart"
    }

    private fun applyCustomTemplate(template: String, fallbackPostId: String, mediaIndex: Int, indexPart: String): String? {
        if (template.isBlank()) return null
        val titleToken = NamingFormat.buildPlaceholder(NamingFormat.TOKEN_TITLE)
        val title = currentNoteMetadata?.title.orEmpty()
        val titleCount = template.windowed(titleToken.length, 1, partialWindows = true).count { it == titleToken }

        var result = template
        if (titleCount > 0) {
            // Keep a conservative filename budget for MediaStore and collision suffixes.
            val baseWithoutTitle = template.replace(titleToken, "")
                .replace(NAMING_PLACEHOLDER_PATTERN.toRegex()) { match -> resolveTemplateValue(match.groupValues[1], fallbackPostId, mediaIndex, indexPart) }
            val available = (50 - baseWithoutTitle.length).coerceAtLeast(1)
            result = result.replace(titleToken, safeTokenValue(title, available).orEmpty())
        }
        result = NAMING_PLACEHOLDER_PATTERN.toRegex().replace(result) { match ->
            resolveTemplateValue(match.groupValues[1], fallbackPostId, mediaIndex, indexPart)
        }
        var sanitized = sanitizeForFilename(result, 0) ?: return null
        if (!containsIndexToken(template)) sanitized += "_$indexPart"
        return sanitized
    }

    private fun resolveTemplateValue(key: String, fallbackPostId: String, mediaIndex: Int, indexPart: String): String {
        return when (key) {
            NamingFormat.TOKEN_USERNAME -> safeTokenValue(currentNoteMetadata?.userName, 60)
            NamingFormat.TOKEN_USER_ID -> safeTokenValue(currentNoteMetadata?.userId, 60)
            NamingFormat.TOKEN_TITLE -> safeTokenValue(currentNoteMetadata?.title, 80)
            NamingFormat.TOKEN_POST_ID -> safeTokenValue(fallbackPostId, 60)
            NamingFormat.TOKEN_PUBLISH_TIME -> safeTokenValue(currentNoteMetadata?.publishTime, 60)
            NamingFormat.TOKEN_INDEX -> mediaIndex.coerceAtLeast(1).toString()
            NamingFormat.TOKEN_INDEX_PADDED -> indexPart
            NamingFormat.TOKEN_DOWNLOAD_TIMESTAMP ->
                (if (sessionDownloadEpochSeconds > 0) sessionDownloadEpochSeconds else System.currentTimeMillis() / 1000L).toString()
            else -> ""
        }
    }

    private fun containsIndexToken(template: String): Boolean =
        template.contains(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_INDEX)) ||
            template.contains(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_INDEX_PADDED))

    private fun safeTokenValue(value: String?, maxLength: Int): String {
        val sanitized = sanitizeForFilename(value, 0) ?: return ""
        if (sanitized.length <= maxLength) return sanitized
        if (maxLength <= 3) return sanitized.take(maxLength)
        return sanitized.take(maxLength - 3) + "..."
    }

    private fun sanitizeForFilename(value: String?, maxLength: Int): String? {
        if (value.isNullOrBlank()) return null
        var sanitized = value
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        if (maxLength > 0) sanitized = sanitized.take(maxLength)
        return sanitized.takeIf { it.isNotBlank() }
    }

    private fun createLivePhotos(postId: String, mediaUrls: List<String>, timestamp: String): Boolean {
        var hasErrors = false
        var livePhotoIndex = 0
        for (pair in livePhotoPairs) {
            livePhotoIndex++
            val baseName = buildFileBaseName(postId, livePhotoIndex)
            val imageName = "${baseName}_img.${determineFileExtension(pair.imageUrl)}"
            val videoName = "${baseName}_vid.${determineFileExtension(pair.videoUrl)}"
            val tempDownloader = FileDownloader(appContext, null)
            var imageFile: File? = null
            var videoFile: File? = null
            try {
                if (!tempDownloader.downloadFileToInternalStorage(pair.imageUrl, imageName, timestamp) ||
                    !tempDownloader.downloadFileToInternalStorage(pair.videoUrl, videoName, timestamp)
                ) {
                    hasErrors = true
                    continue
                }
                val externalDir = appContext.getExternalFilesDir(null)
                if (externalDir == null) {
                    hasErrors = true
                    continue
                }
                imageFile = File(externalDir, "xhs_${timestamp}_$imageName")
                videoFile = File(externalDir, "xhs_${timestamp}_$videoName")
                if (!imageFile.exists() || !videoFile.exists()) {
                    hasErrors = true
                    continue
                }

                val destinationDir = cacheDestinationDir.takeIf { cacheDestinationMode }
                    ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "xhsdn")
                if (!destinationDir.exists()) destinationDir.mkdirs()
                val output = File(destinationDir, "xhs_${baseName}_live.jpg")
                val created = LivePhotoCreator.createLivePhoto(imageFile, videoFile, output, appContext)
                if (created && output.exists() && output.length() > 0) {
                    downloadCallback?.onFileDownloaded(output.absolutePath)
                    if (cacheDestinationMode) cachedMediaFiles += CachedMediaFile(output.absolutePath, output.name)
                } else {
                    hasErrors = true
                    downloadCallback?.onDownloadProgress(
                        "Live photo creation failed for post $postId, index $livePhotoIndex. Falling back to separate files."
                    )
                    val imageResult = downloadMedia(pair.imageUrl, imageName, timestamp)
                    val videoResult = downloadMedia(pair.videoUrl, videoName, timestamp)
                    if (!imageResult.success && !videoResult.success) {
                        downloadCallback?.onDownloadError(
                            "Both image and video failed to download separately after live photo creation failure",
                            "Post $postId, item $livePhotoIndex",
                        )
                    }
                }
            } catch (error: Exception) {
                hasErrors = true
                Log.e(TAG, "Error creating live photo: ${error.message}")
            } finally {
                imageFile?.takeIf(File::exists)?.delete()
                videoFile?.takeIf(File::exists)?.delete()
            }
        }

        var mediaIndex = livePhotoIndex
        mediaUrls.forEach { mediaUrl ->
            if (livePhotoPairs.none { it.imageUrl == mediaUrl || it.videoUrl == mediaUrl }) {
                mediaIndex++
                val suffix = if (isVideoUrl(mediaUrl)) "video" else "image"
                val filename = "${buildFileBaseName(postId, mediaIndex)}_$suffix.${determineFileExtension(mediaUrl)}"
                if (!downloadMedia(mediaUrl, filename, timestamp).success) hasErrors = true
            }
        }
        return hasErrors
    }
}
