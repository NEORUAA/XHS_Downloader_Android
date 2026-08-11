package com.neoruaa.xhsdn.data.storage

/** Destination selected for one download session. */
sealed interface StorageDestination {
    /** The app-managed public media collections (MediaStore on Android 10+). */
    data object DefaultMediaStore : StorageDestination

    /** A user-granted local external-storage tree URI selected through the system picker. */
    data class CustomTree(
        val treeUri: String,
        val existingFilePolicy: ExistingFilePolicy,
    ) : StorageDestination
}

/** Existing-file behavior captured when a download session starts. */
enum class ExistingFilePolicy {
    /** Replace an item with the same display name after the new content is fully written. */
    REPLACE,

    /** Let the document provider allocate a coexistence name such as `name (1).jpg`. */
    COEXIST,
}

internal fun resolveStorageDestination(
    customTreeUri: String?,
    checkExistingFilesBeforeSave: Boolean,
): StorageDestination {
    val treeUri = customTreeUri?.trim()?.takeIf(String::isNotBlank)
        ?: return StorageDestination.DefaultMediaStore
    return StorageDestination.CustomTree(
        treeUri = treeUri,
        existingFilePolicy = if (checkExistingFilesBeforeSave) {
            ExistingFilePolicy.REPLACE
        } else {
            ExistingFilePolicy.COEXIST
        },
    )
}
