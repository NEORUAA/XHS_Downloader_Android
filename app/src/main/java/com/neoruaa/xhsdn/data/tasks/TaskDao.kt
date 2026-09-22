package com.neoruaa.xhsdn.data.tasks

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction
    @Query("SELECT * FROM download_tasks ORDER BY created_at DESC, id DESC")
    fun observeTasks(): Flow<List<TaskWithFiles>>

    @Transaction
    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<TaskWithFiles?>

    @Transaction
    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING','WAITING_FOR_USER','PAUSED') ORDER BY created_at LIMIT 100")
    fun observePendingTasks(): Flow<List<TaskWithFiles>>

    @Transaction
    @Query("""SELECT t.* FROM download_tasks t LEFT JOIN download_sessions s ON t.id = s.task_id
        WHERE (:filter = 0 OR (:filter = 1 AND t.status IN ('WAITING_FOR_USER','PAUSED')) OR (:filter = 2 AND t.status IN ('FAILED','PARTIAL')))
        AND (:query = '' OR instr(lower(t.note_url), lower(:query)) > 0 OR instr(lower(COALESCE(t.note_title,'')), lower(:query)) > 0
        OR instr(lower(COALESCE(t.note_content,'')), lower(:query)) > 0 OR instr(lower(COALESCE(s.note_id,'')), lower(:query)) > 0
        OR instr(lower(COALESCE(s.author_name,'')), lower(:query)) > 0)
        ORDER BY t.created_at DESC, t.id DESC LIMIT :limit""")
    fun observePage(query: String, filter: Int, limit: Int): Flow<List<TaskWithFiles>>

    @Query("""SELECT COUNT(*) AS total, COALESCE(SUM(status IN ('WAITING_FOR_USER','PAUSED')),0) AS waiting,
        COALESCE(SUM(status IN ('FAILED','PARTIAL')),0) AS failed FROM download_tasks""")
    fun observeCounts(): Flow<TaskCounts>

    @Query("DELETE FROM download_tasks WHERE status IN ('COMPLETED','FAILED','PARTIAL','CANCELLED','SKIPPED')")
    suspend fun clearFinishedTasks()

    @Transaction
    @Query("SELECT * FROM download_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: Long): TaskWithFiles?

    @Query("SELECT MAX(id) FROM download_tasks")
    suspend fun getMaxTaskId(): Long?

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<TaskFileEntity>)

    @Query(
        """
        UPDATE download_tasks
        SET note_title = :noteTitle,
            note_type = :noteType,
            total_files = :totalFiles,
            completed_files = :completedFiles,
            failed_files = :failedFiles,
            current_file_progress = :currentFileProgress,
            status = :status,
            completed_at = :completedAt,
            error_message = :errorMessage,
            note_content = :noteContent
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskFields(
        taskId: Long,
        noteTitle: String?,
        noteType: String,
        totalFiles: Int,
        completedFiles: Int,
        failedFiles: Int,
        currentFileProgress: Float,
        status: String,
        completedAt: Long?,
        errorMessage: String?,
        noteContent: String?
    )

    @Query(
        """
        UPDATE download_tasks
        SET completed_files = :completedFiles,
            failed_files = :failedFiles,
            current_file_progress = :currentFileProgress,
            status = :status,
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE id = :taskId
        """
    )
    suspend fun updateProgress(
        taskId: Long,
        completedFiles: Int,
        failedFiles: Int,
        currentFileProgress: Float,
        status: String,
        completedAt: Long?,
        errorMessage: String?
    )

    @Query("DELETE FROM download_task_files WHERE task_id = :taskId")
    suspend fun deleteFiles(taskId: Long)

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("DELETE FROM download_tasks")
    suspend fun deleteAllTasks()

    @Query("SELECT value FROM task_metadata WHERE `key` = :key LIMIT 1")
    suspend fun getMetadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: TaskMetadataEntity)

    @Transaction
    suspend fun replaceTask(task: TaskEntity, files: List<TaskFileEntity>) {
        upsertTask(task)
        deleteFiles(task.id)
        if (files.isNotEmpty()) {
            upsertFiles(files)
        }
    }

    @Transaction
    suspend fun replaceTasks(tasks: List<TaskWithFiles>) {
        tasks.forEach { replaceTask(it.task, it.files) }
    }
}
