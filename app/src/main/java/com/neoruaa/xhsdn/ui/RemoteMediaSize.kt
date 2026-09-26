package com.neoruaa.xhsdn.ui

import android.content.Context
import android.util.LruCache
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.data.network.ResumableTransfer
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private val sourceSizes by lazy { LruCache<String, Long>(256) }
private val sizeRequests = Semaphore(4)

/** Only HEAD is used: missing metadata must never trigger a full media download. */
internal suspend fun remoteMediaSize(context: Context, url: String): Long? {
    sourceSizes.get(url)?.let { return it.takeIf { size -> size > 0 } }
    return sizeRequests.withPermit {
        sourceSizes.get(url)?.let { return@withPermit it.takeIf { size -> size > 0 } }
        val container = (context.applicationContext as XHSApplication).appContainer
        val client = container.network.client(container.settingsRepository.currentSettings.downloadOptions, true)
        val size = requestMediaSize(client, url)
        if (size != null) sourceSizes.put(url, size)
        size
    }
}

internal suspend fun requestMediaSize(client: OkHttpClient, url: String): Long? {
    val request = try {
        Request.Builder().url(url).head().header("Accept-Encoding", "identity")
            .header("User-Agent", ResumableTransfer.USER_AGENT).build()
    } catch (_: IllegalArgumentException) { return null }
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.success(null))
            }
            override fun onResponse(call: Call, response: Response) {
                val size = response.use {
                    val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    // Original CDN media may use the generic binary MIME type.
                    val mediaType = type.startsWith("image/") || type.startsWith("video/") || type == "application/octet-stream"
                    if (it.isSuccessful && mediaType &&
                        it.header("Content-Encoding").let { encoding -> encoding == null || encoding.equals("identity", true) }) {
                        it.header("Content-Length")?.toLongOrNull()?.takeIf { length -> length > 0 }
                    } else null
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(size))
            }
        })
    }
}
