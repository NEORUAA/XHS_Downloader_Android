package com.neoruaa.xhsdn

import android.content.Context
import android.util.Log
import com.neoruaa.xhsdn.data.storage.AndroidStorageSink
import com.neoruaa.xhsdn.data.storage.StorageDestination
import com.neoruaa.xhsdn.data.storage.StorageAccessException
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.data.storage.StorageStreamWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Downloads media files and persists them through MediaStore or the legacy filesystem path. */
class FileDownloader @JvmOverloads constructor(
    context: Context,
    private val callback: DownloadCallback? = null,
    private val storageDestination: StorageDestination = StorageDestination.DefaultMediaStore,
) {
    private val context: Context = context.applicationContext
    private val httpClient: OkHttpClient = SHARED_HTTP_CLIENT
    private val storageSink = AndroidStorageSink(this.context)

    /** Every in-flight request belongs to this downloader session. */
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    companion object {
        private const val TAG = "FileDownloader"
        private val SHARED_HTTP_CLIENT: OkHttpClient = createSharedHttpClient()

        private fun createSharedHttpClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 8
            }
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(ConnectionPool(12, 10, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
        }

        @JvmStatic
        fun getSharedHttpClient(): OkHttpClient = SHARED_HTTP_CLIENT
    }

    private fun track(request: Request): Call = httpClient.newCall(request).also { activeCalls.add(it) }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted || callback?.isCancelled() == true || activeCalls.any { it.isCanceled() }) {
            throw CancellationException("Download cancelled by user")
        }
    }

    private fun writeStreamWithCancellation(
        inputStream: InputStream,
        outputStream: OutputStream,
        contentLength: Long,
    ) {
        val buffer = ByteArray(65536)
        var totalBytesRead = 0L
        var lastProgressUpdate = 0L
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            checkCancellation()
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            if (callback != null && contentLength > 0 &&
                (totalBytesRead - lastProgressUpdate >= 65536 || totalBytesRead == contentLength)
            ) {
                callback.onDownloadProgressUpdate(totalBytesRead, contentLength)
                lastProgressUpdate = totalBytesRead
            }
        }
    }

    /** Cancels all requests in this session, including concurrent media downloads. */
    fun cancel() {
        cancelCalls(activeCalls.toList())
    }

    fun downloadFile(url: String, fileName: String): Boolean =
        downloadFile(url, fileName, timestampForFilename())

    fun downloadFile(url: String, fileName: String, timestamp: String): Boolean =
        downloadFile(url, fileName, timestamp, true)

    fun downloadFileToDirectory(
        url: String,
        fileName: String,
        timestamp: String,
        destinationDir: File?,
    ): File? {
        if (destinationDir == null) return null
        return try {
            if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                Log.e(TAG, "Failed to create cache directory: ${destinationDir.absolutePath}")
                return null
            }

            Log.d(TAG, "on downloadFileToDirectory: $fileName")
            val call = track(buildRequest(url))
            try {
                call.execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Cache download failed. Response code: ${response.code}")
                        return null
                    }
                    val contentType = response.header("Content-Type", "")
                    if (isNonMediaContentType(contentType)) {
                        Log.e(TAG, "Rejecting non-media cache response. Content-Type: $contentType, url: $url")
                        return null
                    }

                    val extension = getFileExtension(response, url)
                    val baseName = fileName.removeSuffixIfHasExtension()
                    val fullName = "xhs_${baseName}.$extension"
                    val destinationFile = File(destinationDir, getUniqueFileName(destinationDir, fullName))
                    body.byteStream().use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            writeStreamWithCancellation(input, output, body.contentLength())
                        }
                    }
                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        Log.d(TAG, "Downloaded cache file: ${destinationFile.absolutePath}")
                        callback?.onFileDownloaded(StoredMediaRef.fromLegacyFile(destinationFile, getMimeTypeForFileExtension(extension)))
                        destinationFile
                    } else {
                        null
                    }
                }
            } finally {
                activeCalls.remove(call)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (callback?.isCancelled() == true) throw CancellationException("Download cancelled by user")
            Log.e(TAG, "Error caching file: ${e.message}", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while caching file: ${e.message}", e)
            null
        }
    }

    /** Copies a cached file into this session's destination without replacing an existing item. */
    fun copyCachedFileToStorage(sourceFile: File?): StoredMediaRef? {
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() <= 0L) return null
        val extension = getFileExtensionFromName(sourceFile.name)
        val mimeType = getMimeTypeForFileExtension(extension)
        return runCatching {
            storageSink.store(
                destination = storageDestination,
                displayName = sourceFile.name,
                mimeType = mimeType,
                sizeBytes = sourceFile.length(),
                writer = StorageStreamWriter { output ->
                    FileInputStream(sourceFile).use { input ->
                        writeStreamWithCancellation(input, output, sourceFile.length())
                    }
                },
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (error is StorageAccessException) throw error
            Log.e(TAG, "Error copying cached file to storage: ${error.message}", error)
        }.getOrNull()
    }

    /** Legacy wrapper retained for callers that still need a filesystem File. */
    fun copyCachedFileToMediaStore(sourceFile: File?): File? =
        copyCachedFileToStorage(sourceFile)?.legacyPath?.let(::File)

    fun downloadFile(url: String, fileName: String, timestamp: String, notifyErrors: Boolean): Boolean {
        return downloadFileStored(url, fileName, timestamp, notifyErrors) != null
    }

    /** Downloads directly into the session destination and returns its stable persisted reference. */
    fun downloadFileStored(
        url: String,
        fileName: String,
        timestamp: String,
        notifyErrors: Boolean = true,
    ): StoredMediaRef? {
        Log.d(TAG, "on downloadFile: $fileName")
        val call = track(buildRequest(url))
        return try {
            call.execute().use { response ->
                val body = response.body
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type", "")
                    if (isNonMediaContentType(contentType)) {
                        Log.e(TAG, "Rejecting non-media response. Content-Type: $contentType, url: $url")
                        if (notifyErrors) callback?.onDownloadError("Download failed. Non-media response received", url)
                        return null
                    }

                    val extension = getFileExtension(response, url)
                    val baseName = fileName.removeSuffixIfHasExtension().also {
                        if (it != fileName) {
                            Log.d(TAG, "Original filename has extension: ${fileName.substringAfterLast('.').lowercase()}, " +
                                "but using Content-Type based extension: $extension")
                        }
                    }
                    val fullName = "xhs_${baseName}.$extension"
                    val mimeType = getMimeTypeForFileExtension(extension)
                    val stored = runCatching {
                        storageSink.store(
                            destination = storageDestination,
                            displayName = fullName,
                            mimeType = mimeType,
                            sizeBytes = body.contentLength(),
                            writer = StorageStreamWriter { output ->
                                body.byteStream().use { input ->
                                    writeStreamWithCancellation(input, output, body.contentLength())
                                }
                            },
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        if (error is StorageAccessException) {
                            callback?.onDownloadError(
                                context.getString(R.string.storage_location_access_lost_reselect),
                                url,
                            )
                            throw error
                        }
                        if (call.isCanceled() || callback?.isCancelled() == true) {
                            throw CancellationException("Download cancelled by user")
                        }
                        Log.e(TAG, "Error saving downloaded media: ${error.message}", error)
                        null
                    }
                    if (stored != null) {
                        Log.d(TAG, "Downloaded file: ${stored.path}")
                        Log.d(TAG, "Total bytes: ${stored.sizeBytes}")
                        callback?.onFileDownloaded(stored)
                    }
                    stored
                } else {
                    Log.e(TAG, "Download failed. Response code: ${response.code}")
                    if (notifyErrors) callback?.onDownloadError("Download failed. Response code: ${response.code}", url)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageAccessException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled() || callback?.isCancelled() == true) throw CancellationException("Download cancelled by user")
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            if (notifyErrors) callback?.onDownloadError("IO Error downloading file: ${e.message}", url)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while downloading file: ${e.message}", e)
            if (notifyErrors) callback?.onDownloadError("Security exception while downloading file: ${e.message}", url)
            null
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=1.0,image/avif,image/webp,image/apng,*/*;q=1.0")
        .addHeader("Referer", "https://www.xiaohongshu.com/")
        .build()

    private fun isNonMediaContentType(contentType: String?): Boolean {
        if (contentType.isNullOrEmpty()) return false
        val normalized = contentType.lowercase()
        if (normalized.contains("image/") || normalized.contains("video/") || normalized.contains("application/octet-stream")) {
            return false
        }
        return normalized.contains("text/html") || normalized.contains("text/plain") ||
            normalized.contains("application/json") || normalized.contains("application/xml") ||
            normalized.contains("text/xml")
    }


    private fun getMimeTypeForFileExtension(fileExtension: String?): String = when (fileExtension?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }


    fun downloadFileToInternalStorage(url: String, fileName: String, timestamp: String): Boolean {
        val call = track(buildRequest(url))
        return try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed. Response code: ${response.code}")
                    return false
                }
                val extension = getFileExtension(response, url)
                val fullName = "xhs_${timestamp}_$fileName"
                val internalDir = context.getExternalFilesDir(null) ?: return false
                val destinationFile = File(internalDir, getUniqueFileName(internalDir, fullName))
                Log.d(TAG, "Saving file to internal storage: ${destinationFile.absolutePath}")
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        writeStreamWithCancellation(input, output, body.contentLength())
                    }
                }
                Log.d(TAG, "Downloaded file to internal storage: ${destinationFile.absolutePath}")
                Log.d(TAG, "Total bytes: ${body.contentLength()}")
                Log.d(TAG, "File exists: ${destinationFile.exists()}")
                Log.d(TAG, "File size: ${destinationFile.length()}")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled() || callback?.isCancelled() == true) throw CancellationException("Download cancelled by user")
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while downloading file: ${e.message}", e)
            false
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun getFileExtension(response: Response, url: String): String {
        val contentType = response.header("Content-Type")?.lowercase()
        if (contentType != null) {
            if (contentType.contains("video")) {
                return when {
                    contentType.contains("mp4") -> "mp4"
                    contentType.contains("quicktime") -> "mov"
                    else -> "mp4"
                }
            }
            if (contentType.contains("image")) {
                return when {
                    contentType.contains("jpeg") -> "jpg"
                    contentType.contains("png") -> "png"
                    contentType.contains("webp") -> "webp"
                    contentType.contains("gif") -> "gif"
                    else -> "jpg"
                }
            }
        }
        val normalizedUrl = url.lowercase()
        return when {
            ".jpg" in normalizedUrl || ".jpeg" in normalizedUrl -> "jpg"
            ".png" in normalizedUrl -> "png"
            ".gif" in normalizedUrl -> "gif"
            ".mp4" in normalizedUrl -> "mp4"
            ".mov" in normalizedUrl -> "mov"
            ".webp" in normalizedUrl -> "webp"
            "sns-img" in url -> "jpg"
            "video" in url -> "mp4"
            else -> "jpg"
        }
    }

    private fun getFileExtensionFromName(fileName: String?): String {
        if (fileName == null) return "jpg"
        val index = fileName.lastIndexOf('.')
        return if (index >= 0 && index < fileName.length - 1) fileName.substring(index + 1).lowercase() else "jpg"
    }

    private fun getUniqueFileName(directory: File, fileName: String): String {
        return try {
            if (!File(directory, fileName).exists()) return fileName
            val dot = fileName.lastIndexOf('.')
            val baseName = if (dot > 0 && dot < fileName.length - 1) fileName.substring(0, dot) else fileName
            val extension = if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot) else ""
            for (counter in 1 until 1000) {
                val candidate = "${baseName}_($counter)$extension"
                if (!File(directory, candidate).exists()) return candidate
            }
            "${baseName}_${System.currentTimeMillis()}$extension"
        } catch (e: Exception) {
            Log.e(TAG, "Error generating unique file name: ${e.message}", e)
            fileName
        }
    }

    private fun timestampForFilename(): String = SimpleDateFormat("yyMMdd", Locale.getDefault()).format(Date())

    private fun String.removeSuffixIfHasExtension(): String {
        val dot = lastIndexOf('.')
        return if (dot > 0 && dot < length - 1) substring(0, dot) else this
    }
}

/** Small pure seam used to keep cancellation of every in-flight call testable. */
internal fun cancelCalls(calls: Iterable<Call>) {
    calls.forEach { it.cancel() }
}
