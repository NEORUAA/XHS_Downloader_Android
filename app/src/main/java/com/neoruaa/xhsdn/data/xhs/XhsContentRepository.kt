package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.domain.download.DownloadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

interface XhsContentRepository {
    suspend fun resolve(rawInput: String): ResolvedNote
}

class XhsResolveException(
    val failure: DownloadFailure,
    cause: Throwable? = null
) : Exception(cause)

class DefaultXhsContentRepository(
    private val fetchHtml: suspend (String) -> String?,
    private val resolveShortUrl: suspend (String) -> String? = { null },
    private val parser: XhsNoteParser = XhsNoteParser(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : XhsContentRepository {
    override suspend fun resolve(rawInput: String): ResolvedNote = withContext(dispatcher) {
        val extracted = XhsUrlParser.extractLinks(rawInput)
        val firstUrl = extracted.firstOrNull()
            ?: throw XhsResolveException(DownloadFailure.InvalidInput)
        val canonicalUrl = if (firstUrl.contains("xhslink.com/") || firstUrl.contains("xhslink.cn/")) {
            try {
                resolveShortUrl(firstUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            } ?: firstUrl
        } else {
            firstUrl
        }
        val html = try {
            fetchHtml(canonicalUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw XhsResolveException(DownloadFailure.Network(error), error)
        } ?: throw XhsResolveException(DownloadFailure.RequiresWebView)
        val parsed = try {
            parser.parse(html)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw XhsResolveException(DownloadFailure.Parsing(error), error)
        }
        if (parsed.mediaUrls.isEmpty()) throw XhsResolveException(DownloadFailure.NoMedia)
        parsed.toResolvedNote(canonicalUrl)
    }
}

class OkHttpXhsPageSource(
    private val client: OkHttpClient
) {
    suspend fun fetchHtml(url: String): String? = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    ) { response ->
        response.body.string().takeIf { response.isSuccessful }
    }

    suspend fun resolveShortUrl(url: String): String? = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
    ) { response ->
        response.request.url.toString().takeIf { response.isSuccessful }
    }

    private suspend fun <T> execute(request: Request, transform: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        runCatching { transform(response) }
                            .onSuccess { value ->
                                if (continuation.isActive) continuation.resumeWith(Result.success(value))
                            }
                            .onFailure { error ->
                                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                            }
                    }
                }
            })
        }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36 xiaohongshu"
    }
}

internal fun ParsedXhsNote.toResolvedNote(canonicalUrl: String): ResolvedNote {
    val liveImageUrls = livePhotos.mapTo(mutableSetOf()) { it.imageUrl }
    val liveVideoUrls = livePhotos.mapTo(mutableSetOf()) { it.videoUrl }
    val images = mediaUrls
        .filterNot(::isResolvedVideoUrl)
        .filterNot(liveImageUrls::contains)
        .map { url -> ResolvedMedia.Image(url, originalUrlByTransformed[url] ?: url) }
    val videos = mediaUrls
        .filter(::isResolvedVideoUrl)
        .filterNot(liveVideoUrls::contains)
        .map { url -> ResolvedMedia.Video(url, originalUrlByTransformed[url] ?: url) }
    val resolvedLivePhotos = livePhotos.map { pair ->
        ResolvedMedia.LivePhoto(
            image = ResolvedMedia.Image(
                sourceUrl = pair.imageUrl,
                originalUrl = originalUrlByTransformed[pair.imageUrl] ?: pair.imageUrl
            ),
            video = ResolvedMedia.Video(
                sourceUrl = pair.videoUrl,
                originalUrl = originalUrlByTransformed[pair.videoUrl] ?: pair.videoUrl
            )
        )
    }
    return ResolvedNote(
        canonicalUrl = canonicalUrl,
        title = metadata?.title,
        description = description,
        authorName = metadata?.userName,
        authorId = metadata?.userId,
        publishTime = metadata?.publishTime,
        images = images,
        videos = videos,
        livePhotos = resolvedLivePhotos
    )
}

private fun isResolvedVideoUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return normalized.contains(".mp4") || normalized.contains(".mov") ||
        normalized.contains("sns-video") || normalized.contains("/spectrum/")
}
