package com.neoruaa.xhsdn.data.tasks

import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/** Persistence boundary used by features and the legacy TaskManager facade. */
interface TaskRepository {
    fun observeTasks(): Flow<List<DownloadTask>>
    fun observeTask(id: Long): Flow<DownloadTask?> = observeTasks().map { list -> list.firstOrNull { it.id == id } }
    fun observePendingTasks(): Flow<List<DownloadTask>> = observeTasks().map { list -> list.filter { it.isActive || it.status == TaskStatus.PAUSED } }
    fun observePage(query: String, filter: Int, limit: Int): Flow<List<DownloadTask>> = observeTasks().map { list ->
        list.filter { (filter == 0 || (filter == 1 && it.status in setOf(TaskStatus.WAITING_FOR_USER, TaskStatus.PAUSED)) ||
            (filter == 2 && it.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL))) &&
            (query.isBlank() || listOf(it.noteUrl, it.noteTitle.orEmpty(), it.noteContent.orEmpty()).any { value -> value.contains(query, true) }) }.take(limit)
    }
    fun observeCounts(): Flow<TaskCounts> = observeTasks().map { list -> TaskCounts(list.size, list.count { it.status in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_FOR_USER) }, list.count { it.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL) }) }
    suspend fun clearFinishedTasks() { observeTasks().first().filter { it.isCompleted }.forEach { deleteTask(it.id) } }

    suspend fun getTaskById(taskId: Long): DownloadTask?

    suspend fun insertTask(task: DownloadTask)

    suspend fun createTask(
        noteUrl: String,
        noteTitle: String?,
        noteType: NoteType,
        totalFiles: Int,
        noteContent: String? = null
    ): Long

    suspend fun startTask(taskId: Long)

    suspend fun updateProgress(
        taskId: Long,
        completedFiles: Int,
        failedFiles: Int,
        currentFileProgress: Float = 0f
    )

    suspend fun addMediaRef(taskId: Long, media: StoredMediaRef)

    suspend fun removeMediaRef(taskId: Long, location: String)

    suspend fun completeTask(taskId: Long, success: Boolean, errorMessage: String? = null)

    suspend fun deleteTask(taskId: Long)

    suspend fun clearAllTasks()

    suspend fun updateTaskType(taskId: Long, noteType: NoteType)

    suspend fun resetTask(taskId: Long)

    suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, errorMessage: String? = null)

    suspend fun updateTask(taskId: Long, update: (DownloadTask) -> DownloadTask)

    suspend fun setNextId(nextId: Long)
}

data class TaskCounts(val total: Int = 0, val waiting: Int = 0, val failed: Int = 0)
