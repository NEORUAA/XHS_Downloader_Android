package com.neoruaa.xhsdn.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.data.network.ResumableTransfer
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

private val previewMemory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/** Small bounded previews; these never enter the durable media ledger. */
internal suspend fun remoteThumbnail(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
    previewMemory.get(url)?.let { return@withContext it }
    val directory = File(context.cacheDir, "previews").apply { mkdirs() }
    val file = File(directory, ResumableTransfer.fingerprint(url))
    if (!file.exists()) {
        val container = (context.applicationContext as XHSApplication).appContainer
        val client = container.network.client(container.settingsRepository.currentSettings.downloadOptions, true)
        val bytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", ResumableTransfer.USER_AGENT).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!response.isSuccessful || response.body.contentLength() > 4 * 1024 * 1024) return@use null
                        response.body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(32 * 1024)
                            while (continuation.isActive && output.size() <= 4 * 1024 * 1024) {
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray().takeIf { continuation.isActive && it.size <= 4 * 1024 * 1024 }
                        }
                    } }.getOrNull()
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }
            })
        } ?: return@withContext null
        val temporary = File.createTempFile("preview", ".tmp", directory)
        try { temporary.writeBytes(bytes); temporary.renameTo(file) } finally { temporary.delete() }
        var size = directory.listFiles().orEmpty().sumOf { it.length() }
        directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach { old ->
            if (size > 32 * 1024 * 1024 && old != file) { val length = old.length(); if (old.delete()) size -= length }
        }
    }
    val bitmap = runCatching { decodeSampledBitmap(file.path, 600, 600) }.getOrNull()
    if (bitmap != null) previewMemory.put(url, bitmap) else file.delete()
    bitmap
}
