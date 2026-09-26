package com.neoruaa.xhsdn.feature.selection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.data.settings.DownloadJson
import com.neoruaa.xhsdn.data.tasks.DownloadSessionEntity
import com.neoruaa.xhsdn.viewmodels.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionQueueTest {
    @Test fun dismissingOneSelectionOpensTheNextAndPendingIdsSurviveRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XHSApplication>()
        val container = app.appContainer
        container.initialization.await()
        val ids = mutableListOf<Long>()
        var model: MainViewModel? = null
        fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        try {
            repeat(2) { index ->
                val id = container.taskRepository.createTask("https://www.xiaohongshu.com/explore/selection_fixture_$index", "Selection fixture", NoteType.IMAGE, 1)
                ids += id
                val note = ResolvedNote("", "Fixture", "", null, null, null, noteId = "selection_fixture_$index",
                    items = listOf(ResolvedMedia.Image("https://example.invalid/preview.jpg", id = "image-$index")))
                container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(id,
                    DownloadJson.encodeToString(AppSettings()), resolvedJson = DownloadJson.encodeToString(note), requireSelection = true))
                container.taskRepository.updateTaskStatus(id, TaskStatus.WAITING_FOR_USER)
            }
            val handle = SavedStateHandle(mapOf("pendingSelections" to ids.toList()))
            onMain { model = MainViewModel(app, handle) }
            val first = withTimeout(5_000) { model!!.uiState.first { it.selectiveDownload.show } }.selectiveDownload.taskId
            onMain { model!!.cancelSelectiveDownload() }
            val second = withTimeout(5_000) { model!!.uiState.first { it.selectiveDownload.show && it.selectiveDownload.taskId != first } }.selectiveDownload.taskId
            assertEquals(ids.toSet(), setOf(first, second))
            assertEquals(TaskStatus.WAITING_FOR_USER, container.taskRepository.getTaskById(first)?.status)
            onMain {
                model!!.viewModelScope.cancel()
                model = MainViewModel(app, SavedStateHandle(mapOf("pendingSelections" to handle.get<List<Long>>("pendingSelections"))))
            }
            val restored = withTimeout(5_000) { model!!.uiState.first { it.selectiveDownload.show } }
            assertEquals(second, restored.selectiveDownload.taskId)
            onMain { model!!.cancelSelectiveDownload() }
            delay(200)
            assertFalse(model!!.uiState.value.selectiveDownload.show)
        } finally {
            onMain { model?.viewModelScope?.cancel() }
            ids.forEach { container.downloadQueue.delete(it) }
        }
    }
}
