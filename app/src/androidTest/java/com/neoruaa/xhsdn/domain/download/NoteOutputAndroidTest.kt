package com.neoruaa.xhsdn.domain.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteOutputAndroidTest {
    @Test fun namingIsValidOnAndroidIcuAndCannotEscapeDestination() {
        val note = ResolvedNote("", "a/../../title", null, "Author", "user", null, noteId = "note")
        assertEquals("note_01.jpg", NoteOutput.fileName(note, AppSettings(), 0, "jpg", 1))
        val custom = NoteOutput.fileName(note, AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{title}_{username}"), 0, "png", 1)
        assertFalse(custom.contains('/'))
        assertTrue(custom.endsWith("_Author_01.png"))
    }
}
