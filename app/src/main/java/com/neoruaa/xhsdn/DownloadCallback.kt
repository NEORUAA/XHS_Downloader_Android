package com.neoruaa.xhsdn

import com.neoruaa.xhsdn.data.storage.StoredMediaRef

interface DownloadCallback {
    /**
     * Typed callback for one completed output. The path overload remains as a compatibility
     * bridge for existing consumers and is invoked by the default implementation.
     */
    fun onFileDownloaded(ref: StoredMediaRef) {
        onFileDownloaded(ref.path)
    }

    /** Legacy callback retained while task/UI consumers migrate to [StoredMediaRef]. */
    fun onFileDownloaded(filePath: String) = Unit
    fun onDownloadProgress(status: String)
    fun onDownloadProgressUpdate(downloaded: Long, total: Long)
    fun onDownloadError(status: String, originalUrl: String)
    fun onVideoDetected()

    /** Returns true when the current download should be cancelled. */
    fun isCancelled(): Boolean = false
}
