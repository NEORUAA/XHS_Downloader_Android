package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StructuredNoteTest {
    @Test fun selectsRequestedNoteInsteadOfRecommendation() {
        val html = """<script>window.__INITIAL_STATE__={"note":{"noteDetailMap":{"other":{"note":{"noteId":"other","title":"Wrong","imageList":[]}},"target":{"note":{"noteId":"target","title":"Right","desc":"Keep undefined and new Map() as text","user":{"userId":"stable-id","redId":"display-id"},"imageList":[]}}}}};</script>"""
        val note = XhsNoteParser().parse(html, "target", "https://www.rednote.com/explore/target").resolved!!
        assertEquals("Right", note.title)
        assertEquals("stable-id", note.authorId)
        assertEquals("Keep undefined and new Map() as text", note.body)
        assertNull(XhsNoteParser().parse(html, "missing").resolved)
    }

    @Test fun preservesLivePairAndAllCodecCandidatesInOrder() {
        val note = XhsNoteParser().parseNote(JSONObject("""{"noteId":"n","type":"normal","imageList":[
            {"urlDefault":"https://cdn.example/one.jpg","stream":{"h264":[{"masterUrl":"https://cdn.example/a.mp4","backupUrls":["https://cdn.example/b.mp4"]}],"h265":[{"masterUrl":"https://cdn.example/c.mp4"}]}},
            {"urlDefault":"https://cdn.example/two.jpg"}] }"""), "https://www.xiaohongshu.com/explore/n", "n")
        assertEquals(2, note.orderedMedia.size)
        val live = note.orderedMedia[0] as ResolvedMedia.LivePhoto
        assertEquals(3, live.video.candidates.size)
        assertTrue(note.orderedMedia[1] is ResolvedMedia.Image)
        assertNotEquals(live.id, live.video.id)
    }

    @Test fun distinguishesVideoCoverFromGalleryImages() {
        val video = XhsNoteParser().parseNote(JSONObject("""{"noteId":"n","type":"video","imageList":[{"urlDefault":"https://cdn.example/cover.jpg"}],"video":{"media":{"stream":{"h264":[{"masterUrl":"https://cdn.example/video.mp4","width":1920,"height":1080,"videoBitrate":5000}]}}}}"""), "", "n")
        assertTrue(video.images.single().cover)
        assertEquals(1, video.videos.size)
    }

    @Test fun videoNotesKeepLivePhotoPairsAlongsideTheCoverAndMainVideo() {
        val note = XhsNoteParser().parseNote(JSONObject("""{"noteId":"mixed","type":"video","imageList":[
            {"urlDefault":"https://cdn.example/cover.jpg"},
            {"urlDefault":"https://cdn.example/live.jpg","stream":{"h264":[{"masterUrl":"https://cdn.example/motion.mp4"}]}}
        ],"video":{"media":{"stream":{"h264":[{"masterUrl":"https://cdn.example/main.mp4"}]}}}}"""), "", "mixed")
        assertEquals(3, note.orderedMedia.size)
        assertTrue((note.orderedMedia[0] as ResolvedMedia.Image).cover)
        val live = note.orderedMedia[1] as ResolvedMedia.LivePhoto
        assertFalse(live.image.cover)
        assertEquals("https://cdn.example/motion.mp4", live.video.sourceUrl)
        assertEquals("https://cdn.example/main.mp4", (note.orderedMedia[2] as ResolvedMedia.Video).sourceUrl)
        val eligible = com.neoruaa.xhsdn.domain.download.NoteOutput.eligible(note, com.neoruaa.xhsdn.data.settings.DownloadOptions(videoCoverDownload = false))
        assertEquals(listOf(live, note.orderedMedia[2]), eligible)
    }

    @Test fun acceptsBareRednoteLinksAndRejectsLookalikeHosts() {
        assertEquals(listOf("https://www.rednote.com/explore/abc"), XhsUrlParser.extractLinks("分享：www.rednote.com/explore/abc。"))
        assertTrue(XhsUrlParser.extractLinks("https://evil.xiaohongshu.com/explore/a https://xiaohongshu.com.evil/explore/b").isEmpty())
        assertNull(XhsUrlParser.extractPostId("https://xhslink.cn/o/short"))
    }
}
