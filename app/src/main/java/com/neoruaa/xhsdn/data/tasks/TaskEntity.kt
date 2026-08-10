package com.neoruaa.xhsdn.data.tasks

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus

/**
 * Room representation of a download task.
 *
 * Enum values are stored as strings so adding a value does not silently change the
 * meaning of existing rows.
 */
@Entity(tableName = "download_tasks")
data class TaskEntity(
    @PrimaryKey
    val id: Long,
    @ColumnInfo(name = "note_url")
    val noteUrl: String,
    @ColumnInfo(name = "note_title")
    val noteTitle: String?,
    @ColumnInfo(name = "note_type")
    val noteType: String,
    @ColumnInfo(name = "total_files")
    val totalFiles: Int,
    @ColumnInfo(name = "completed_files")
    val completedFiles: Int,
    @ColumnInfo(name = "failed_files")
    val failedFiles: Int,
    @ColumnInfo(name = "current_file_progress")
    val currentFileProgress: Float,
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "note_content")
    val noteContent: String?
)

@Entity(
    tableName = "download_task_files",
    primaryKeys = ["task_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("task_id")]
)
data class TaskFileEntity(
    @ColumnInfo(name = "task_id")
    val taskId: Long,
    val path: String,
    val position: Int
)

@Entity(tableName = "task_metadata")
data class TaskMetadataEntity(
    @PrimaryKey
    val key: String,
    val value: String
)

data class TaskWithFiles(
    @Embedded
    val task: TaskEntity,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["task_id"]
    )
    val files: List<TaskFileEntity>
)

internal fun DownloadTask.toEntity(): TaskEntity = TaskEntity(
    id = id,
    noteUrl = noteUrl,
    noteTitle = noteTitle,
    noteType = noteType.name,
    totalFiles = totalFiles,
    completedFiles = completedFiles,
    failedFiles = failedFiles,
    currentFileProgress = currentFileProgress,
    status = status.name,
    createdAt = createdAt,
    completedAt = completedAt,
    errorMessage = errorMessage,
    noteContent = noteContent
)

internal fun TaskWithFiles.toModel(): DownloadTask {
    val safeType = runCatching { NoteType.valueOf(task.noteType) }.getOrDefault(NoteType.UNKNOWN)
    val safeStatus = runCatching { TaskStatus.valueOf(task.status) }.getOrDefault(TaskStatus.COMPLETED)
    return DownloadTask(
        id = task.id,
        noteUrl = task.noteUrl,
        noteTitle = task.noteTitle,
        noteType = safeType,
        totalFiles = task.totalFiles,
        completedFiles = task.completedFiles,
        failedFiles = task.failedFiles,
        currentFileProgress = task.currentFileProgress,
        status = safeStatus,
        createdAt = task.createdAt,
        completedAt = task.completedAt,
        errorMessage = task.errorMessage,
        filePaths = files.sortedBy { it.position }.map { it.path },
        noteContent = task.noteContent
    )
}

internal fun DownloadTask.toFileEntities(): List<TaskFileEntity> = filePaths
    .mapIndexed { index, path -> TaskFileEntity(taskId = id, path = path, position = index) }
