package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.data.settings.ImageFormat
import org.junit.Assert.*
import org.junit.Test

class NoteOutputTest {
    @Test fun stripsTemporaryCdnRoutingPrefixBeforeRequestingOriginalImage() {
        val image = ResolvedMedia.Image("https://sns-webpic-qc.xhscdn.com/202609222015/0123456789abcdef0123456789abcdef/notes_pre_post/token!nd_dft_wlteh_webp_3")
        assertEquals("https://sns-img-bd.xhscdn.com/notes_pre_post/token", NoteOutput.imageUrls(image, ImageFormat.AUTO).first())
        assertEquals("https://ci.xiaohongshu.com/notes_pre_post/token?imageView2/format/avif", NoteOutput.imageUrls(image, ImageFormat.AVIF).first())
    }

    @Test fun limitsNameBytesWithoutSplittingUnicodeCharacters() {
        val name = NoteOutput.safePart("😀".repeat(100) + "/file", "note")
        assertTrue(name.toByteArray().size <= 160)
        assertFalse(name.contains('/'))
        assertEquals(name, String(name.toByteArray()))
    }
}
