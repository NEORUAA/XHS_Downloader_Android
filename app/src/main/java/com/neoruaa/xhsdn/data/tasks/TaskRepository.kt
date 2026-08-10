package com.neoruaa.xhsdn.data.tasks

import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.flow.Flow

/** Persistence boundary used by features and the legacy TaskManager facade. */
interface TaskRepository {
    fun observeTasks(): Flow<List<DownloadTask>>

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
