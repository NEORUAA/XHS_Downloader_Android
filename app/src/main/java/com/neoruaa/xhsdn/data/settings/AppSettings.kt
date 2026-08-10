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
    /** Value of the removed legacy key, retained for diagnostics and compatibility. */
    val useMetadataFileNames: Boolean = false
)
