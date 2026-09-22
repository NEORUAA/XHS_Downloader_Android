package com.neoruaa.xhsdn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTaskTest {
    private val task = DownloadTask(
        id = 1L,
        noteUrl = "https://xhslink.cn/o/example",
        noteTitle = null,
        noteType = NoteType.IMAGE,
        totalFiles = 3,
        status = TaskStatus.COMPLETED,
        createdAt = 0L
    )

    @Test
    fun explicitTitleTakesPrecedenceOverDescription() {
        assertEquals("Note title", task.copy(noteTitle = "Note title", noteContent = "Body").displayTitle)
    }

    @Test
    fun untitledNotesUseTheFirstNonEmptyDescriptionLine() {
        assertEquals("Shared content", task.copy(noteContent = "\n  Shared content  \n#tag").displayTitle)
    }

    @Test
    fun blankLegacyTitleFallsBackToDescription() {
        assertEquals("Description", task.copy(noteTitle = "  ", noteContent = "Description").displayTitle)
    }

    @Test
    fun removesTopicMarkersFromBothTitleAndDescription() {
        val topic = "#cosplay[话题]# #outfit[话题]#"
        assertEquals("#cosplay# #outfit#", task.copy(noteTitle = topic).displayTitle)
        assertEquals("#cosplay# #outfit#", task.copy(noteContent = topic).displayTitle)
    }

    @Test
    fun missingOrBlankMetadataFallsBackToLink() {
        assertEquals(task.noteUrl, task.displayTitle)
        assertEquals(task.noteUrl, task.copy(noteTitle = " ", noteContent = " \n\t ").displayTitle)
    }
}
