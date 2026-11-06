package com.neoruaa.xhsdn.domain.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.NamingFormat
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteOutputAndroidTest {
    @Test fun customDatesAndSeparatePublicIdWorkOnAndroidIcu() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val note = ResolvedNote("", null, null, null, "uid", null,
                noteId = "note", publishedAt = 1704164645000, authorRedId = "1025390089")
            val settings = AppSettings(useCustomNamingFormat = true,
                customNamingTemplate = "{redId}_{userId}_{publishTime:MM-dd}_{downloadTimestamp:HH-mm-ss}")
            assertEquals("1025390089_uid_01-02_03-04-05_01.jpg", NoteOutput.fileName(note, settings, 0, "jpg", 1704251045000))
            assertFalse(NamingFormat.isValidDatePattern("uuuu-MM-dd"))
            assertEquals("note_01.jpg", NoteOutput.fileName(note,
                settings.copy(customNamingTemplate = "{publishTime:bad}"), 0, "jpg", 1))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun namingIsValidOnAndroidIcuAndCannotEscapeDestination() {
        val note = ResolvedNote("", "a/../../title", null, "Author", "user", null, noteId = "note")
        assertEquals("note_01.jpg", NoteOutput.fileName(note, AppSettings(), 0, "jpg", 1))
        val custom = NoteOutput.fileName(note, AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{title}_{username}"), 0, "png", 1)
        assertFalse(custom.contains('/'))
        assertTrue(custom.endsWith("_Author_01.png"))
    }
}
