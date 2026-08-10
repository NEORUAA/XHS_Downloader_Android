package com.neoruaa.xhsdn.data.tasks

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyTaskHistoryImporterTest {
    private lateinit var context: Context
    private lateinit var database: TaskDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        legacyPreferences().edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        legacyPreferences().edit().clear().commit()
    }

    @Test
    fun importsCompleteRowsSkipsBadRowsAndIsIdempotent() = runBlocking {
        val expected = DownloadTask(
            id = 41L,
            noteUrl = "https://xhslink.com/example",
            noteTitle = "Title",
            noteType = NoteType.IMAGE,
            totalFiles = 2,
            completedFiles = 2,
            status = TaskStatus.COMPLETED,
            createdAt = 1234L,
            completedAt = 2345L,
            filePaths = listOf("/first.jpg", "/second.jpg"),
            noteContent = "Description"
        )
        legacyPreferences().edit()
            .putString(
                LegacyTaskHistoryImporter.KEY_TASKS,
                JSONArray().put(expected.toJson()).put(JSONObject().put("broken", true)).toString()
            )
            .putLong(LegacyTaskHistoryImporter.KEY_NEXT_ID, 90L)
            .commit()

        val importer = LegacyTaskHistoryImporter(context, database)
        importer.importIfNeeded()
        importer.importIfNeeded()

        assertEquals(listOf(expected), RoomTaskRepository(database).observeTasks().first())
        assertEquals("90", database.taskDao().getMetadata(TaskDatabaseConstants.NEXT_ID_KEY))
    }

    @Test
    fun malformedTopLevelJsonRollsBackAndCanRetry() = runBlocking {
        legacyPreferences().edit()
            .putString(LegacyTaskHistoryImporter.KEY_TASKS, "not-json")
            .commit()

        val importer = LegacyTaskHistoryImporter(context, database)
        assertThrows(org.json.JSONException::class.java) {
            runBlocking { importer.importIfNeeded() }
        }
        assertEquals(null, database.taskDao().getMetadata(TaskDatabaseConstants.LEGACY_IMPORT_KEY))

        legacyPreferences().edit()
            .putString(LegacyTaskHistoryImporter.KEY_TASKS, "[]")
            .commit()
        importer.importIfNeeded()
        assertEquals(emptyList<DownloadTask>(), RoomTaskRepository(database).observeTasks().first())
    }

    private fun legacyPreferences() = context.getSharedPreferences(
        LegacyTaskHistoryImporter.LEGACY_PREFS_NAME,
        Context.MODE_PRIVATE
    )
}
