package com.neoruaa.xhsdn.feature.history

import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryViewModelTest {
    private val tasks = listOf(
        task(
            id = 3,
            url = "92 Example https://xhslink.cn/demo",
            content = "夏日海边照片",
            status = TaskStatus.COMPLETED
        ),
        task(
            id = 2,
            url = "https://www.xiaohongshu.com/explore/ABC123",
            content = "Night Walk",
            status = TaskStatus.FAILED
        ),
        task(
            id = 1,
            url = "https://xhslink.com/waiting",
            content = null,
            status = TaskStatus.WAITING_FOR_USER
        )
    )

    @Test
    fun `blank query keeps status-filtered tasks`() {
        assertEquals(
            listOf(2L),
            filterHistoryTasks(tasks, HistoryFilter.Failed, "  ").map { it.id }
        )
    }

    @Test
    fun `query matches complete share text and url ignoring case`() {
        assertEquals(
            listOf(3L),
            filterHistoryTasks(tasks, HistoryFilter.All, "XHSLINK.CN").map { it.id }
        )
        assertEquals(
            listOf(2L),
            filterHistoryTasks(tasks, HistoryFilter.All, "abc123").map { it.id }
        )
    }

    @Test
    fun `query matches description and handles null descriptions`() {
        assertEquals(
            listOf(3L),
            filterHistoryTasks(tasks, HistoryFilter.All, "海边").map { it.id }
        )
        assertEquals(
            emptyList<Long>(),
            filterHistoryTasks(tasks, HistoryFilter.All, "missing").map { it.id }
        )
    }

    @Test
    fun `query and status filter are intersected`() {
        assertEquals(
            listOf(2L),
            filterHistoryTasks(tasks, HistoryFilter.Failed, "night").map { it.id }
        )
        assertEquals(
            emptyList<Long>(),
            filterHistoryTasks(tasks, HistoryFilter.WaitingForUser, "night").map { it.id }
        )
    }

    private fun task(
        id: Long,
        url: String,
        content: String?,
        status: TaskStatus
    ) = DownloadTask(
        id = id,
        noteUrl = url,
        noteTitle = null,
        noteType = NoteType.UNKNOWN,
        totalFiles = 0,
        status = status,
        createdAt = id,
        noteContent = content
    )
}
