package com.neoruaa.xhsdn.data.tasks

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [TaskEntity::class, TaskFileEntity::class, TaskMetadataEntity::class],
    version = 1,
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
