package com.neoruaa.xhsdn.data.tasks

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration

@Database(
    entities = [TaskEntity::class, TaskFileEntity::class, TaskMetadataEntity::class, DownloadSessionEntity::class, DownloadResourceEntity::class, NoteAuthorEntity::class],
    version = 3,
    exportSchema = true
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun downloadSessionDao(): DownloadSessionDao
    abstract fun taskDao(): TaskDao
}

internal object TaskDatabaseConstants {
    const val DATABASE_NAME = "xhs_tasks.db"
    const val LEGACY_IMPORT_KEY = "legacy_task_history_import_v1"
    const val NEXT_ID_KEY = "task_next_id"
}

internal val TASK_DATABASE_MIGRATION_1_2 = Migration(1, 2) { connection ->
    listOf(
        "ALTER TABLE download_task_files ADD COLUMN uri TEXT",
        "ALTER TABLE download_task_files ADD COLUMN display_name TEXT",
        "ALTER TABLE download_task_files ADD COLUMN mime_type TEXT",
        "ALTER TABLE download_task_files ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE download_task_files ADD COLUMN legacy_path TEXT"
    ).forEach { sql ->
        connection.prepare(sql).use { statement -> statement.step() }
    }
}

internal val TASK_DATABASE_MIGRATION_2_3 = Migration(2, 3) { connection ->
    listOf(
        "CREATE TABLE IF NOT EXISTS download_sessions (task_id INTEGER NOT NULL PRIMARY KEY, settings_json TEXT NOT NULL, resolved_json TEXT, selected_json TEXT, require_selection INTEGER NOT NULL, info_only INTEGER NOT NULL, note_id TEXT NOT NULL, author_name TEXT NOT NULL, FOREIGN KEY(task_id) REFERENCES download_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS index_download_sessions_note_id ON download_sessions(note_id)",
        "CREATE TABLE IF NOT EXISTS download_resources (task_id INTEGER NOT NULL, media_id TEXT NOT NULL, record_key TEXT NOT NULL, state TEXT NOT NULL, refs_json TEXT NOT NULL, bytes_downloaded INTEGER NOT NULL, total_bytes INTEGER NOT NULL, warning TEXT, PRIMARY KEY(task_id, media_id), FOREIGN KEY(task_id) REFERENCES download_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS index_download_resources_task_id ON download_resources(task_id)",
        "CREATE INDEX IF NOT EXISTS index_download_resources_record_key ON download_resources(record_key)",
        "CREATE TABLE IF NOT EXISTS note_authors (id TEXT NOT NULL PRIMARY KEY, nickname TEXT NOT NULL, remark TEXT NOT NULL)",
    ).forEach { sql -> connection.prepare(sql).use { it.step() } }
}
