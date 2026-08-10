package com.neoruaa.xhsdn.data.tasks

import android.content.Context
import androidx.room3.withWriteTransaction
import com.neoruaa.xhsdn.data.DownloadTask
import org.json.JSONArray

/**
 * Imports the JSON history written by the pre-Room TaskManager.
 *
 * The marker and every imported row are written in one Room transaction. A malformed
 * top-level JSON document therefore leaves the marker unset and is retried next launch;
 * malformed individual task entries retain the old loader's skip-invalid-entry behavior.
 */
class LegacyTaskHistoryImporter(
    private val context: Context,
    private val database: TaskDatabase,
    private val dao: TaskDao = database.taskDao()
) {
    suspend fun importIfNeeded() {
        database.withWriteTransaction {
            if (dao.getMetadata(TaskDatabaseConstants.LEGACY_IMPORT_KEY) != null) return@withWriteTransaction

            val preferences = context.applicationContext.getSharedPreferences(
                LEGACY_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val rawTasks = preferences.getString(KEY_TASKS, "[]") ?: "[]"
            val tasks = parseTasks(JSONArray(rawTasks))
            tasks.forEach { task ->
                dao.replaceTask(task.toEntity(), task.toFileEntities())
            }

            val maximumId = tasks.maxOfOrNull { it.id } ?: 0L
            val legacyNextId = runCatching { preferences.getLong(KEY_NEXT_ID, 1L) }
                .getOrDefault(1L)
                .coerceAtLeast(1L)
            val nextId = maxOf(legacyNextId, maximumId + 1L)
            dao.upsertMetadata(
                TaskMetadataEntity(TaskDatabaseConstants.NEXT_ID_KEY, nextId.toString())
            )
            dao.upsertMetadata(
                TaskMetadataEntity(
                    TaskDatabaseConstants.LEGACY_IMPORT_KEY,
                    System.currentTimeMillis().toString()
                )
            )
        }
    }

    private fun parseTasks(array: JSONArray): List<DownloadTask> = buildList {
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is org.json.JSONObject) continue
            runCatching { DownloadTask.fromJson(value) }
                .onSuccess(::add)
        }
    }

    companion object {
        const val LEGACY_PREFS_NAME = "task_history"
        const val KEY_TASKS = "tasks"
        const val KEY_NEXT_ID = "next_id"
    }
}
