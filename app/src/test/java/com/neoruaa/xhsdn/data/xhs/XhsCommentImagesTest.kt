package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.data.settings.DownloadJson
import com.neoruaa.xhsdn.data.settings.DownloadOptions
import com.neoruaa.xhsdn.domain.download.NoteOutput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XhsCommentImagesTest {
    private val detail = """{
      "note":{"noteId":"n","title":"Note","imageList":[{"urlDefault":"https://cdn.example/body"}]},
      "comments":{"firstRequestFinish":true,"hasMore":true,"list":[
        {"id":"c1","noteId":"n","pictures":[{"urlPre":"https://cdn.example/preview","width":1440,"height":1440,
          "infoList":[{"imageScene":"WB_PRV","url":"https://cdn.example/preview"},{"imageScene":"WB_DFT","url":"https://cdn.example/original"}]}],
          "subComments":[{"id":"reply","pictures":[{"urlDefault":"https://cdn.example/reply"}]}]},
        {"id":"c1","pictures":[{"urlDefault":"https://cdn.example/duplicate"}]},
        {"id":"foreign","noteId":"other","pictures":[{"urlDefault":"https://cdn.example/foreign"}]},
        {"id":"invalid","invalid":true,"pictures":[{"urlDefault":"https://cdn.example/invalid"}]},
        {"id":"avatar","userInfo":{"image":"https://cdn.example/avatar"}},
        {"id":"bad","pictures":[{"urlDefault":"javascript:alert(1)"}]}
      ]}
    }"""

    @Test fun desktopStateIncludesOnlyCurrentNotePicturesAndReplies() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteDetailMap\":{\"n\":$detail}}};</script>"
        val note = XhsNoteParser().parse(html, "n").resolved!!
        val images = note.orderedMedia.filterIsInstance<ResolvedMedia.Image>()
        assertEquals(listOf(null, "c1", "reply"), images.map { it.commentId })
        assertEquals("https://cdn.example/original", images[1].originalUrl)
        assertEquals(1440, images[1].width)
        assertEquals("n:comment:c1:1", images[1].id)
        assertTrue(note.commentsLoaded)
        assertTrue(note.commentsHasMore)
        assertEquals(1, NoteOutput.eligible(note, DownloadOptions()).size)
        assertEquals(3, NoteOutput.eligible(note, DownloadOptions(commentImageDownload = true)).size)
        assertEquals(2, NoteOutput.eligible(note, DownloadOptions(imageDownload = false, commentImageDownload = true)).size)
        assertEquals(2, NoteOutput.eligible(note, DownloadOptions(imageDownload = false, commentImageDownload = true), true).size)
        val restored = DownloadJson.decodeFromString<com.neoruaa.xhsdn.core.model.ResolvedNote>(DownloadJson.encodeToString(note))
        assertEquals(note, restored)
    }

    @Test fun webViewEnvelopeMatchesHtmlAndRetainsReplyPagination() {
        val data = JSONObject(detail)
        data.getJSONObject("comments").put("hasMore", false).getJSONArray("list").getJSONObject(0).put("subCommentHasMore", true)
        val note = XhsNoteParser().parseDetail(data, "", "n")
        assertEquals(3, note.mediaCount)
        assertTrue(note.commentsHasMore)
        assertNotNull(NoteOutput.commentWarning(note, DownloadOptions(commentImageDownload = true)))
        assertNull(NoteOutput.commentWarning(note, DownloadOptions()))
    }

    @Test fun mobileStateRetainsItsSiblingCommentData() {
        val data = JSONObject(detail)
        val comments = data.getJSONObject("comments")
        comments.put("comments", comments.remove("list"))
        val mobile = JSONObject().put("noteData", data.getJSONObject("note")).put("commentData", comments)
        val root = JSONObject().put("noteData", JSONObject().put("data", mobile))
        val note = XhsNoteParser().parse("<script>window.__INITIAL_STATE__=$root;</script>", "n").resolved!!
        assertTrue(note.commentsLoaded)
        assertEquals(3, note.mediaCount)
    }

    @Test fun absentCommentsAndEmptyLoadedCommentsHaveDifferentNotices() {
        val parser = XhsNoteParser()
        val data = JSONObject(detail)
        data.remove("comments")
        val absent = parser.parseDetail(data, "", "n")
        assertFalse(absent.commentsLoaded)
        assertNotNull(NoteOutput.commentWarning(absent, DownloadOptions(commentImageDownload = true)))
        data.put("comments", JSONObject("""{"list":[],"hasMore":false}"""))
        val empty = parser.parseDetail(data, "", "n")
        assertTrue(empty.commentsLoaded)
        assertNull(NoteOutput.commentWarning(empty, DownloadOptions(commentImageDownload = true)))
    }
}
