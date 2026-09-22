package com.neoruaa.xhsdn.data.network

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class TransferException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NETWORK, ACCESS, NON_MEDIA, INCOMPLETE, RANGE, NO_SPACE }
}
data class TransferredMedia(val file: File, val type: MediaFileType)

/** A file can be appended only when the server confirms the exact range and validator. */
class ResumableTransfer(private val root: File) {
    suspend fun fetch(
        client: OkHttpClient,
        taskId: Long,
        mediaId: String,
        urls: List<String>,
        maxRetries: Int,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): TransferredMedia = withContext(Dispatchers.IO) {
        val directory = File(root, taskId.toString()).apply { mkdirs() }
        val name = fingerprint(mediaId)
        val partial = File(directory, "$name.part")
        val metadata = File(directory, "$name.json")
        var last: IOException = TransferException(TransferException.Reason.NETWORK)
        for (url in urls.distinct()) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            for (attempt in 0..maxRetries.coerceIn(0, 10)) {
                coroutineContext.ensureActive()
                try {
                    return@withContext transfer(client, url, partial, metadata, progress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    last = error
                    if (error is TransferException && error.reason == TransferException.Reason.NO_SPACE) throw error
                    if (error is TransferException && error.reason in setOf(TransferException.Reason.ACCESS, TransferException.Reason.NON_MEDIA)) break
                    if (attempt < maxRetries) delay((500L shl attempt.coerceAtMost(5)).coerceAtMost(15_000))
                }
            }
        }
        throw last
    }

    private suspend fun transfer(
        client: OkHttpClient,
        url: String,
        file: File,
        metadata: File,
        progress: (Long, Long) -> Unit,
    ): TransferredMedia {
        val identity = fingerprint(url)
        var saved = readMetadata(metadata)
        if (saved.optString("identity") != identity) {
            file.delete()
            metadata.delete()
            saved = JSONObject()
        }
        if (saved.optBoolean("complete") && file.length() == saved.optLong("total") && file.length() > 0) {
            MediaFileType.detect(file)?.let { progress(file.length(), file.length()); return TransferredMedia(file, it) }
        }
        val validator = saved.optString("etag").takeUnless { it.startsWith("W/") }.orEmpty()
            .ifBlank { saved.optString("modified") }
        if (file.length() > 0 && validator.isBlank()) file.delete()
        val offset = file.length()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://www.xiaohongshu.com/")
            .apply { if (offset > 0) { header("Range", "bytes=$offset-"); header("If-Range", validator) } }
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!continuation.isActive) throw CancellationException("Transfer cancelled")
                            if (response.code == 416) {
                                val length = response.header("Content-Range")?.substringAfter("*/")?.toLongOrNull()
                                val unchanged = validator.isNotBlank() && validator in listOf(response.header("ETag"), response.header("Last-Modified"))
                                if (length == offset && offset > 0 && unchanged) {
                                    val type = MediaFileType.detect(file) ?: throw TransferException(TransferException.Reason.NON_MEDIA)
                                    writeMetadata(metadata, saved.put("complete", true).put("total", offset))
                                    return@runCatching TransferredMedia(file, type)
                                }
                                file.delete(); metadata.delete()
                                throw TransferException(TransferException.Reason.RANGE)
                            }
                            if (!response.isSuccessful) throw TransferException(
                                if (response.code in setOf(401, 403, 404, 410)) TransferException.Reason.ACCESS else TransferException.Reason.NETWORK,
                            )
                            val contentType = response.header("Content-Type").orEmpty().lowercase()
                            if (contentType.contains("text/") || contentType.contains("json") || contentType.contains("xml")) {
                                file.delete(); metadata.delete()
                                throw TransferException(TransferException.Reason.NON_MEDIA)
                            }
                            val body = response.body
                            val range = parseRange(response.header("Content-Range"))
                            val appending = response.code == 206
                            if (appending && (range == null || range.first != offset ||
                                    (body.contentLength() >= 0 && range.last - range.first + 1 != body.contentLength()))) {
                                file.delete(); metadata.delete()
                                throw TransferException(TransferException.Reason.RANGE)
                            }
                            if (appending && offset > 0) {
                                val received = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                                    ?: response.header("Last-Modified")
                                if (received != null && received != validator) {
                                    file.delete(); metadata.delete()
                                    throw TransferException(TransferException.Reason.RANGE)
                                }
                            }
                            val start = if (appending) offset else 0L
                            val total = if (appending) range!!.total else body.contentLength()
                            if (total > start && file.parentFile!!.usableSpace < total - start + 1024 * 1024) {
                                throw TransferException(TransferException.Reason.NO_SPACE)
                            }
                            val state = JSONObject().put("identity", identity).put("etag", response.header("ETag").orEmpty())
                                .put("modified", response.header("Last-Modified").orEmpty()).put("total", total).put("complete", false)
                            // Truncate before publishing new response validators.
                            RandomAccessFile(file, "rw").use { output ->
                                output.setLength(start)
                                output.seek(start)
                                writeMetadata(metadata, state)
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var written = start
                                    var lastProgress = 0L
                                    while (true) {
                                        if (!continuation.isActive) throw CancellationException("Transfer cancelled")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        written += count
                                        val now = System.nanoTime()
                                        if (now - lastProgress >= 200_000_000L) {
                                            progress(written, total)
                                            lastProgress = now
                                        }
                                    }
                                    output.fd.sync()
                                    if (written <= 0 || (total >= 0 && written != total)) throw TransferException(TransferException.Reason.INCOMPLETE)
                                    progress(written, if (total > 0) total else written)
                                }
                            }
                            val type = MediaFileType.detect(file) ?: run {
                                file.delete(); metadata.delete()
                                throw TransferException(TransferException.Reason.NON_MEDIA)
                            }
                            writeMetadata(metadata, state.put("complete", true).put("total", file.length()))
                            TransferredMedia(file, type)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    fun clear(taskId: Long) { File(root, taskId.toString()).deleteRecursively() }
    private fun readMetadata(file: File) = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
    private fun writeMetadata(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value.toString())
        if (!temporary.renameTo(file)) throw IOException("Unable to persist transfer checkpoint")
    }
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36 xiaohongshu"
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        data class ByteRange(val first: Long, val last: Long, val total: Long)
        fun parseRange(value: String?): ByteRange? {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
            val (first, last, total) = match.destructured
            val range = ByteRange(first.toLongOrNull() ?: return null, last.toLongOrNull() ?: return null, total.toLongOrNull() ?: return null)
            return range.takeIf { it.first >= 0 && it.last >= it.first && it.total > it.last }
        }
    }
}
