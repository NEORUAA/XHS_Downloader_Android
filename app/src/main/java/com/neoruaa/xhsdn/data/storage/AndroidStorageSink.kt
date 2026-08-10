package com.neoruaa.xhsdn.data.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android implementation of the common streaming storage boundary. */
class AndroidStorageSink(context: Context) : StorageSink, StoredMediaReader {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val destinationLock = Any()

    override fun store(
        destination: StorageDestination,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val safeName = sanitizeDisplayName(displayName)
        require(safeName.isNotBlank()) { "Storage display name must not be blank" }
        val safeMimeType = mimeType.ifBlank { "application/octet-stream" }
        // The create/query step must be serialized with the stream write: otherwise two
        // concurrent downloads can both observe a free name before either provider insert.
        return synchronized(destinationLock) {
            when (destination) {
                StorageDestination.DefaultMediaStore ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        storeMediaStore(safeName, safeMimeType, writer)
                    } else {
                        storeLegacyFile(safeName, safeMimeType, sizeBytes, writer)
                    }

                is StorageDestination.CustomTree -> storeCustomTree(
                    Uri.parse(destination.treeUri),
                    safeName,
                    safeMimeType,
                    writer,
                )
            }
        }
    }

    override fun open(ref: StoredMediaRef): InputStream? {
        ref.legacyPath?.let { path ->
            runCatching { FileInputStream(path) }.getOrNull()?.let { return it }
        }
        return runCatching { resolver.openInputStream(ref.androidUri) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun storeMediaStore(
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val (collection, relativePath) = mediaStoreTarget(mimeType)
        val uniqueName = uniqueMediaStoreName(collection, relativePath, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Unable to create MediaStore entry")
        try {
            val writtenBytes = resolver.openOutputStream(uri, "w")?.use { rawOutput ->
                CountingOutputStream(rawOutput).use { output ->
                    writer.write(output)
                    output.bytesWritten
                }
            } ?: throw IOException("Unable to open MediaStore output stream")
            if (writtenBytes <= 0L) throw IOException("MediaStore produced an empty file")
            val finalSize = querySize(uri).takeIf { it > 0L } ?: writtenBytes
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated <= 0) throw IOException("Unable to publish MediaStore entry")
            return StoredMediaRef(
                uri = uri.toString(),
                displayName = uniqueName,
                mimeType = mimeType,
                sizeBytes = finalSize,
            )
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun storeLegacyFile(
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val rootDirectory = when {
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        var directory = File(
            Environment.getExternalStoragePublicDirectory(rootDirectory),
            "xhsdn",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            directory = File(
                appContext.getExternalFilesDir(rootDirectory) ?: appContext.filesDir,
                "xhsdn",
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create legacy storage directory")
            }
        }
        val target = File(directory, uniqueFileName(directory, displayName))
        try {
            target.outputStream().use(writer::write)
            if (!target.exists() || target.length() <= 0L) {
                throw IOException("Legacy storage produced an empty file")
            }
            val scannedUri = scanAndResolve(target, mimeType)
            return StoredMediaRef(
                uri = (scannedUri ?: Uri.fromFile(target)).toString(),
                displayName = target.name,
                mimeType = mimeType,
                sizeBytes = target.length().takeIf { it >= 0L } ?: sizeBytes,
                legacyPath = target.absolutePath,
            )
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
    }

    private fun storeCustomTree(
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val parentDocumentUri = validateCustomTree(treeUri)
        val uniqueName = uniqueTreeName(treeUri, displayName)
        val documentUri = try {
            DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                mimeType,
                uniqueName,
            )
        } catch (error: SecurityException) {
            throw StorageAccessException("Custom storage access is unavailable", error)
        } ?: throw StorageAccessException("Custom storage directory is unavailable")
        try {
            val writtenBytes = resolver.openOutputStream(documentUri, "w")?.use { rawOutput ->
                CountingOutputStream(rawOutput).use { output ->
                    writer.write(output)
                    output.bytesWritten
                }
            } ?: throw IOException("Unable to open custom storage document")
            if (writtenBytes <= 0L) throw IOException("Custom storage produced an empty file")
            val finalSize = querySize(documentUri).takeIf { it > 0L } ?: writtenBytes
            // ExternalStorageProvider may expose a MediaStore counterpart. Keep the document
            // URI when it cannot be resolved; it remains a valid readable persisted URI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mediaUri = runCatching {
                    MediaStore.getMediaUri(appContext, documentUri)
                }.getOrNull()
                mediaUri?.let { uri -> runCatching { resolver.notifyChange(uri, null) } }
            }
            return StoredMediaRef(
                // Keep the tree-backed document URI as the canonical reference. It inherits
                // the persisted tree grant, while an equivalent MediaStore URI may not.
                uri = documentUri.toString(),
                displayName = uniqueName,
                mimeType = mimeType,
                sizeBytes = finalSize,
            )
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
            if (error is SecurityException) {
                throw StorageAccessException("Custom storage access is unavailable", error)
            }
            throw error
        }
    }

    private fun validateCustomTree(treeUri: Uri): Uri {
        if (!DocumentsContract.isTreeUri(treeUri) ||
            treeUri.scheme != ContentResolver.SCHEME_CONTENT ||
            treeUri.authority != EXTERNAL_STORAGE_PROVIDER_AUTHORITY
        ) {
            throw StorageAccessException("Custom storage location is invalid")
        }
        resolver.persistedUriPermissions.firstOrNull { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        } ?: throw StorageAccessException("Custom storage permission is no longer available")

        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrElse { error ->
            throw StorageAccessException("Unable to resolve custom storage directory", error)
        }
        val supportsCreate = runCatching {
            resolver.query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mimeType = cursor.getString(0)
                val flags = cursor.getInt(1)
                mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            } ?: false
        }.getOrElse { error ->
            throw StorageAccessException("Custom storage directory is unavailable", error)
        }
        if (!supportsCreate) throw StorageAccessException("Custom storage directory is not writable")
        return documentUri
    }

    private fun uniqueMediaStoreName(collection: Uri, relativePath: String, requestedName: String): String {
        if (!mediaStoreNameExists(collection, relativePath, requestedName)) return requestedName
        val (base, extension) = splitExtension(requestedName)
        for (counter in 1 until MAX_UNIQUE_ATTEMPTS) {
            val candidate = "${base}_($counter)$extension"
            if (!mediaStoreNameExists(collection, relativePath, candidate)) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$extension"
    }

    private fun mediaStoreNameExists(collection: Uri, relativePath: String, name: String): Boolean {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(name, relativePath)
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun uniqueTreeName(treeUri: Uri, requestedName: String): String {
        val existingNames = treeNames(treeUri)
        if (requestedName !in existingNames) return requestedName
        val (base, extension) = splitExtension(requestedName)
        for (counter in 1 until MAX_UNIQUE_ATTEMPTS) {
            val candidate = "${base}_($counter)$extension"
            if (candidate !in existingNames) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$extension"
    }

    private fun treeNames(treeUri: Uri): Set<String> {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreTarget(mimeType: String): Pair<Uri, String> = when {
        mimeType.startsWith("image/") ->
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                (Environment.DIRECTORY_PICTURES + File.separator + "xhsdn" + File.separator)

        mimeType.startsWith("video/") ->
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                (Environment.DIRECTORY_MOVIES + File.separator + "xhsdn" + File.separator)

        else ->
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to
                (Environment.DIRECTORY_DOWNLOADS + File.separator + "xhsdn" + File.separator)
    }

    private fun querySize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (index >= 0) cursor.getLong(index) else -1L
            } else {
                -1L
            }
        } ?: -1L
    }.getOrDefault(-1L)

    private fun scanAndResolve(file: File, mimeType: String): Uri? {
        val latch = CountDownLatch(1)
        var scannedUri: Uri? = null
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
        ) { _, uri ->
            scannedUri = uri
            latch.countDown()
        }
        runCatching { latch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return scannedUri
    }

    private fun uniqueFileName(directory: File, requestedName: String): String {
        if (!File(directory, requestedName).exists()) return requestedName
        val (base, extension) = splitExtension(requestedName)
        for (counter in 1 until MAX_UNIQUE_ATTEMPTS) {
            val candidate = "${base}_($counter)$extension"
            if (!File(directory, candidate).exists()) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$extension"
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            name.substring(0, dot) to name.substring(dot)
        } else {
            name to ""
        }
    }

    private fun sanitizeDisplayName(name: String): String = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
        .trim()

    companion object {
        private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
            "com.android.externalstorage.documents"
        private const val MAX_UNIQUE_ATTEMPTS = 1000
        private const val SCAN_TIMEOUT_SECONDS = 3L
    }
}

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var bytesWritten: Long = 0L
        private set

    override fun write(byte: Int) {
        out.write(byte)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytesWritten += length
    }
}
