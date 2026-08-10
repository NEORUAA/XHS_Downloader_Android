package com.neoruaa.xhsdn

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
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
import okhttp3.ResponseBody

/** Downloads media files and persists them through MediaStore or the legacy filesystem path. */
class FileDownloader @JvmOverloads constructor(
    context: Context,
    private val callback: DownloadCallback? = null,
) {
    private val context: Context = context.applicationContext
    private val httpClient: OkHttpClient = SHARED_HTTP_CLIENT

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
                        callback?.onFileDownloaded(destinationFile.absolutePath)
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

    fun copyCachedFileToMediaStore(sourceFile: File?): File? {
        if (sourceFile == null || !sourceFile.exists()) return null
        val extension = getFileExtensionFromName(sourceFile.name)
        var destinationFile: File? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            destinationFile = saveExistingFileToMediaStore(sourceFile, sourceFile.name, extension)
        }
        if (destinationFile == null) {
            destinationFile = copyCachedFileToFileSystem(sourceFile, extension)
        }
        if (destinationFile != null && destinationFile.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isFileInPrivateDirectory(destinationFile)) {
                notifyMediaStore(destinationFile)
            }
            return destinationFile
        }
        return null
    }

    fun downloadFile(url: String, fileName: String, timestamp: String, notifyErrors: Boolean): Boolean {
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
                        return false
                    }

                    val extension = getFileExtension(response, url)
                    val baseName = fileName.removeSuffixIfHasExtension().also {
                        if (it != fileName) {
                            Log.d(TAG, "Original filename has extension: ${fileName.substringAfterLast('.').lowercase()}, " +
                                "but using Content-Type based extension: $extension")
                        }
                    }
                    val fullName = "xhs_${baseName}.$extension"
                    var destinationFile: File? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        destinationFile = saveToMediaStore(fullName, body, extension)
                    }
                    if (destinationFile == null) {
                        destinationFile = saveToFileSystem(url, fullName, body)
                    }
                    if (destinationFile != null && destinationFile.exists()) {
                        Log.d(TAG, "Downloaded file: ${destinationFile.absolutePath}")
                        Log.d(TAG, "Total bytes: ${body.contentLength()}")
                        Log.d(TAG, "File exists: ${destinationFile.exists()}")
                        Log.d(TAG, "File size: ${destinationFile.length()}")
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isFileInPrivateDirectory(destinationFile)) {
                            notifyMediaStore(destinationFile)
                        }
                        callback?.onFileDownloaded(destinationFile.absolutePath)
                        return true
                    }
                    false
                } else {
                    Log.e(TAG, "Download failed. Response code: ${response.code}")
                    if (notifyErrors) callback?.onDownloadError("Download failed. Response code: ${response.code}", url)
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled() || callback?.isCancelled() == true) throw CancellationException("Download cancelled by user")
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            if (notifyErrors) callback?.onDownloadError("IO Error downloading file: ${e.message}", url)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while downloading file: ${e.message}", e)
            if (notifyErrors) callback?.onDownloadError("Security exception while downloading file: ${e.message}", url)
            false
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(fileName: String, body: ResponseBody, fileExtension: String): File? {
        return try {
            val resolver = context.contentResolver
            val (collectionUri, relativePath) = mediaStoreTarget(fileExtension)
            deleteExistingFilesInMediaStore(resolver, collectionUri, fileName, relativePath)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeTypeForFileExtension(fileExtension))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collectionUri, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    body.byteStream().use { input -> writeStreamWithCancellation(input, output, body.contentLength()) }
                } ?: throw IOException("Unable to open MediaStore output stream")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                val mediaStoreFile = buildMediaStoreFile(relativePath, fileName)
                when {
                    mediaStoreFile.exists() -> mediaStoreFile
                    getFileFromUri(uri)?.exists() == true -> getFileFromUri(uri)
                    else -> mediaStoreFile
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Download cancelled, cleaning up MediaStore entry: $uri")
                runCatching { resolver.delete(uri, null, null) }
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Error writing to MediaStore URI: ${e.message}", e)
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore: ${e.message}", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveExistingFileToMediaStore(sourceFile: File, fileName: String, fileExtension: String): File? {
        return try {
            val resolver = context.contentResolver
            val (collectionUri, relativePath) = mediaStoreTarget(fileExtension)
            deleteExistingFilesInMediaStore(resolver, collectionUri, fileName, relativePath)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeTypeForFileExtension(fileExtension))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collectionUri, values) ?: return null
            try {
                FileInputStream(sourceFile).use { input ->
                    resolver.openOutputStream(uri)?.use { output ->
                        writeStreamWithCancellation(input, output, sourceFile.length())
                    } ?: throw IOException("Unable to open MediaStore output stream")
                }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                val mediaStoreFile = buildMediaStoreFile(relativePath, fileName)
                when {
                    mediaStoreFile.exists() -> mediaStoreFile
                    getFileFromUri(uri)?.exists() == true -> getFileFromUri(uri)
                    else -> mediaStoreFile
                }
            } catch (e: CancellationException) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Error copying cached file to MediaStore: ${e.message}", e)
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cached file to MediaStore: ${e.message}", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreTarget(fileExtension: String): Pair<Uri, String> = when {
        isImageFile(fileExtension) -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
            (Environment.DIRECTORY_PICTURES + File.separator + "xhsdn")
        isVideoFile(fileExtension) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
            (Environment.DIRECTORY_MOVIES + File.separator + "xhsdn")
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to
            (Environment.DIRECTORY_DOWNLOADS + File.separator + "xhsdn")
    }

    private fun saveToFileSystem(url: String, fileName: String, body: ResponseBody): File? {
        var destinationFile: File? = null
        return try {
            val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            var destinationDir: File? = publicPictures?.let { File(it, "xhsdn") }
            var canWriteToPublic = false
            if (destinationDir != null) {
                canWriteToPublic = try {
                    if (!destinationDir.exists()) {
                        destinationDir.mkdirs()
                    } else {
                        val testFile = File(destinationDir, ".test_permission")
                        val created = testFile.createNewFile()
                        if (created) testFile.delete()
                        created
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cannot write to public directory: ${e.message}")
                    false
                }
            }
            if (!canWriteToPublic) {
                Log.d(TAG, "Falling back to app's private directory")
                destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "xhsdn") }
            } else {
                Log.d(TAG, "Using public Pictures directory")
            }
            destinationDir ?: return null
            if (!destinationDir.exists()) {
                Log.d(TAG, "Directory creation result: ${destinationDir.mkdirs()} for ${destinationDir.absolutePath}")
            }
            destinationFile = File(destinationDir, fileName)
            if (destinationFile.exists()) destinationFile.delete()
            Log.d(TAG, "Saving file to: ${destinationFile.absolutePath}")
            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    writeStreamWithCancellation(input, output, body.contentLength())
                }
            }
            destinationFile
        } catch (e: CancellationException) {
            Log.w(TAG, "Download cancelled, cleaning up file: ${destinationFile?.absolutePath ?: "unknown"}")
            if (destinationFile?.exists() == true) destinationFile.delete()
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Error saving file to filesystem: ${e.message}", e)
            null
        }
    }

    private fun copyCachedFileToFileSystem(sourceFile: File, fileExtension: String): File? {
        return try {
            val publicRoot = when {
                isVideoFile(fileExtension) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                isImageFile(fileExtension) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            var destinationDir = File(publicRoot, "xhsdn")
            if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "xhsdn") }
                    ?: return null
                if (!destinationDir.exists()) destinationDir.mkdirs()
            }
            val destinationFile = File(destinationDir, sourceFile.name)
            if (destinationFile.exists()) destinationFile.delete()
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    writeStreamWithCancellation(input, output, sourceFile.length())
                }
            }
            destinationFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Error copying cached file to filesystem: ${e.message}", e)
            null
        }
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

    private fun isImageFile(fileExtension: String?): Boolean = when (fileExtension?.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> true
        else -> false
    }

    private fun isVideoFile(fileExtension: String?): Boolean = when (fileExtension?.lowercase()) {
        "mp4", "mov" -> true
        else -> false
    }

    private fun isFileInPrivateDirectory(file: File): Boolean {
        val appPrivateDir = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return file.absolutePath.startsWith(appPrivateDir)
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index != -1) cursor.getString(index)?.let(::File) else null
                } else null
            } ?: getDisplayNameFromUri(uri)?.let { File(context.getExternalFilesDir(null) ?: context.filesDir, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file path from URI: ${e.message}", e)
            null
        }
    }

    private fun buildMediaStoreFile(relativePath: String, fileName: String): File =
        File(Environment.getExternalStorageDirectory(), relativePath + File.separator + fileName)

    private fun getDisplayNameFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display name from URI: ${e.message}", e)
            null
        }
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

    private fun notifyMediaStore(file: File) {
        val filePath = file.absolutePath
        val publicPicturesPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath
        val publicDownloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        if (filePath.startsWith(publicPicturesPath) || filePath.startsWith(publicDownloadsPath)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, getMimeTypeForFile(file))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, getRelativePathForFile(file, publicPicturesPath, publicDownloadsPath))
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use {
                            Log.d(TAG, "File inserted into MediaStore via direct method: ${file.absolutePath}")
                        }
                    } else {
                        fallbackMediaScan(file)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error inserting file into MediaStore: ${e.message}", e)
                    fallbackMediaScan(file)
                }
            } else {
                fallbackMediaScan(file)
            }
        } else {
            Log.d(TAG, "File is in private directory, no MediaStore notification needed: $filePath")
        }
    }

    private fun fallbackMediaScan(file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { path, uri ->
            Log.d(TAG, "MediaScanner scanned file: $path, URI: $uri")
        }
    }

    private fun getMimeTypeForFile(file: File): String = when {
        file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
        file.name.endsWith(".png", true) -> "image/png"
        file.name.endsWith(".gif", true) -> "image/gif"
        file.name.endsWith(".mp4", true) -> "video/mp4"
        file.name.endsWith(".mov", true) -> "video/quicktime"
        file.name.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun getRelativePathForFile(file: File, publicPicturesPath: String, publicDownloadsPath: String): String {
        val filePath = file.absolutePath
        return when {
            filePath.startsWith(publicPicturesPath) -> {
                val subPath = filePath.substring(publicPicturesPath.length)
                Environment.DIRECTORY_PICTURES + subPath.substring(0, subPath.lastIndexOf('/'))
            }
            filePath.startsWith(publicDownloadsPath) -> {
                val subPath = filePath.substring(publicDownloadsPath.length)
                Environment.DIRECTORY_DOWNLOADS + subPath.substring(0, subPath.lastIndexOf('/'))
            }
            else -> Environment.DIRECTORY_PICTURES
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getUniqueFileNameInMediaStore(
        contentResolver: ContentResolver,
        collectionUri: Uri,
        fileName: String,
        relativePath: String,
    ): String {
        return try {
            val dot = fileName.lastIndexOf('.')
            val baseName = if (dot > 0 && dot < fileName.length - 1) fileName.substring(0, dot) else fileName
            val extension = if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot) else ""
            if (!fileExistsInMediaStore(contentResolver, collectionUri, fileName, relativePath)) return fileName
            for (counter in 1 until 1000) {
                val candidate = "${baseName}_($counter)$extension"
                if (!fileExistsInMediaStore(contentResolver, collectionUri, candidate, relativePath)) return candidate
            }
            "${baseName}_${System.currentTimeMillis()}$extension"
        } catch (e: Exception) {
            Log.e(TAG, "Error generating unique file name: ${e.message}", e)
            fileName
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fileExistsInMediaStore(
        contentResolver: ContentResolver,
        collectionUri: Uri,
        fileName: String,
        relativePath: String,
    ): Boolean {
        return try {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(fileName, relativePath + File.separator)
            contentResolver.query(collectionUri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use {
                it.count > 0
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file existence: ${e.message}", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteExistingFilesInMediaStore(
        contentResolver: ContentResolver,
        collectionUri: Uri,
        fileName: String,
        relativePath: String,
    ): Int {
        return try {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(fileName, relativePath + File.separator)
            var deletedCount = 0
            contentResolver.query(collectionUri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext() && idColumn != -1) {
                    val fileUri = Uri.withAppendedPath(collectionUri, cursor.getLong(idColumn).toString())
                    if (contentResolver.delete(fileUri, null, null) > 0) deletedCount++
                }
            }
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting existing files: ${e.message}", e)
            0
        }
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
