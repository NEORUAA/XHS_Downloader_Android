package com.neoruaa.xhsdn.viewmodels

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.core.model.*
import com.neoruaa.xhsdn.data.*
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.data.xhs.*
import com.neoruaa.xhsdn.domain.download.NoteOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class MediaItem(
    val media: StoredMediaRef,
    val type: MediaType = com.neoruaa.xhsdn.utils.detectMediaType(media)
) {
    val path: String
        get() = media.path

    constructor(path: String, type: MediaType) : this(
        media = StoredMediaRef.fromLocation(path),
        type = type
    )
}

enum class MediaType {
    IMAGE, VIDEO, OTHER
}

enum class SelectiveDownloadPhase {
    Idle, Caching, Ready, Saving, Error
}

data class CachedMediaItem(
    val path: String,
    val displayName: String,
    val type: MediaType,
    val previewUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val live: Boolean = false,
)

data class SelectiveDownloadUiState(
    val show: Boolean = false,
    val taskId: Long = 0,
    val phase: SelectiveDownloadPhase = SelectiveDownloadPhase.Idle,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val progressText: String = "0.0%｜0KB/s",
    val status: String = "",
    val items: List<CachedMediaItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val noteUrl: String = "",
    val noteContent: String? = null,
    val cacheDir: String? = null,
    val errorMessage: String? = null
)

data class MainUiState(
    val urlInput: String = "",
    val status: List<String> = emptyList(),
    val mediaItems: List<MediaItem> = emptyList(),
    val isDownloading: Boolean = false,
    val progressLabel: String = "",
    val progress: Float = 0f,
    val downloadProgressText: String = "0%｜0kb/s", // Format: "XX%｜XXXkb/s"
    val showWebCrawl: Boolean = false,
    val selectiveDownload: SelectiveDownloadUiState = SelectiveDownloadUiState()
)

class MainViewModel(application: Application, private val savedStateHandle: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val container = (application as XHSApplication).appContainer
    private val queue = container.downloadQueue
    private val tasks = container.taskRepository
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val downloadSpeeds: StateFlow<Map<Long, Long>> = queue.downloadSpeeds
    var currentTaskId: Long = 0
        private set
    private val autoSelections = savedStateHandle.getStateFlow("pendingSelections", emptyList<Long>())

    private fun updateAutoSelections(change: (Set<Long>) -> Set<Long>) {
        savedStateHandle["pendingSelections"] = change(autoSelections.value.toSet()).toList()
    }

    init {
        viewModelScope.launch {
            combine(queue.activeTask, queue.progress) { id, progress -> id to progress[id] }.collect { (id, fraction) ->
                currentTaskId = id ?: 0
                _uiState.update { it.copy(isDownloading = id != null, progress = fraction ?: 0f) }
            }
        }
        viewModelScope.launch {
            combine(tasks.observePendingTasks(), autoSelections,
                uiState.map { it.selectiveDownload.show }.distinctUntilChanged()) { pending, requested, visible ->
                if (visible) null else pending.firstOrNull { it.id in requested && it.status == TaskStatus.WAITING_FOR_USER }
            }.collectLatest { task ->
                if (task != null) showSelection(task.id)
            }
        }
    }

    fun updateUrl(value: String) { _uiState.update { it.copy(urlInput = value) } }
    fun pasteLinkFromClipboard() {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        updateUrl(clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty())
    }

    fun startDownload(onError: (String) -> Unit) = submit(false, false, onError)
    fun startSelectiveDownload(onError: (String) -> Unit) = submit(true, false, onError)
    fun saveNoteInformation(onError: (String) -> Unit) = submit(false, true, onError)
    private fun submit(selection: Boolean, infoOnly: Boolean, onError: (String) -> Unit) {
        val input = uiState.value.urlInput
        viewModelScope.launch {
            try {
                container.settingsRepository.awaitReady()
                val requireSelection = !infoOnly && (selection || container.settingsRepository.currentSettings.selectiveDownload)
                val ids = withContext(Dispatchers.IO) { queue.enqueue(input, requireSelection, infoOnly) }
                if (requireSelection) updateAutoSelections { it + ids }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { onError(queue.errorMessage(error)) }
        }
    }

    private suspend fun showSelection(id: Long) {
        val note = queue.resolved(id) ?: return
        val settings = queue.settings(id) ?: return
        val items = NoteOutput.eligible(note, settings.downloadOptions).mapIndexed { index, item ->
            val image = when (item) { is ResolvedMedia.Image -> item; is ResolvedMedia.LivePhoto -> item.image; else -> null }
            CachedMediaItem(item.id, (index + 1).toString(), if (item is ResolvedMedia.Video) MediaType.VIDEO else MediaType.IMAGE,
                item.previewUrl, image?.width ?: 0, image?.height ?: 0, item is ResolvedMedia.LivePhoto)
        }
        _uiState.update { it.copy(selectiveDownload = SelectiveDownloadUiState(show = true, taskId = id,
            phase = SelectiveDownloadPhase.Ready, items = items, selectedPaths = items.map { item -> item.path }.toSet(),
            noteUrl = note.canonicalUrl, noteContent = note.description)) }
    }

    fun cancelSelectiveDownload() {
        val id = uiState.value.selectiveDownload.taskId
        updateAutoSelections { it - id }
        _uiState.update { it.copy(selectiveDownload = SelectiveDownloadUiState()) }
        // Waiting is durable: dismissing the sheet keeps the task available for later selection.
    }

    fun toggleSelectiveItem(path: String) {
        _uiState.update { state -> state.copy(selectiveDownload = state.selectiveDownload.let {
            it.copy(selectedPaths = if (path in it.selectedPaths) it.selectedPaths - path else it.selectedPaths + path)
        }) }
    }
    fun toggleAllSelectiveItems() {
        _uiState.update { state -> state.copy(selectiveDownload = state.selectiveDownload.let {
            it.copy(selectedPaths = if (it.selectedPaths.size == it.items.size) emptySet() else it.items.map { item -> item.path }.toSet())
        }) }
    }

    fun saveSelectedMedia(onError: (String) -> Unit) {
        val selection = uiState.value.selectiveDownload
        if (selection.selectedPaths.isEmpty()) return
        cancelSelectiveDownload()
        viewModelScope.launch {
            try { queue.select(selection.taskId, selection.selectedPaths) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(queue.errorMessage(error)) }
        }
    }

    fun pauseTask(id: Long) { viewModelScope.launch { queue.pause(id) } }
    fun cancelTask(id: Long) { viewModelScope.launch { queue.cancel(id) } }
    fun deleteTask(id: Long) { viewModelScope.launch { queue.delete(id) } }
    fun cancelCurrentDownload() { if (currentTaskId != 0L) pauseTask(currentTaskId) }
    fun retryTask(task: DownloadTask, onError: (String) -> Unit) {
        viewModelScope.launch {
            try { queue.resume(task.id, refresh = task.status == TaskStatus.FAILED) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(queue.errorMessage(error)) }
        }
    }
    fun continueTask(task: DownloadTask) {
        viewModelScope.launch {
            if (task.status == TaskStatus.WAITING_FOR_USER && queue.resolved(task.id) != null) showSelection(task.id) else queue.resume(task.id)
        }
    }

    fun copyDescription(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val input = uiState.value.urlInput
        viewModelScope.launch {
            try {
                container.settingsRepository.awaitReady()
                val source = OkHttpXhsPageSource(container.network.client(container.settingsRepository.currentSettings.downloadOptions, false))
                val note = DefaultXhsContentRepository(source::fetchHtml, source::resolveShortUrl).resolve(input)
                val text = note.description.orEmpty()
                (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("note", text))
                onResult(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(queue.errorMessage(error)) }
        }
    }

    fun onWebCrawlResult(urls: List<String>, content: String?, taskId: Long? = null, noteJson: String? = null) {
        viewModelScope.launch {
            try {
                val url = uiState.value.urlInput
                val note = noteJson?.let { XhsNoteParser().parseNote(JSONObject(it), url, XhsUrlParser.extractPostId(url)) }
                    ?: throw XhsResolveException(com.neoruaa.xhsdn.domain.download.DownloadFailure.RequiresWebView)
                val id = queue.acceptResolved(taskId, note)
                updateAutoSelections { it + id }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _uiState.update { it.copy(status = listOf(queue.errorMessage(error))) } }
        }
    }

    fun clearHistory() { viewModelScope.launch { queue.clearFinishedHistory() } }
    fun removeMediaItem(mediaItem: MediaItem) { _uiState.update { it.copy(mediaItems = it.mediaItems - mediaItem) } }
    fun resetWebCrawlFlag() { _uiState.update { it.copy(showWebCrawl = false) } }
    fun notifyWebCrawlSuggestion() { _uiState.update { it.copy(showWebCrawl = true) } }
}
