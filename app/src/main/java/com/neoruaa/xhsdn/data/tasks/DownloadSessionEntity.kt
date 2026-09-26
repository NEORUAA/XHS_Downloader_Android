package com.neoruaa.xhsdn.data.tasks

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download_sessions", foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("note_id")])
data class DownloadSessionEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    @ColumnInfo(name = "resolved_json") val resolvedJson: String? = null,
    @ColumnInfo(name = "selected_json") val selectedJson: String? = null,
    @ColumnInfo(name = "require_selection") val requireSelection: Boolean = false,
    @ColumnInfo(name = "info_only") val infoOnly: Boolean = false,
    @ColumnInfo(name = "note_id") val noteId: String = "",
    @ColumnInfo(name = "author_name") val authorName: String = "",
)

@Entity(tableName = "download_resources", primaryKeys = ["task_id", "media_id"], foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("task_id"), Index("record_key")])
data class DownloadResourceEntity(
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    val state: String = "PENDING",
    @ColumnInfo(name = "refs_json") val refsJson: String = "[]",
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long = 0,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0,
    val warning: String? = null,
)

@Entity(tableName = "note_authors")
data class NoteAuthorEntity(
    @PrimaryKey val id: String,
    val nickname: String,
    val remark: String = "",
)

@Dao
interface DownloadSessionDao {
    @Query("""SELECT t.id AS taskId, s.note_id AS noteId, s.author_name AS author, COALESCE(t.note_title,'') AS title,
        r.media_id AS mediaId, r.state AS state, t.created_at AS createdAt
        FROM download_resources r JOIN download_sessions s ON r.task_id = s.task_id JOIN download_tasks t ON t.id = r.task_id
        WHERE r.state IN ('COMPLETED','SKIPPED') AND r.media_id NOT LIKE '%:output:%'
        ORDER BY t.id, r.media_id LIMIT :limit OFFSET :offset""")
    suspend fun exportPage(limit: Int, offset: Int): List<DownloadRecord>
    @Query("SELECT * FROM download_sessions WHERE task_id = :id") fun observeSession(id: Long): Flow<DownloadSessionEntity?>
    @Query("SELECT * FROM download_resources WHERE task_id = :id") fun observeResources(id: Long): Flow<List<DownloadResourceEntity>>
    @Upsert suspend fun saveSession(session: DownloadSessionEntity)
    @Query("SELECT * FROM download_sessions WHERE task_id = :id") suspend fun session(id: Long): DownloadSessionEntity?
    @Upsert suspend fun saveResource(resource: DownloadResourceEntity)
    @Query("SELECT * FROM download_resources WHERE task_id = :id") suspend fun resources(id: Long): List<DownloadResourceEntity>
    @Query("SELECT * FROM download_resources WHERE record_key = :key AND state IN ('COMPLETED','SKIPPED') ORDER BY task_id DESC LIMIT 10")
    suspend fun completedResources(key: String): List<DownloadResourceEntity>
    @Query("SELECT t.id FROM download_tasks t INNER JOIN download_sessions s ON t.id = s.task_id WHERE t.status = 'QUEUED' ORDER BY t.created_at, t.id LIMIT 1") suspend fun nextQueued(): Long?
    @Query("UPDATE download_tasks SET status = 'PAUSED' WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING')") suspend fun recoverInterrupted()
    @Query("SELECT id FROM download_tasks WHERE status IN ('COMPLETED','FAILED','PARTIAL','CANCELLED','SKIPPED')") suspend fun finishedIds(): List<Long>
    @Query("SELECT * FROM note_authors ORDER BY nickname, id") fun observeAuthors(): Flow<List<NoteAuthorEntity>>
    @Query("SELECT * FROM note_authors WHERE id = :id") suspend fun author(id: String): NoteAuthorEntity?
    @Upsert suspend fun saveAuthor(author: NoteAuthorEntity)
}
