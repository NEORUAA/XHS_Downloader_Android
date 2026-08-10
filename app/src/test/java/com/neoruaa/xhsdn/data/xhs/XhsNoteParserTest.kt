package com.neoruaa.xhsdn.data.xhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhsNoteParserTest {
    @Test
    fun parsesImageVideoLivePhotoAndMetadata() {
        val html = """
            <html><script>
            window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"noteId":"n1","title":"Title","desc":"Description","user":{"nickname":"Alice","redId":"alice-id"},"imageList":[{"urlDefault":"https://sns-img-qc.xhscdn.com/path/token!format","stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/path/live.mp4"}]}}],"video":{"consumer":{"originVideoKey":"path/main.mp4"}}}}}};
            </script></html>
        """.trimIndent()

        val parseErrors = mutableListOf<String>()
        val parser = XhsNoteParser(
            urlTransformer = { url ->
                if (url.contains("sns-img-qc")) "https://ci.xiaohongshu.com/path/token" else url
            },
            logError = parseErrors::add
        )
        val parsed = parser.parse(html)

        assertEquals(emptyList<String>(), parseErrors)
        assertEquals("TitleDescription", parsed.description)
        assertEquals("Alice", parsed.metadata?.userName)
        assertEquals("alice-id", parsed.metadata?.userId)
        assertTrue(parsed.containsVideo)
        assertEquals(1, parsed.livePhotos.size)
        assertEquals(3, parsed.mediaUrls.size)
        assertEquals("https://ci.xiaohongshu.com/path/token", parsed.mediaUrls.first())
        assertEquals("https://sns-video-bd.xhscdn.com/path/main.mp4", parsed.mediaUrls.last())
    }

    @Test
    fun parsesFallbackMediaUrlsAndHandlesNullHtml() {
        val html = "<img src=\"https://cdn.example.com/a.jpg\"><video src=\"https://cdn.example.com/b.mp4\"></video>"
        val parsed = XhsNoteParser().parse(html)
        assertEquals(listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.mp4"), parsed.mediaUrls)
        assertTrue(parsed.containsVideo)
        assertEquals(emptyList<String>(), XhsNoteParser().parse(null).mediaUrls)
        assertEquals(null, XhsNoteParser().description(null))
    }
}
