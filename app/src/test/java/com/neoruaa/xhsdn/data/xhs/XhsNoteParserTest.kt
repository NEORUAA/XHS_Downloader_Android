package com.neoruaa.xhsdn.data.xhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhsNoteParserTest {
    @Test fun reportedWebLivePhotoIsAWatermarkedPreviewNotAnOriginalVideo() {
        val json = javaClass.getResourceAsStream("/xhs/live-photo-web-preview.json")!!
            .bufferedReader().use { it.readText() }
        val note = XhsNoteParser().parseNote(org.json.JSONObject(json), "")
        val video = note.livePhotos.single().video
        assertEquals(3, video.candidates.size)
        assertTrue(video.candidates.all { it.watermarked && !it.original })
        assertTrue(video.candidates.all { it.width == 1080 && it.height == 1440 })
        // Changing ranking cannot manufacture an original absent from this response.
        for (preference in com.neoruaa.xhsdn.data.settings.VideoPreference.entries) {
            assertEquals(video.candidates.map { it.url }, com.neoruaa.xhsdn.domain.download.NoteOutput.videoUrls(video, preference))
        }
    }

    @Test fun livePhotoUsesSuppliedOriginalKeyBeforeStreamVariants() {
        val note = org.json.JSONObject("""{"noteId":"n1","type":"normal","imageList":[
            {"urlDefault":"https://example.com/cover.jpg","consumer":{"originVideoKey":"original/live.mp4"},
             "stream":{"h264":[{"masterUrl":"https://example.com/wm.mp4","streamType":259,"width":4096,"height":2160},
                                    {"masterUrl":"https://example.com/clean.mp4","streamType":258}]}}
        ]}""")
        val live = XhsNoteParser().parseNote(note, "").livePhotos.single()
        assertEquals("https://sns-video-bd.xhscdn.com/original/live.mp4", live.video.sourceUrl)
        assertTrue(live.video.candidates.first().original)
        for (preference in com.neoruaa.xhsdn.data.settings.VideoPreference.entries) {
            val urls = com.neoruaa.xhsdn.domain.download.NoteOutput.videoUrls(live.video, preference)
            assertTrue(urls.indexOf("https://example.com/clean.mp4") < urls.indexOf("https://example.com/wm.mp4"))
        }
    }

    @Test fun livePhotoWithoutOriginalRetainsUnknownAndWatermarkedFallbacks() {
        val note = org.json.JSONObject("""{"noteId":"n1","type":"normal","imageList":[
            {"urlDefault":"https://example.com/cover.jpg","videoId":"not-an-original-key",
             "stream":{"h264":[{"masterUrl":"https://example.com/wm.mp4","streamType":309},
                                    {"masterUrl":"https://example.com/unknown.mp4"}]}}
        ]}""")
        val video = XhsNoteParser().parseNote(note, "").livePhotos.single().video
        assertEquals("https://example.com/unknown.mp4", video.sourceUrl)
        assertEquals(2, video.candidates.size)
        assertTrue(video.candidates.none { it.original })
        assertTrue(video.candidates.last().watermarked)
    }

    @Test fun nestedLivePhotoAndNullOriginalDoNotCreateBogusCdnUrls() {
        val note = org.json.JSONObject("""{"noteId":"n1","type":"normal","imageList":[
            {"urlDefault":"https://example.com/cover.jpg","livePhoto":{"consumer":{"originVideoKey":null},
             "media":{"stream":{"h264":[{"masterUrl":"https://example.com/live.mp4"}]}}}}
        ]}""")
        val video = XhsNoteParser().parseNote(note, "").livePhotos.single().video
        assertEquals(listOf("https://example.com/live.mp4"), video.candidates.map { it.url })
    }

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
        assertEquals("Title\nDescription", parsed.description)
        assertEquals("Alice", parsed.metadata?.userName)
        assertEquals(null, parsed.metadata?.userId)
        assertEquals("alice-id", parsed.metadata?.redId)
        assertEquals("alice-id", parsed.resolved?.authorRedId)
        assertTrue(parsed.containsVideo)
        assertEquals(1, parsed.livePhotos.size)
        assertEquals(3, parsed.mediaUrls.size)
        assertEquals("https://ci.xiaohongshu.com/path/token", parsed.mediaUrls.first())
        assertEquals("https://sns-video-bd.xhscdn.com/path/main.mp4", parsed.mediaUrls.last())
    }

    @Test fun parsesPublicProfileIdsWithoutConfusingMobileIdWithUid() {
        val parser = XhsNoteParser()
        val mobile = """<script>window.__INITIAL_STATE__={"profile":{"userInfo":{"id":"public-id","redId":"public-id"}},"user":{"userInfo":{"redId":"signed-in-user"}}};</script>"""
        assertEquals("public-id", parser.parseAuthorRedId(mobile, "uid"))
        for (wrapper in listOf("value", "_value", "_rawValue")) {
            val desktop = """<script>window.__INITIAL_STATE__={"user":{"userPageData":{"$wrapper":{"basicInfo":{"userId":"uid","redId":"public-id"}}}}};</script>"""
            assertEquals("public-id", parser.parseAuthorRedId(desktop, "uid"))
            assertEquals(null, parser.parseAuthorRedId(desktop, "other"))
        }
        val direct = """<script>window.__INITIAL_STATE__={"profile":{"userInfo":{}},"user":{"userPageData":{"basic_info":{"red_id":"public-id"}}}};</script>"""
        assertEquals("public-id", parser.parseAuthorRedId(direct, "uid"))
        val unavailable = """<script>window.__INITIAL_STATE__={"profile":{"userInfo":{"redId":null}},"user":{"userInfo":{"redId":"signed-in-user"}}};</script>"""
        assertEquals(null, parser.parseAuthorRedId(unavailable, "uid"))
        assertEquals(null, parser.parseAuthorRedId("<html>Login required</html>", "uid"))
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
