package com.neoruaa.xhsdn.data.tasks

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

class RoomTaskRepository(
    private val database: TaskDatabase,
    private val dao: TaskDao = database.taskDao()
) : TaskRepository {

    override fun observeTasks(): Flow<List<DownloadTask>> = dao.observeTasks().map { rows ->
        rows.map(TaskWithFiles::toModel)
    }

    override suspend fun getTaskById(taskId: Long): DownloadTask? = database.withReadTransaction {
        dao.getTask(taskId)?.toModel()
    }

    override suspend fun insertTask(task: DownloadTask) {
        database.withWriteTransaction {
            dao.replaceTask(task.toEntity(), task.toFileEntities())
            val currentNext = dao.getMetadata(TaskDatabaseConstants.NEXT_ID_KEY)?.toLongOrNull()
            if (currentNext == null || currentNext <= task.id) {
                dao.upsertMetadata(
                    TaskMetadataEntity(TaskDatabaseConstants.NEXT_ID_KEY, (task.id + 1).toString())
                )
            }
        }
    }

    override suspend fun createTask(
        noteUrl: String,
        noteTitle: String?,
        noteType: NoteType,
        totalFiles: Int,
        noteContent: String?
    ): Long = database.withWriteTransaction {
        val maxExistingId = dao.getMaxTaskId() ?: 0L
        val storedNextId = dao.getMetadata(TaskDatabaseConstants.NEXT_ID_KEY)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        val taskId = max(storedNextId ?: 1L, maxExistingId + 1L)
        val task = DownloadTask(
            id = taskId,
            noteUrl = noteUrl,
            noteTitle = noteTitle,
            noteType = noteType,
            totalFiles = totalFiles,
            status = TaskStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
            noteContent = noteContent
        )
        dao.replaceTask(task.toEntity(), emptyList())
        dao.upsertMetadata(TaskMetadataEntity(TaskDatabaseConstants.NEXT_ID_KEY, (taskId + 1L).toString()))
        taskId
    }

    override suspend fun startTask(taskId: Long) {
        updateTask(taskId) { it.copy(status = TaskStatus.DOWNLOADING) }
    }

    override suspend fun updateProgress(
        taskId: Long,
        completedFiles: Int,
        failedFiles: Int,
        currentFileProgress: Float
    ) {
        database.withWriteTransaction {
            val task = dao.getTask(taskId)?.toModel() ?: return@withWriteTransaction
            val boundedCompleted = completedFiles.coerceAtLeast(0)
            val boundedFailed = failedFiles.coerceAtLeast(0)
            val boundedCurrent = currentFileProgress.coerceIn(0f, 1f)
            val newProgress = if (task.totalFiles > 0) {
                (boundedCompleted + boundedCurrent) / task.totalFiles.toFloat()
            } else {
                0f
            }
            val oldProgress = task.progress
            if (newProgress >= oldProgress) {
                val totalFinished = boundedCompleted + boundedFailed
                val newStatus = when {
                    totalFinished >= task.totalFiles && boundedFailed > 0 -> TaskStatus.FAILED
                    totalFinished >= task.totalFiles -> TaskStatus.COMPLETED
                    else -> TaskStatus.DOWNLOADING
                }
                val completedAt = if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.FAILED) {
                    task.completedAt ?: System.currentTimeMillis()
                } else {
                    null
                }
                dao.updateProgress(
                    taskId = taskId,
                    completedFiles = boundedCompleted,
                    failedFiles = boundedFailed,
                    currentFileProgress = boundedCurrent,
                    status = newStatus.name,
                    completedAt = completedAt,
                    errorMessage = if (boundedFailed > 0) task.errorMessage else null
                )
            }
        }
    }

    override suspend fun addMediaRef(taskId: Long, media: StoredMediaRef) {
        updateTask(taskId) { task ->
            if (task.mediaRefs.any { it.path == media.path }) task
            else task.copy(mediaRefs = task.mediaRefs + media)
        }
    }

    override suspend fun removeMediaRef(taskId: Long, location: String) {
        updateTask(taskId) { task ->
            task.copy(mediaRefs = task.mediaRefs.filterNot { it.path == location })
        }
    }

    override suspend fun completeTask(taskId: Long, success: Boolean, errorMessage: String?) {
        updateTask(taskId) { task ->
            task.copy(
                status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        }
    }

    override suspend fun deleteTask(taskId: Long) {
        database.withWriteTransaction { dao.deleteTask(taskId) }
    }

    override suspend fun clearAllTasks() {
        database.withWriteTransaction { dao.deleteAllTasks() }
    }

    override suspend fun updateTaskType(taskId: Long, noteType: NoteType) {
        updateTask(taskId) { it.copy(noteType = noteType) }
    }

    override suspend fun resetTask(taskId: Long) {
        updateTask(taskId) { task ->
            task.copy(
                status = TaskStatus.DOWNLOADING,
                completedFiles = 0,
                failedFiles = 0,
                currentFileProgress = 0f,
                mediaRefs = emptyList(),
                errorMessage = null,
                completedAt = null
            )
        }
    }

    override suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, errorMessage: String?) {
        updateTask(taskId) { it.copy(status = status, errorMessage = errorMessage) }
    }

    override suspend fun updateTask(taskId: Long, update: (DownloadTask) -> DownloadTask) {
        database.withWriteTransaction {
            val current = dao.getTask(taskId)?.toModel() ?: return@withWriteTransaction
            val updated = update(current)
            if (updated.id != current.id) return@withWriteTransaction
            if (updated.mediaRefs == current.mediaRefs) {
                val entity = updated.toEntity()
                dao.updateTaskFields(
                    taskId = entity.id,
                    noteTitle = entity.noteTitle,
                    noteType = entity.noteType,
                    totalFiles = entity.totalFiles,
                    completedFiles = entity.completedFiles,
                    failedFiles = entity.failedFiles,
                    currentFileProgress = entity.currentFileProgress,
                    status = entity.status,
                    completedAt = entity.completedAt,
                    errorMessage = entity.errorMessage,
                    noteContent = entity.noteContent
                )
            } else {
                dao.replaceTask(updated.toEntity(), updated.toFileEntities())
            }
        }
    }

    override suspend fun setNextId(nextId: Long) {
        database.withWriteTransaction {
            val safeNextId = nextId.coerceAtLeast(1L)
            val currentNext = dao.getMetadata(TaskDatabaseConstants.NEXT_ID_KEY)?.toLongOrNull() ?: 1L
            if (safeNextId > currentNext) {
                dao.upsertMetadata(TaskMetadataEntity(TaskDatabaseConstants.NEXT_ID_KEY, safeNextId.toString()))
            }
        }
    }
}
