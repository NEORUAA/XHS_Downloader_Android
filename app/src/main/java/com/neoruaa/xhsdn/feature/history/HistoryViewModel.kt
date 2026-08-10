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
    val query: String = ""
) {
    val waitingCount: Int
        get() = allTasks.count { it.status == TaskStatus.WAITING_FOR_USER }

    val failedCount: Int
        get() = allTasks.count { it.status == TaskStatus.FAILED }

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

    val uiState: StateFlow<HistoryUiState> = combine(
        taskRepository.observeTasks(),
        query,
        selectedFilter
    ) { tasks, currentQuery, currentFilter ->
        HistoryUiState(
            allTasks = tasks,
            filteredTasks = filterHistoryTasks(tasks, currentFilter, currentQuery),
            selectedFilter = currentFilter,
            query = currentQuery
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState()
    )

    fun updateQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    fun clearQuery() {
        savedStateHandle[KEY_QUERY] = ""
    }

    fun selectFilter(filter: HistoryFilter) {
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
            HistoryFilter.WaitingForUser -> task.status == TaskStatus.WAITING_FOR_USER
            HistoryFilter.Failed -> task.status == TaskStatus.FAILED
        }
        val matchesQuery = query.isEmpty() ||
            task.noteUrl.contains(query, ignoreCase = true) ||
            task.noteContent?.contains(query, ignoreCase = true) == true
        matchesFilter && matchesQuery
    }
}
