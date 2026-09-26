package com.neoruaa.xhsdn.feature.settings

import androidx.lifecycle.viewModelScope
import com.neoruaa.xhsdn.SettingsViewModel
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test fun staleUiEmissionCannotResetPreviouslySavedSwitches() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = DelayedSettings()
        val model = SettingsViewModel(repository)
        try {
            runCurrent()
            model.onManualInputLinksChange(true)
            runCurrent()
            assertTrue(repository.currentSettings.manualInputLinks)
            // Simulate an older observer value arriving before the next action.
            repository.settings.value = AppSettings(keepScreenOn = true)
            runCurrent()
            model.onShowMediaResolutionChange(true)
            runCurrent()
            model.onSelectiveDownloadChange(true)
            runCurrent()
            assertTrue(repository.currentSettings.manualInputLinks)
            assertTrue(repository.currentSettings.selectiveDownload)
            assertTrue(repository.currentSettings.showMediaResolution)
            assertFalse(repository.currentSettings.keepScreenOn)
            repository.settings.value = repository.currentSettings
            runCurrent()
            assertTrue(model.state.value.manualInputLinks)
            assertTrue(model.state.value.selectiveDownload)
            assertTrue(model.state.value.showMediaResolution)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun loadingSettingsBeforeAnEditPreservesUnrelatedPreferences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = DelayedSettings(AppSettings(manualInputLinks = true, selectiveDownload = true, showMediaResolution = true))
        val model = SettingsViewModel(repository)
        try {
            model.onKeepScreenOnChange(true)
            advanceUntilIdle()
            assertTrue(repository.currentSettings.manualInputLinks)
            assertTrue(repository.currentSettings.selectiveDownload)
            assertTrue(repository.currentSettings.showMediaResolution)
            assertTrue(repository.currentSettings.keepScreenOn)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class DelayedSettings(private val disk: AppSettings = AppSettings()) : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override var currentSettings = AppSettings()
        private var loaded = false
        override suspend fun awaitReady() {
            if (!loaded) { currentSettings = disk; loaded = true }
        }
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            currentSettings = transform(currentSettings)
        }
    }
}
