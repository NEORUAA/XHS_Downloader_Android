package com.neoruaa.xhsdn.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.neoruaa.xhsdn.NamingFormat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

internal fun formatStorageDocumentPath(documentId: String): String? {
    val separatorIndex = documentId.indexOf(':')
    if (separatorIndex <= 0) return null

    val volumeId = documentId.substring(0, separatorIndex)
    val relativePath = documentId.substring(separatorIndex + 1).trim('/')
    if (volumeId.equals("raw", ignoreCase = true)) {
        return relativePath.takeIf(String::isNotBlank)?.let { "/$it" }
    }

    val rootPath = when {
        volumeId.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
        volumeId.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
        else -> "/storage/$volumeId"
    }
    return if (relativePath.isBlank()) rootPath else "$rootPath/$relativePath"
}

internal fun storageDisplayPathFromTreeUri(treeUri: String): String? = runCatching {
    formatStorageDocumentPath(DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
}.getOrNull()

class DataStoreSettingsRepository(
    context: Context,
    private val scope: CoroutineScope
) : SettingsRepository {
    private val appContext = context.applicationContext
    private val legacyPreferences: SharedPreferences = appContext.getSharedPreferences(
        LEGACY_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { appContext.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
    )

    @Volatile
    override var currentSettings: AppSettings = legacySettings()
        private set

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toSettings)
        .onEach { currentSettings = it }

    init {
        scope.launch {
            migrateLegacyPreferences()
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(currentSettings).normalize()
        currentSettings = updated
        dataStore.edit { preferences ->
            preferences[CREATE_LIVE_PHOTOS] = updated.createLivePhotos
            preferences[USE_CUSTOM_NAMING_FORMAT] = updated.useCustomNamingFormat
            preferences[CUSTOM_NAMING_TEMPLATE] = updated.customNamingTemplate
            preferences[DEBUG_NOTIFICATION_ENABLED] = updated.debugNotificationEnabled
            preferences[SELECTIVE_DOWNLOAD] = updated.selectiveDownload
            preferences[KEEP_SCREEN_ON] = updated.keepScreenOn
            preferences[SHOW_CLIPBOARD_BUBBLE] = updated.showClipboardBubble
            preferences[AUTO_READ_CLIPBOARD] = updated.autoReadClipboard
            preferences[MANUAL_INPUT_LINKS] = updated.manualInputLinks
            if (updated.customStorageTreeUri == null) {
                preferences.remove(CUSTOM_STORAGE_TREE_URI)
                preferences.remove(CUSTOM_STORAGE_DISPLAY_NAME)
            } else {
                preferences[CUSTOM_STORAGE_TREE_URI] = updated.customStorageTreeUri
                updated.customStorageDisplayName?.let {
                    preferences[CUSTOM_STORAGE_DISPLAY_NAME] = it
                } ?: preferences.remove(CUSTOM_STORAGE_DISPLAY_NAME)
            }
            preferences[CHECK_EXISTING_FILES_BEFORE_SAVE] = updated.checkExistingFilesBeforeSave
            preferences[USE_METADATA_FILE_NAMES] = updated.useMetadataFileNames
        }
    }

    private suspend fun migrateLegacyPreferences() {
        dataStore.edit { preferences ->
            if ((preferences[MIGRATION_VERSION] ?: 0) >= MIGRATION_VERSION_CURRENT) return@edit

            if (!preferences.contains(CREATE_LIVE_PHOTOS)) {
                preferences[CREATE_LIVE_PHOTOS] = legacyBoolean("create_live_photos", true)
            }
            if (!preferences.contains(USE_CUSTOM_NAMING_FORMAT)) {
                preferences[USE_CUSTOM_NAMING_FORMAT] = legacyUseCustomNaming()
            }
            if (!preferences.contains(CUSTOM_NAMING_TEMPLATE)) {
                preferences[CUSTOM_NAMING_TEMPLATE] = legacyTemplate()
            }
            if (!preferences.contains(DEBUG_NOTIFICATION_ENABLED)) {
                preferences[DEBUG_NOTIFICATION_ENABLED] = legacyBoolean("debug_notification_enabled", false)
            }
            if (!preferences.contains(SELECTIVE_DOWNLOAD)) {
                preferences[SELECTIVE_DOWNLOAD] = legacyBoolean("selective_download", false)
            }
            if (!preferences.contains(KEEP_SCREEN_ON)) {
                preferences[KEEP_SCREEN_ON] = legacyBoolean("keep_screen_on", false)
            }
            if (!preferences.contains(SHOW_CLIPBOARD_BUBBLE)) {
                preferences[SHOW_CLIPBOARD_BUBBLE] = legacyBoolean("show_clipboard_bubble", true)
            }
            if (!preferences.contains(AUTO_READ_CLIPBOARD)) {
                preferences[AUTO_READ_CLIPBOARD] = legacyBoolean("auto_read_clipboard", false)
            }
            if (!preferences.contains(MANUAL_INPUT_LINKS)) {
                preferences[MANUAL_INPUT_LINKS] = legacyBoolean("manual_input_links", false)
            }
            // Custom storage was introduced after the legacy preferences migration. Missing
            // values intentionally remain absent so they map to the default MediaStore path.
            if (!preferences.contains(USE_METADATA_FILE_NAMES)) {
                preferences[USE_METADATA_FILE_NAMES] = legacyBoolean("use_metadata_file_names", false)
            }
            if (!preferences.contains(CHECK_EXISTING_FILES_BEFORE_SAVE)) {
                preferences[CHECK_EXISTING_FILES_BEFORE_SAVE] = true
            }
            preferences[MIGRATION_VERSION] = MIGRATION_VERSION_CURRENT
        }
    }

    private fun toSettings(preferences: Preferences): AppSettings {
        val customStorageTreeUri = preferences[CUSTOM_STORAGE_TREE_URI]
            ?.trim()
            ?.ifBlank { null }
        val storedCustomStorageDisplayName = preferences[CUSTOM_STORAGE_DISPLAY_NAME]
            ?.trim()
            ?.ifBlank { null }
        return AppSettings(
            createLivePhotos = preferences[CREATE_LIVE_PHOTOS]
                ?: legacyBoolean("create_live_photos", true),
            useCustomNamingFormat = preferences[USE_CUSTOM_NAMING_FORMAT]
                ?: legacyUseCustomNaming(),
            customNamingTemplate = preferences[CUSTOM_NAMING_TEMPLATE]
                ?.trim()
                ?.ifBlank { NamingFormat.DEFAULT_TEMPLATE }
                ?: legacyTemplate(),
            debugNotificationEnabled = preferences[DEBUG_NOTIFICATION_ENABLED]
                ?: legacyBoolean("debug_notification_enabled", false),
            selectiveDownload = preferences[SELECTIVE_DOWNLOAD]
                ?: legacyBoolean("selective_download", false),
            keepScreenOn = preferences[KEEP_SCREEN_ON]
                ?: legacyBoolean("keep_screen_on", false),
            showClipboardBubble = preferences[SHOW_CLIPBOARD_BUBBLE]
                ?: legacyBoolean("show_clipboard_bubble", true),
            autoReadClipboard = preferences[AUTO_READ_CLIPBOARD]
                ?: legacyBoolean("auto_read_clipboard", false),
            manualInputLinks = preferences[MANUAL_INPUT_LINKS]
                ?: legacyBoolean("manual_input_links", false),
            customStorageTreeUri = customStorageTreeUri,
            customStorageDisplayName = customStorageTreeUri?.let(::storageDisplayPathFromTreeUri)
                ?: storedCustomStorageDisplayName?.takeIf { customStorageTreeUri != null },
            checkExistingFilesBeforeSave = preferences[CHECK_EXISTING_FILES_BEFORE_SAVE] ?: true,
            useMetadataFileNames = preferences[USE_METADATA_FILE_NAMES]
                ?: legacyBoolean("use_metadata_file_names", false)
        )
    }

    private fun legacySettings(): AppSettings = AppSettings(
        createLivePhotos = legacyBoolean("create_live_photos", true),
        useCustomNamingFormat = legacyUseCustomNaming(),
        customNamingTemplate = legacyTemplate(),
        debugNotificationEnabled = legacyBoolean("debug_notification_enabled", false),
        selectiveDownload = legacyBoolean("selective_download", false),
        keepScreenOn = legacyBoolean("keep_screen_on", false),
        showClipboardBubble = legacyBoolean("show_clipboard_bubble", true),
        autoReadClipboard = legacyBoolean("auto_read_clipboard", false),
        manualInputLinks = legacyBoolean("manual_input_links", false),
        customStorageTreeUri = null,
        customStorageDisplayName = null,
        checkExistingFilesBeforeSave = true,
        useMetadataFileNames = legacyBoolean("use_metadata_file_names", false)
    )

    private fun AppSettings.normalize(): AppSettings {
        val normalizedTreeUri = customStorageTreeUri?.trim()?.ifBlank { null }
        return copy(
            customNamingTemplate = customNamingTemplate.trim().ifBlank { NamingFormat.DEFAULT_TEMPLATE },
            customStorageTreeUri = normalizedTreeUri,
            customStorageDisplayName = normalizedTreeUri?.let(::storageDisplayPathFromTreeUri)
                ?: customStorageDisplayName?.trim()?.ifBlank { null }
                    ?.takeIf { normalizedTreeUri != null }
        )
    }

    private fun legacyBoolean(key: String, default: Boolean): Boolean =
        runCatching { legacyPreferences.getBoolean(key, default) }.getOrDefault(default)

    private fun legacyUseCustomNaming(): Boolean = if (legacyPreferences.contains("use_custom_naming_format")) {
        legacyBoolean("use_custom_naming_format", false)
    } else {
        legacyBoolean("use_metadata_file_names", false)
    }

    private fun legacyTemplate(): String = runCatching {
        legacyPreferences.getString("custom_naming_template", NamingFormat.DEFAULT_TEMPLATE)
            ?.trim()
            ?.ifBlank { NamingFormat.DEFAULT_TEMPLATE }
            ?: NamingFormat.DEFAULT_TEMPLATE
    }.getOrDefault(NamingFormat.DEFAULT_TEMPLATE)

    companion object {
        const val LEGACY_PREFS_NAME = "XHSDownloaderPrefs"
        const val DATASTORE_FILE_NAME = "xhs_settings.preferences_pb"
        private const val MIGRATION_VERSION_CURRENT = 3

        private val MIGRATION_VERSION = intPreferencesKey("legacy_migration_version")
        private val CREATE_LIVE_PHOTOS = booleanPreferencesKey("create_live_photos")
        private val USE_CUSTOM_NAMING_FORMAT = booleanPreferencesKey("use_custom_naming_format")
        private val CUSTOM_NAMING_TEMPLATE = stringPreferencesKey("custom_naming_template")
        private val DEBUG_NOTIFICATION_ENABLED = booleanPreferencesKey("debug_notification_enabled")
        private val SELECTIVE_DOWNLOAD = booleanPreferencesKey("selective_download")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val SHOW_CLIPBOARD_BUBBLE = booleanPreferencesKey("show_clipboard_bubble")
        private val AUTO_READ_CLIPBOARD = booleanPreferencesKey("auto_read_clipboard")
        private val MANUAL_INPUT_LINKS = booleanPreferencesKey("manual_input_links")
        private val CUSTOM_STORAGE_TREE_URI = stringPreferencesKey("custom_storage_tree_uri")
        private val CUSTOM_STORAGE_DISPLAY_NAME = stringPreferencesKey("custom_storage_display_name")
        private val CHECK_EXISTING_FILES_BEFORE_SAVE =
            booleanPreferencesKey("check_existing_files_before_save")
        private val USE_METADATA_FILE_NAMES = booleanPreferencesKey("use_metadata_file_names")
    }
}
