package com.neoruaa.xhsdn

interface DownloadCallback {
    fun onFileDownloaded(filePath: String)
    fun onDownloadProgress(status: String)
    fun onDownloadProgressUpdate(downloaded: Long, total: Long)
    fun onDownloadError(status: String, originalUrl: String)
    fun onVideoDetected()

    /** Returns true when the current download should be cancelled. */
    fun isCancelled(): Boolean = false
}
