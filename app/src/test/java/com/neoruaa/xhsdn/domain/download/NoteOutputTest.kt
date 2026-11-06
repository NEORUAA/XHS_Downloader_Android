package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.MediaCandidate
import com.neoruaa.xhsdn.data.settings.VideoPreference
import com.neoruaa.xhsdn.data.settings.ImageFormat
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.NamingFormat
import com.neoruaa.xhsdn.core.model.ResolvedNote
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class NoteOutputTest {
    @Test fun publicAccountNumberAndInternalUidAreIndependentNamingFields() {
        val note = ResolvedNote("", null, null, "Author", "internal-uid", null,
            noteId = "note", authorRedId = "public-number")
        val settings = AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{redId}_{userId}")
        assertEquals("public-number_internal-uid_01.jpg", NoteOutput.fileName(note, settings, 0, "jpg", 1))
        assertEquals("_internal-uid_01.jpg", NoteOutput.fileName(note.copy(authorRedId = null), settings, 0, "jpg", 1))
        assertTrue(NamingFormat.containsToken(settings.customNamingTemplate, NamingFormat.TOKEN_RED_ID))
        assertFalse(NamingFormat.containsToken("{userId}", NamingFormat.TOKEN_RED_ID))
    }

    @Test fun publicIdNamingRefreshesDownloadIdentityWhenTheLookupRecoversOrTheIdChanges() {
        val settings = AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{redId}_{title}")
        val missing = NoteOutput.recordKey("note", "image", settings, emptyList())
        val resolved = NoteOutput.recordKey("note", "image", settings, emptyList(), "public-id")
        assertNotEquals(missing, resolved)
        assertNotEquals(resolved, NoteOutput.recordKey("note", "image", settings, emptyList(), "changed-id"))
        assertEquals(missing, NoteOutput.recordKey("note", "image", settings, emptyList(), ""))
        for (unused in listOf(settings.copy(useCustomNamingFormat = false),
            settings.copy(customNamingTemplate = "{userId}_{title}"))) {
            assertEquals(NoteOutput.recordKey("note", "image", unused, emptyList()),
                NoteOutput.recordKey("note", "image", unused, emptyList(), "public-id"))
        }
    }

    @Test fun customTimeFormatsUseEachTimestampAndTheDeviceTimeZone() = withTimeZone("GMT+08:00") {
        val note = ResolvedNote("", null, null, null, null, null, noteId = "note", publishedAt = 1704164645000)
        val settings = AppSettings(useCustomNamingFormat = true,
            customNamingTemplate = "{publishTime:MM-dd_HH-mm}_{downloadTimestamp:yyyy-MM-dd_HH-mm}")
        assertEquals("01-02_11-04_2024-01-03_11-04_01.jpg", NoteOutput.fileName(note, settings, 0, "jpg", 1704251045000))
    }

    @Test fun unformattedTimeTokensKeepTheirExistingOutput() = withTimeZone("UTC") {
        val note = ResolvedNote("", null, null, null, null, null, noteId = "note", publishedAt = 1704164645000)
        val settings = AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{publishTime}_{downloadTimestamp}")
        assertEquals("2024-01-02_03-04-05_1704251045000_01.jpg", NoteOutput.fileName(note, settings, 0, "jpg", 1704251045000))
        assertEquals("1704164645000_1704251045000_01.jpg",
            NoteOutput.fileName(note.copy(publishedAt = null, publishTime = "1704164645000"), settings, 0, "jpg", 1704251045000))
    }

    @Test fun formattedLegacyTimestampAndQuotedLiteralsRemainSafeForFilenames() = withTimeZone("UTC") {
        val note = ResolvedNote("", null, null, null, null, "1704164645000", noteId = "note")
        val settings = AppSettings(useCustomNamingFormat = true, customNamingTemplate = "{publishTime:MM/dd' at 'HH:mm}")
        assertEquals("01_02 at 03_04_01.jpg", NoteOutput.fileName(note, settings, 0, "jpg", 1))
        assertEquals("note_01.jpg", NoteOutput.fileName(note.copy(publishTime = null), settings, 0, "jpg", 1))
    }

    @Test fun invalidTemplatesAndNonPortablePatternsDoNotInterruptDownloads() {
        val note = ResolvedNote("", null, null, null, null, null, noteId = "note")
        for (template in listOf("{publishTime:}", "{publishTime:QQ}", "{publishTime:yyyy-'unterminated}",
            "{publishTime:uuuu}", "{unknown}", "{redId:MM}", "{publishTime:MM-dd", "{title}}")) {
            assertNotNull(template, NamingFormat.validationError(template))
            assertEquals(template, "note_01.jpg", NoteOutput.fileName(note,
                AppSettings(useCustomNamingFormat = true, customNamingTemplate = template), 0, "jpg", 1))
        }
        assertNull(NamingFormat.validationError("{redId}_{publishTime:MM-dd}_{downloadTimestamp:HH-mm-ss}"))
        assertFalse(NamingFormat.isValidDatePattern("y".repeat(81)))
        assertFalse(NamingFormat.isValidDatePattern("yyyy\nMM"))
        assertFalse(NamingFormat.isValidDatePattern("'literal'"))
    }

    private fun withTimeZone(zone: String, action: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            action()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun knownWatermarkedStreamsNeverWinOnResolutionBitrateSizeOrCompatibility() {
        val video = ResolvedMedia.Video("watermarked", candidates = listOf(
            MediaCandidate("watermarked", width = 4000, height = 3000, bitrate = 99999, size = 99999, codec = "h264", watermarked = true),
            MediaCandidate("unknown", width = 720, height = 1280, codec = "h265"),
            MediaCandidate("original", original = true),
        ))
        for (preference in VideoPreference.entries) {
            assertEquals("watermarked", NoteOutput.videoUrls(video, preference).last())
        }
        val old = com.neoruaa.xhsdn.data.settings.DownloadJson.decodeFromString<MediaCandidate>("""{"url":"old"}""")
        assertFalse(old.watermarked)
    }

    @Test fun livePhotoFormatChangesDownloadIdentity() {
        val original = AppSettings()
        val samsung = original.copy(downloadOptions = original.downloadOptions.copy(livePhotoFormat = com.neoruaa.xhsdn.data.settings.LivePhotoFormat.SAMSUNG))
        assertNotEquals(NoteOutput.recordKey("note", "live", original, emptyList()), NoteOutput.recordKey("note", "live", samsung, emptyList()))
    }

    @Test fun compatibilityPrefersHighestResolutionAvcWithoutDiscardingFallbacks() {
        val video = ResolvedMedia.Video("original", candidates = listOf(
            MediaCandidate("original", original = true),
            MediaCandidate("hevc", width = 3840, height = 2160, codec = "h265"),
            MediaCandidate("avc-low", width = 1280, height = 720, codec = "h264"),
            MediaCandidate("avc-high", width = 1920, height = 1080, codec = "h264"),
        ))
        assertEquals("original", NoteOutput.videoUrls(video, VideoPreference.RESOLUTION).first())
        assertEquals(listOf("avc-high", "avc-low", "hevc", "original"), NoteOutput.videoUrls(video, VideoPreference.COMPATIBILITY))
    }

    @Test fun stripsTemporaryCdnRoutingPrefixBeforeRequestingOriginalImage() {
        val image = ResolvedMedia.Image("https://sns-webpic-qc.xhscdn.com/202609222015/0123456789abcdef0123456789abcdef/notes_pre_post/token!nd_dft_wlteh_webp_3")
        assertEquals("https://sns-img-bd.xhscdn.com/notes_pre_post/token", NoteOutput.imageUrls(image, ImageFormat.AUTO).first())
        assertEquals("https://ci.xiaohongshu.com/notes_pre_post/token?imageView2/format/avif", NoteOutput.imageUrls(image, ImageFormat.AVIF).first())
    }

    @Test fun skipToggleAndNetworkSettingsDoNotChangeResourceIdentity() {
        val original = AppSettings()
        val changed = original.copy(selectiveDownload = true, downloadOptions = original.downloadOptions.copy(skipExisting = true, timeoutSeconds = 90, proxy = "http://localhost:8080"))
        assertEquals(NoteOutput.recordKey("note", "image", original, emptyList()), NoteOutput.recordKey("note", "image", changed, emptyList()))
        assertNotEquals(NoteOutput.recordKey("note", "image", original, emptyList()), NoteOutput.recordKey("note", "image", changed.copy(customStorageTreeUri = "content://other"), emptyList()))
    }

    @Test fun selectionOffersCoversWithoutChangingAutomaticDownloadDefaults() {
        val cover = ResolvedMedia.Image("cover", cover = true)
        val video = ResolvedMedia.Video("video")
        val note = com.neoruaa.xhsdn.core.model.ResolvedNote("", null, null, null, null, null, items = listOf(cover, video))
        val options = com.neoruaa.xhsdn.data.settings.DownloadOptions(videoCoverDownload = false)
        assertEquals(listOf(video), NoteOutput.eligible(note, options))
        assertEquals(listOf(cover, video), NoteOutput.eligible(note, options, forSelection = true))
    }

    @Test fun limitsNameBytesWithoutSplittingUnicodeCharacters() {
        val name = NoteOutput.safePart("😀".repeat(100) + "/file", "note")
        assertTrue(name.toByteArray().size <= 160)
        assertFalse(name.contains('/'))
        assertEquals(name, String(name.toByteArray()))
    }
}
