package com.neoruaa.xhsdn.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoruaa.xhsdn.NamingFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreSettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DataStoreSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.preferencesDataStoreFile(DataStoreSettingsRepository.DATASTORE_FILE_NAME).delete()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.getSharedPreferences(DataStoreSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun migratesLegacyKeysDefaultsAndPersistsUpdates() = runBlocking {
        context.getSharedPreferences(DataStoreSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("create_live_photos", false)
            .putBoolean("use_metadata_file_names", true)
            .putBoolean("selective_download", true)
            .putString("custom_naming_template", "")
            .commit()

        val repository = DataStoreSettingsRepository(context, scope)
        val migrated = withTimeout(5_000) { repository.settings.first() }
        assertFalse(migrated.createLivePhotos)
        assertTrue(migrated.useCustomNamingFormat)
        assertTrue(migrated.selectiveDownload)
        assertEquals(NamingFormat.DEFAULT_TEMPLATE, migrated.customNamingTemplate)
        assertTrue(migrated.showClipboardBubble)

        repository.setManualInputLinks(true)
        assertTrue(repository.currentSettings.manualInputLinks)
        assertTrue(withTimeout(5_000) { repository.settings.first { it.manualInputLinks } }.manualInputLinks)
    }
}
