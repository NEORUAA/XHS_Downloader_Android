package com.neoruaa.xhsdn.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.tasks.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class HistoryFilter {
    All,
    WaitingForUser,
    Failed
}

data class HistoryUiState(
    val allTasks: List<DownloadTask> = emptyList(),
    val filteredTasks: List<DownloadTask> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.All,
    val query: String = "",
    val canLoadMore: Boolean = false,
    val counts: com.neoruaa.xhsdn.data.tasks.TaskCounts? = null
) {
    val waitingCount: Int
        get() = counts?.waiting ?: allTasks.count { it.status in setOf(TaskStatus.WAITING_FOR_USER, TaskStatus.PAUSED) }

    val failedCount: Int
        get() = counts?.failed ?: allTasks.count { it.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL) }

    val hasActiveSearch: Boolean
        get() = query.isNotBlank()
}

class HistoryViewModel(
    private val savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository
) : ViewModel() {
    private val query = savedStateHandle.getStateFlow(KEY_QUERY, "")
    private val selectedFilter = savedStateHandle.getStateFlow(
        KEY_FILTER,
        HistoryFilter.All
    )

    private val limit = MutableStateFlow(50)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val page = combine(query, selectedFilter, limit) { q, f, l -> Triple(q, f, l) }
        .flatMapLatest { (q, f, l) -> taskRepository.observePage(q.trim(), f.ordinal, l + 1).map { rows ->
            HistoryUiState(rows.take(l), rows.take(l), f, q, rows.size > l)
        } }
    val uiState: StateFlow<HistoryUiState> = combine(page, taskRepository.observeCounts()) { page, counts ->
        page.copy(counts = counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun loadMore() { limit.value += 50 }

    fun updateQuery(value: String) {
        limit.value = 50
        savedStateHandle[KEY_QUERY] = value
    }

    fun clearQuery() {
        savedStateHandle[KEY_QUERY] = ""
    }

    fun selectFilter(filter: HistoryFilter) {
        limit.value = 50
        savedStateHandle[KEY_FILTER] = filter
    }

    companion object {
        const val KEY_QUERY = "history_query"
        const val KEY_FILTER = "history_filter"

        fun factory(taskRepository: TaskRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    taskRepository = taskRepository
                )
            }
        }
    }
}

internal fun filterHistoryTasks(
    tasks: List<DownloadTask>,
    filter: HistoryFilter,
    rawQuery: String
): List<DownloadTask> {
    val query = rawQuery.trim()
    return tasks.filter { task ->
        val matchesFilter = when (filter) {
            HistoryFilter.All -> true
            HistoryFilter.WaitingForUser -> task.status in setOf(TaskStatus.WAITING_FOR_USER, TaskStatus.PAUSED)
            HistoryFilter.Failed -> task.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL)
        }
        val matchesQuery = query.isEmpty() ||
            task.noteUrl.contains(query, ignoreCase = true) ||
            task.noteTitle?.contains(query, ignoreCase = true) == true ||
            task.noteContent?.contains(query, ignoreCase = true) == true
        matchesFilter && matchesQuery
    }
}
