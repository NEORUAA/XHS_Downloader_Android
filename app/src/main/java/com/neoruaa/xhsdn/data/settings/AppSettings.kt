package com.neoruaa.xhsdn.data.settings

import com.neoruaa.xhsdn.NamingFormat

data class AppSettings(
    val createLivePhotos: Boolean = true,
    val useCustomNamingFormat: Boolean = false,
    val customNamingTemplate: String = NamingFormat.DEFAULT_TEMPLATE,
    val debugNotificationEnabled: Boolean = false,
    val selectiveDownload: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showClipboardBubble: Boolean = true,
    val autoReadClipboard: Boolean = false,
    val manualInputLinks: Boolean = false,
    /** Persisted tree URI for custom downloads; null keeps the default MediaStore location. */
    val customStorageTreeUri: String? = null,
    /** Full readable path for [customStorageTreeUri], or null when the default is used. */
    val customStorageDisplayName: String? = null,
    /** Value of the removed legacy key, retained for diagnostics and compatibility. */
    val useMetadataFileNames: Boolean = false
)
