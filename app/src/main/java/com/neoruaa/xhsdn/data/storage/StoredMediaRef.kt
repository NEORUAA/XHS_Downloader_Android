package com.neoruaa.xhsdn.data.storage

import android.net.Uri
import java.io.File

/** Stable description of a successfully persisted media item. */
data class StoredMediaRef(
    /** Persistable location string; parsed into [Uri] only at Android I/O boundaries. */
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    /** Non-null for legacy filesystem output and temporary/cache files. */
    val legacyPath: String? = null,
) {
    /** Compatibility value for older task/UI code that still stores a single path string. */
    val path: String
        get() = legacyPath ?: uri

    val androidUri: Uri
        get() = Uri.parse(uri)

    companion object {
        fun fromLegacyFile(file: File, mimeType: String = "application/octet-stream"): StoredMediaRef =
            StoredMediaRef(
                uri = file.toURI().toString(),
                displayName = file.name,
                mimeType = mimeType,
                sizeBytes = file.length(),
                legacyPath = file.absolutePath,
            )

        fun fromLegacyPath(path: String): StoredMediaRef {
            val file = File(path)
            return fromLegacyFile(file)
        }

        fun fromLocation(location: String): StoredMediaRef {
            if (location.startsWith("content://")) {
                return StoredMediaRef(
                    uri = location,
                    displayName = location.substringAfterLast('/'),
                    mimeType = "application/octet-stream",
                )
            }
            return fromLegacyPath(location)
        }
    }
}
