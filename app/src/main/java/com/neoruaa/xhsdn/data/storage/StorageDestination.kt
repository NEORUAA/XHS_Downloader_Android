package com.neoruaa.xhsdn.data.storage

/** Destination selected for one download session. */
sealed interface StorageDestination {
    /** The app-managed public media collections (MediaStore on Android 10+). */
    data object DefaultMediaStore : StorageDestination

    /** A user-granted local external-storage tree URI selected through the system picker. */
    data class CustomTree(val treeUri: String) : StorageDestination
}
