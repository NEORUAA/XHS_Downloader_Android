package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.MediaCandidate
import com.neoruaa.xhsdn.data.settings.VideoPreference
import com.neoruaa.xhsdn.data.settings.ImageFormat
import com.neoruaa.xhsdn.data.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class NoteOutputTest {
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
