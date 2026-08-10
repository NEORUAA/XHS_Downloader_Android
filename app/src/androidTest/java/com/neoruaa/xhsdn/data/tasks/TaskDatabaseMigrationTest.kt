package com.neoruaa.xhsdn.data.tasks

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "task-migration-test.db"
    private lateinit var databaseFile: File

    @Before
    fun createVersionOneDatabase() {
        context.deleteDatabase(databaseName)
        databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        AndroidSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.exec(
                """
                CREATE TABLE IF NOT EXISTS download_tasks (
                    id INTEGER NOT NULL,
                    note_url TEXT NOT NULL,
                    note_title TEXT,
                    note_type TEXT NOT NULL,
                    total_files INTEGER NOT NULL,
                    completed_files INTEGER NOT NULL,
                    failed_files INTEGER NOT NULL,
                    current_file_progress REAL NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    error_message TEXT,
                    note_content TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            connection.exec(
                """
                CREATE TABLE IF NOT EXISTS download_task_files (
                    task_id INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY(task_id, position),
                    FOREIGN KEY(task_id) REFERENCES download_tasks(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            connection.exec(
                "CREATE INDEX IF NOT EXISTS index_download_task_files_task_id " +
                    "ON download_task_files(task_id)"
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS task_metadata (`key` TEXT NOT NULL, " +
                    "value TEXT NOT NULL, PRIMARY KEY(`key`))"
            )
            connection.exec(
                """
                INSERT INTO download_tasks (
                    id, note_url, note_title, note_type, total_files, completed_files,
                    failed_files, current_file_progress, status, created_at, completed_at,
                    error_message, note_content
                ) VALUES (
                    1, 'https://example.com/note', NULL, 'IMAGE', 1, 1,
                    0, 0, 'COMPLETED', 1, 2, NULL, NULL
                )
                """.trimIndent()
            )
            connection.exec(
                "INSERT INTO download_task_files (task_id, path, position) " +
                    "VALUES (1, '/storage/emulated/0/Pictures/xhsdn/legacy.jpg', 0)"
            )
            connection.exec("PRAGMA user_version = 1")
        }
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationOneToTwoPreservesLegacyFileRecord() = runBlocking {
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, databaseName)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(TASK_DATABASE_MIGRATION_1_2)
            .build()
        try {
            val task = database.taskDao().getTask(1L)
            requireNotNull(task)
            val file = task.files.single()
            assertEquals("/storage/emulated/0/Pictures/xhsdn/legacy.jpg", file.path)
            assertNull(file.uri)
            assertNull(file.displayName)
            assertNull(file.mimeType)
            assertEquals(0L, file.sizeBytes)
            assertNull(file.legacyPath)
        } finally {
            database.close()
        }
    }

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }
}
