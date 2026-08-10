package com.neoruaa.xhsdn.data.tasks

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration

@Database(
    entities = [TaskEntity::class, TaskFileEntity::class, TaskMetadataEntity::class],
    version = 2,
    exportSchema = true
)
abstract class TaskDatabase : RoomDatabase() {
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
