package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StructuredNoteTest {
    @Test fun selectsRequestedNoteInsteadOfRecommendation() {
        val html = """<script>window.__INITIAL_STATE__={"note":{"noteDetailMap":{"other":{"note":{"noteId":"other","title":"Wrong","imageList":[]}},"target":{"note":{"noteId":"target","title":"Right","desc":"Keep undefined and new Map() as text","user":{"userId":"stable-id","redId":"display-id"},"imageList":[]}}}}};</script>"""
        val note = XhsNoteParser().parse(html, "target", "https://www.rednote.com/explore/target").resolved!!
        assertEquals("Right", note.title)
        assertEquals("stable-id", note.authorId)
        assertEquals("display-id", note.authorRedId)
        assertEquals("Keep undefined and new Map() as text", note.body)
        assertNull(XhsNoteParser().parse(html, "missing").resolved)
    }

    @Test fun separatesPublicIdsAndPreservesOnlyTheAuthorsProfileContext() {
        val parser = XhsNoteParser()
        val note = parser.parseNote(JSONObject("""{"noteId":"note","user":{"user_id":"uid","red_id":"public-id","xsec_token":"a+b/c=","xsec_source":"pc_note"}}"""), "https://www.xiaohongshu.com/explore/note")
        assertEquals("uid", note.authorId)
        assertEquals("public-id", note.authorRedId)
        val profile = note.authorProfileUrl!!.toHttpUrl()
        assertEquals(listOf("user", "profile", "uid"), profile.pathSegments)
        assertEquals("a+b/c=", profile.queryParameter("xsec_token"))
        assertEquals("pc_note", profile.queryParameter("xsec_source"))
        for (user in listOf("""{"redId":"public-id"}""", """{"userId":null,"redId":null}""")) {
            val parsed = parser.parseNote(JSONObject("""{"noteId":"note","user":$user}"""), "")
            assertNull(parsed.authorId)
            assertNull(parsed.authorProfileUrl)
        }
        val empty = parser.parseNote(JSONObject("""{"noteId":"note","user":{"userId":null,"redId":null}}"""), "")
        assertNull(empty.authorRedId)
    }

    @Test fun rejectsUnrelatedProfileLinksAndKeepsOnlyProfileSecurityParameters() {
        val parser = XhsNoteParser()
        for (url in listOf("https://xiaohongshu.com.evil/user/profile/uid", "https://www.xiaohongshu.com/user/profile/other")) {
            val note = parser.parseNote(JSONObject("""{"noteId":"n","user":{"userId":"uid","profileUrl":"$url"}}"""), "")
            assertNull(note.authorProfileUrl)
        }
        val note = parser.parseNote(JSONObject("""{"noteId":"n","user":{"userId":"uid","profileUrl":"https://www.xiaohongshu.com/user/profile/uid?xsec_token=token&xsec_source=app_share&tracking=ignored"}}"""), "")
        assertEquals("https://www.xiaohongshu.com/user/profile/uid?xsec_token=token&xsec_source=app_share", note.authorProfileUrl)
    }

    @Test fun oldResolvedSnapshotsRemainReadableWithoutPublicIds() {
        val note = com.neoruaa.xhsdn.data.settings.DownloadJson.decodeFromString<com.neoruaa.xhsdn.core.model.ResolvedNote>(
            """{"canonicalUrl":"","title":null,"description":null,"authorName":null,"authorId":"uid","publishTime":null}"""
        )
        assertEquals("uid", note.authorId)
        assertNull(note.authorRedId)
        assertNull(note.authorProfileUrl)
    }

    @Test fun webViewDetailPreservesTheMatchingPageUrlAndItsSecurityContext() {
        val parser = XhsNoteParser()
        val detail = JSONObject("""{"note":{"noteId":"note","title":"Title","user":{"userId":"uid"}}}""")
        for (pageUrl in listOf(
            "https://www.xiaohongshu.com/explore/note?xsec_token=note-token&xsec_source=pc_share",
            "https://www.rednote.com/discovery/item/note?xsec_token=note-token&xsec_source=app_share",
            "https://www.xiaohongshu.com/user/profile/uid/note?xsec_token=note-token&xsec_source=pc_user",
        )) {
            detail.put("url", pageUrl)
            val note = parser.parseDetail(detail, "Share https://xhslink.cn/o/short", "note")
            assertEquals(pageUrl, note.canonicalUrl)
            assertEquals("note", note.noteId)
        }
        for (pageUrl in listOf(
            "https://www.xiaohongshu.com.evil/explore/note?xsec_token=bad",
            "https://www.xiaohongshu.com/explore/other?xsec_token=bad",
            "https://www.xiaohongshu.com/user/profile/uid/other?xsec_token=bad",
            "https://www.xiaohongshu.com/user/profile//note?xsec_token=bad",
            "https://www.xiaohongshu.com/user/profile/uid/note/extra?xsec_token=bad",
            "https://user:password@www.xiaohongshu.com/explore/note?xsec_token=bad",
            "https://www.xiaohongshu.com:8443/explore/note?xsec_token=bad",
            "javascript:alert(1)",
        )) {
            detail.put("url", pageUrl)
            assertEquals("https://xhslink.cn/o/short", parser.parseDetail(detail, "https://xhslink.cn/o/short", "note").canonicalUrl)
        }
        detail.put("url", "https://www.xiaohongshu.com/explore/note?xsec_token=note-token")
        assertThrows(IllegalArgumentException::class.java) { parser.parseDetail(detail, "https://xhslink.cn/o/short", "other") }
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
