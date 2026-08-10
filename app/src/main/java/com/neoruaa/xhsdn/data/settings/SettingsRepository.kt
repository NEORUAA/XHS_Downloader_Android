package com.neoruaa.xhsdn.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Latest value for synchronous download and notification paths. */
    val currentSettings: AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setCreateLivePhotos(enabled: Boolean) = update { it.copy(createLivePhotos = enabled) }

    suspend fun setUseCustomNamingFormat(enabled: Boolean) =
        update { it.copy(useCustomNamingFormat = enabled) }

    suspend fun setCustomNamingTemplate(template: String) = update {
        it.copy(customNamingTemplate = template)
    }

    suspend fun setDebugNotificationEnabled(enabled: Boolean) =
        update { it.copy(debugNotificationEnabled = enabled) }

    suspend fun setSelectiveDownload(enabled: Boolean) = update { it.copy(selectiveDownload = enabled) }

    suspend fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOn = enabled) }

    suspend fun setShowClipboardBubble(enabled: Boolean) =
        update { it.copy(showClipboardBubble = enabled) }

    suspend fun setAutoReadClipboard(enabled: Boolean) = update { it.copy(autoReadClipboard = enabled) }

    suspend fun setManualInputLinks(enabled: Boolean) = update { it.copy(manualInputLinks = enabled) }

    suspend fun setCustomStorageLocation(uri: String?, displayName: String?) = update {
        if (uri.isNullOrBlank()) {
            it.copy(customStorageTreeUri = null, customStorageDisplayName = null)
        } else {
            it.copy(
                customStorageTreeUri = uri,
                customStorageDisplayName = displayName
            )
        }
    }

    suspend fun clearCustomStorageLocation() = setCustomStorageLocation(null, null)
}
