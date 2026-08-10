package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.domain.download.DownloadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XhsContentRepositoryTest {
    @Test
    fun `resolve returns canonical immutable note`() = runTest {
        val html = """
            <script>window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"noteId":"n1","title":"Title","desc":"Description","user":{"nickname":"Alice","redId":"alice-id"},"imageList":[{"urlDefault":"https://sns-img-qc.xhscdn.com/path/image.jpg"}],"video":{"consumer":{"originVideoKey":"path/main.mp4"}}}}}};</script>
        """.trimIndent()
        val repository = DefaultXhsContentRepository(
            fetchHtml = { html },
            resolveShortUrl = { "https://www.xiaohongshu.com/explore/n1" },
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val note = repository.resolve("分享 https://xhslink.cn/demo")

        assertEquals("https://www.xiaohongshu.com/explore/n1", note.canonicalUrl)
        assertEquals("Title", note.title)
        assertEquals("TitleDescription", note.description)
        assertEquals("Alice", note.authorName)
        assertEquals(1, note.images.size)
        assertEquals(1, note.videos.size)
    }

    @Test
    fun `resolve exposes typed invalid input failure`() {
        val repository = DefaultXhsContentRepository(fetchHtml = { null })

        val error = assertThrows(XhsResolveException::class.java) {
            kotlinx.coroutines.runBlocking { repository.resolve("not a link") }
        }

        assertEquals(DownloadFailure.InvalidInput, error.failure)
    }

    @Test
    fun `resolve propagates coroutine cancellation`() = runTest {
        val repository = DefaultXhsContentRepository(
            fetchHtml = { throw CancellationException("cancelled") },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        var cancelled = false

        try {
            repository.resolve("https://www.xiaohongshu.com/explore/abc")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }
}
