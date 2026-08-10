package com.neoruaa.xhsdn.data.tasks

import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskEntityMappingTest {
    @Test
    fun mappingPreservesTaskFieldsAndFileOrder() {
        val task = DownloadTask(
            id = 42L,
            noteUrl = "https://www.xiaohongshu.com/explore/42",
            noteTitle = "A note",
            noteType = NoteType.IMAGE,
            totalFiles = 2,
            completedFiles = 1,
            failedFiles = 0,
            currentFileProgress = .5f,
            status = TaskStatus.DOWNLOADING,
            createdAt = 100L,
            completedAt = null,
            errorMessage = null,
            filePaths = listOf("second.jpg", "first.jpg", "second.jpg"),
            noteContent = "description"
        )

        val row = TaskWithFiles(
            task = task.toEntity(),
            files = task.toFileEntities().reversed()
        )

        assertEquals(task, row.toModel())
    }

}
