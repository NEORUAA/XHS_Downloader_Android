package com.neoruaa.xhsdn.data.storage

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android implementation of the common streaming storage boundary. */
class AndroidStorageSink(context: Context) : StorageSink, StoredMediaReader {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val destinationLock = Any()
    // A downloader reuses this sink for the whole save operation. Android 10 snapshots a tree
    // only when replacement is enabled; the all-files fast path and coexist mode never enumerate.
    private val customTreeSessions = mutableMapOf<String, CustomTreeSession>()

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
                    destination,
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
                sizeBytes = writtenBytes,
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
        destination: StorageDestination.CustomTree,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val treeUri = Uri.parse(destination.treeUri)
        val session = customTreeSession(treeUri)
        return when (destination.existingFilePolicy) {
            ExistingFilePolicy.COEXIST -> storeCustomTreeCoexisting(
                session = session,
                displayName = displayName,
                mimeType = mimeType,
                writer = writer,
            )

            ExistingFilePolicy.REPLACE -> {
                ensureReplacementPermission()
                val rawDirectory = session.rawDirectory
                if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q && rawDirectory != null) {
                    storeCustomTreeReplacingRaw(
                        session = session,
                        rawDirectory = rawDirectory,
                        displayName = displayName,
                        mimeType = mimeType,
                        writer = writer,
                    )
                } else {
                    storeCustomTreeReplacingSaf(
                        session = session,
                        displayName = displayName,
                        mimeType = mimeType,
                        writer = writer,
                    )
                }
            }
        }
    }

    private fun customTreeSession(treeUri: Uri): CustomTreeSession =
        customTreeSessions.getOrPut(treeUri.toString()) {
            CustomTreeSession(
                treeUri = treeUri,
                parentDocumentUri = validateCustomTree(treeUri),
                rawDirectory = resolveRawDirectory(treeUri),
            )
        }

    private fun storeCustomTreeCoexisting(
        session: CustomTreeSession,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val written = createAndWriteDocument(
            parentDocumentUri = session.parentDocumentUri,
            displayName = displayName,
            mimeType = mimeType,
            writer = writer,
        )
        notifyStoredDocument(written.uri)
        return written.toStoredMediaRef(mimeType)
    }

    private fun storeCustomTreeReplacingRaw(
        session: CustomTreeSession,
        rawDirectory: File,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val target = safeChild(rawDirectory, displayName)
        val backup = safeChild(rawDirectory, recoveryBackupName(displayName))
        val staged = createAndWriteDocument(
            parentDocumentUri = session.parentDocumentUri,
            displayName = temporaryName(displayName, "stage"),
            mimeType = mimeType,
            writer = writer,
        )
        val lock = replacementLock(target.absolutePath)
        val finalUri = synchronized(lock) {
            replaceStagedDocumentUsingRawPath(
                stagedUri = staged.uri,
                target = target,
                backup = backup,
                requestedName = displayName,
            )
        }
        val actualName = queryDocumentDisplayName(finalUri) ?: displayName
        notifyStoredDocument(finalUri)
        return StoredMediaRef(
            uri = finalUri.toString(),
            displayName = actualName,
            mimeType = mimeType,
            sizeBytes = staged.bytesWritten,
        )
    }

    private fun storeCustomTreeReplacingSaf(
        session: CustomTreeSession,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): StoredMediaRef {
        val documents = session.documents ?: queryTreeDocuments(session.treeUri).also {
            session.documents = it
        }
        val backupName = recoveryBackupName(displayName)
        val staged = createAndWriteDocument(
            parentDocumentUri = session.parentDocumentUri,
            displayName = temporaryName(displayName, "stage"),
            mimeType = mimeType,
            writer = writer,
        )
        val lock = replacementLock("${session.treeUri}\u0000$displayName")
        val finalUri = synchronized(lock) {
            replaceStagedDocumentUsingSaf(
                stagedUri = staged.uri,
                documents = documents,
                requestedName = displayName,
                backupName = backupName,
            )
        }
        val actualName = queryDocumentDisplayName(finalUri) ?: displayName
        documents[displayName] = finalUri
        notifyStoredDocument(finalUri)
        return StoredMediaRef(
            uri = finalUri.toString(),
            displayName = actualName,
            mimeType = mimeType,
            sizeBytes = staged.bytesWritten,
        )
    }

    private fun createAndWriteDocument(
        parentDocumentUri: Uri,
        displayName: String,
        mimeType: String,
        writer: StorageStreamWriter,
    ): WrittenDocument {
        val documentUri = try {
            DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                mimeType,
                displayName,
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
            return WrittenDocument(
                uri = documentUri,
                displayName = queryDocumentDisplayName(documentUri) ?: displayName,
                bytesWritten = writtenBytes,
            )
        } catch (error: Throwable) {
            deleteDocument(documentUri)
            if (error is SecurityException) {
                throw StorageAccessException("Custom storage access is unavailable", error)
            }
            throw error
        }
    }

    private fun replaceStagedDocumentUsingRawPath(
        stagedUri: Uri,
        target: File,
        backup: File,
        requestedName: String,
    ): Uri {
        var existingMoved = false
        try {
            recoverRawBackup(target, backup)
            if (target.exists()) {
                Os.rename(target.absolutePath, backup.absolutePath)
                existingMoved = true
            }
            val renamedUri = DocumentsContract.renameDocument(resolver, stagedUri, requestedName)
                ?: throw IOException("Unable to finalize custom storage document")
            if (existingMoved && !backup.delete()) {
                Log.w(TAG, "Unable to delete custom storage backup: ${backup.name}")
            }
            return renamedUri
        } catch (error: Throwable) {
            val rolledBack = if (existingMoved) {
                runCatching {
                    Os.rename(backup.absolutePath, target.absolutePath)
                    true
                }.getOrDefault(false)
            } else {
                true
            }
            if (rolledBack) deleteDocument(stagedUri)
            if (!rolledBack) {
                Log.e(TAG, "Unable to restore custom storage backup: ${backup.name}", error)
            }
            throw StorageAccessException("Unable to replace existing custom storage file", error)
        }
    }

    private fun replaceStagedDocumentUsingSaf(
        stagedUri: Uri,
        documents: MutableMap<String, Uri>,
        requestedName: String,
        backupName: String,
    ): Uri {
        var backupUri: Uri? = null
        try {
            recoverSafBackup(documents, requestedName, backupName)
            val existingUri = documents[requestedName]
            if (existingUri != null) {
                backupUri = DocumentsContract.renameDocument(
                    resolver,
                    existingUri,
                    backupName,
                ) ?: throw IOException("Unable to prepare existing custom storage document")
                documents.remove(requestedName)
                documents[backupName] = backupUri
            }
            val renamedUri = DocumentsContract.renameDocument(resolver, stagedUri, requestedName)
                ?: throw IOException("Unable to finalize custom storage document")
            val backupDeleted = backupUri?.let { uri ->
                deleteDocument(uri).also { deleted ->
                    if (!deleted) {
                        Log.w(TAG, "Unable to delete SAF custom storage backup")
                    }
                }
            } ?: true
            if (backupDeleted) {
                documents.remove(backupName)
            } else {
                documents[backupName] = requireNotNull(backupUri)
            }
            return renamedUri
        } catch (error: Throwable) {
            val rolledBack = backupUri?.let { uri ->
                runCatching {
                    val restoredUri = DocumentsContract.renameDocument(resolver, uri, requestedName)
                        ?: return@runCatching false
                    documents.remove(backupName)
                    documents[requestedName] = restoredUri
                    true
                }.getOrDefault(false)
            } ?: true
            if (rolledBack) deleteDocument(stagedUri)
            if (!rolledBack) Log.e(TAG, "Unable to restore SAF custom storage backup", error)
            throw StorageAccessException("Unable to replace existing custom storage file", error)
        }
    }

    private fun recoverRawBackup(target: File, backup: File) {
        if (!backup.exists()) return
        if (target.exists()) {
            if (!backup.delete()) {
                throw IOException("Unable to remove stale custom storage backup")
            }
        } else {
            Os.rename(backup.absolutePath, target.absolutePath)
        }
    }

    private fun recoverSafBackup(
        documents: MutableMap<String, Uri>,
        requestedName: String,
        backupName: String,
    ) {
        val backupUri = documents[backupName] ?: return
        if (documents.containsKey(requestedName)) {
            if (!deleteDocument(backupUri)) {
                throw IOException("Unable to remove stale SAF custom storage backup")
            }
            documents.remove(backupName)
        } else {
            val restoredUri = DocumentsContract.renameDocument(resolver, backupUri, requestedName)
                ?: throw IOException("Unable to restore SAF custom storage backup")
            documents.remove(backupName)
            documents[requestedName] = restoredUri
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

    private fun queryTreeDocuments(treeUri: Uri): MutableMap<String, Uri> {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        return try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                LinkedHashMap<String, Uri>().apply {
                    val idIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    val nameIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    if (idIndex < 0 || nameIndex < 0) {
                        throw IOException("Custom storage provider omitted required columns")
                    }
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex) ?: continue
                        put(
                            name,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        )
                    }
                }
            } ?: throw IOException("Unable to enumerate custom storage directory")
        } catch (error: SecurityException) {
            throw StorageAccessException("Custom storage access is unavailable", error)
        } catch (error: Throwable) {
            throw StorageAccessException("Unable to inspect custom storage directory", error)
        }
    }

    private fun queryDocumentDisplayName(documentUri: Uri): String? = runCatching {
        resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    private fun ensureReplacementPermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager() -> {
                throw StorageAccessException("All files access is required to replace existing files")
            }

            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED -> {
                throw StorageAccessException("Legacy storage access is required to replace existing files")
            }
        }
    }

    private fun resolveRawDirectory(treeUri: Uri): File? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val separator = documentId.indexOf(':')
        if (separator <= 0) return@runCatching null
        resolveStorageTreeDirectory(
            documentId = documentId,
            primaryRoot = Environment.getExternalStorageDirectory(),
            homeRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ) { requestedVolumeId ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val storageManager = appContext.getSystemService(StorageManager::class.java)
                storageManager.storageVolumes.firstOrNull { volume ->
                    volume.uuid?.equals(requestedVolumeId, ignoreCase = true) == true
                }?.directory
            } else {
                File(STORAGE_ROOT, requestedVolumeId)
            }
        }
    }.getOrNull()

    private fun safeChild(directory: File, displayName: String): File {
        val canonicalDirectory = directory.canonicalFile
        val child = File(canonicalDirectory, displayName).canonicalFile
        if (child.parentFile != canonicalDirectory) {
            throw StorageAccessException("Custom storage file name is invalid")
        }
        return child
    }

    private fun temporaryName(displayName: String, purpose: String): String {
        val extension = splitExtension(displayName).second
        return ".xhsdn_${purpose}_${UUID.randomUUID().toString().replace("-", "")}$extension"
    }

    private fun recoveryBackupName(displayName: String): String {
        val extension = splitExtension(displayName).second
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(displayName.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return ".xhsdn_recovery_$digest$extension"
    }

    private fun replacementLock(key: String): Any {
        val index = (key.hashCode() and Int.MAX_VALUE) % REPLACEMENT_LOCK_COUNT
        return REPLACEMENT_LOCKS[index]
    }

    private fun deleteDocument(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    private fun notifyStoredDocument(documentUri: Uri) {
        // ExternalStorageProvider may expose a MediaStore counterpart. Keep the document URI
        // as the canonical reference because it inherits the persisted tree grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaUri = runCatching {
                MediaStore.getMediaUri(appContext, documentUri)
            }.getOrNull()
            mediaUri?.let { uri -> runCatching { resolver.notifyChange(uri, null) } }
        }
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
        private const val TAG = "AndroidStorageSink"
        private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
            "com.android.externalstorage.documents"
        private const val STORAGE_ROOT = "/storage"
        private const val MAX_UNIQUE_ATTEMPTS = 1000
        private const val SCAN_TIMEOUT_SECONDS = 3L
        private const val REPLACEMENT_LOCK_COUNT = 64
        private val REPLACEMENT_LOCKS = Array(REPLACEMENT_LOCK_COUNT) { Any() }
    }
}

private data class CustomTreeSession(
    val treeUri: Uri,
    val parentDocumentUri: Uri,
    val rawDirectory: File?,
    var documents: MutableMap<String, Uri>? = null,
)

private data class WrittenDocument(
    val uri: Uri,
    val displayName: String,
    val bytesWritten: Long,
) {
    fun toStoredMediaRef(mimeType: String): StoredMediaRef = StoredMediaRef(
        uri = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = bytesWritten,
    )
}

internal fun resolveStorageTreeDirectory(
    documentId: String,
    primaryRoot: File,
    homeRoot: File,
    secondaryRoot: (String) -> File?,
): File? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volumeId = documentId.substring(0, separator)
    val rawRelativePath = documentId.substring(separator + 1)
    if (volumeId.equals("raw", ignoreCase = true)) {
        val rawDirectory = File(rawRelativePath).canonicalFile
        return rawDirectory.takeIf {
            it.absolutePath == "/storage" || it.absolutePath.startsWith("/storage/")
        }
    }

    val root = when {
        volumeId.equals("primary", ignoreCase = true) -> primaryRoot
        volumeId.equals("home", ignoreCase = true) -> homeRoot
        else -> secondaryRoot(volumeId)
    } ?: return null
    val canonicalRoot = root.canonicalFile
    val relativePath = rawRelativePath.trim('/')
    val directory = if (relativePath.isBlank()) {
        canonicalRoot
    } else {
        File(canonicalRoot, relativePath).canonicalFile
    }
    return directory.takeIf { candidate ->
        candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)
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
