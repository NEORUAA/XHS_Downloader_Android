package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.domain.download.DownloadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("Title\nDescription", note.description)
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

    @Test fun `profile lookup reuses only matching note context and prefers author context`() = runTest {
        val resolver = XhsAuthorProfileResolver()
        val note = profileNote().copy(canonicalUrl = "https://www.xiaohongshu.com/discovery/item/note?xsec_token=note%2Btoken&xsec_source=app_share&tracking=ignored")
        val requests = mutableListOf<String>()
        val fetch: suspend (String) -> XhsPage? = { url ->
            requests += url
            profilePage(url)
        }
        assertEquals("public-id", resolver.resolveRedId(note, fetch))
        val request = requests.single().toHttpUrl()
        assertEquals(listOf("user", "profile", "uid"), request.pathSegments)
        assertEquals("note+token", request.queryParameter("xsec_token"))
        assertEquals("app_share", request.queryParameter("xsec_source"))
        assertNull(request.queryParameter("tracking"))

        val authorContext = "https://www.xiaohongshu.com/user/profile/uid?xsec_token=author-token&xsec_source=pc_note"
        XhsAuthorProfileResolver().resolveRedId(note.copy(authorProfileUrl = authorContext), fetch)
        assertEquals(authorContext, requests.last())
        for (canonical in listOf(
            "https://www.xiaohongshu.com/explore/other?xsec_token=unrelated",
            "https://www.xiaohongshu.com.evil/explore/note?xsec_token=untrusted",
        )) {
            XhsAuthorProfileResolver().resolveRedId(note.copy(canonicalUrl = canonical), fetch)
            assertNull(requests.last().toHttpUrl().queryParameter("xsec_token"))
        }
    }

    @Test fun `profile lookup validates final identity and never reads account ids`() = runTest {
        for (url in listOf(
            "https://www.xiaohongshu.com/login",
            "https://www.xiaohongshu.com/website-login/captcha",
            "https://www.xiaohongshu.com/user/profile/other",
            "https://www.xiaohongshu.com.evil/user/profile/uid",
        )) {
            assertNull(XhsAuthorProfileResolver().resolveRedId(profileNote()) { profilePage(url) })
        }
        val account = """<script>window.__INITIAL_STATE__={"user":{"userInfo":{"redId":"signed-in-user"}}};</script>"""
        assertNull(XhsAuthorProfileResolver().resolveRedId(profileNote()) { XhsPage(account, it) })
        assertEquals("already-known", XhsAuthorProfileResolver().resolveRedId(profileNote().copy(authorRedId = "already-known")) {
            error("A known public ID must not trigger a request")
        })
    }

    @Test fun `profile cache expires and stays bounded while failures remain retryable`() = runTest {
        var clock = 0L
        var calls = 0
        val resolver = XhsAuthorProfileResolver(now = { clock })
        val fetch: suspend (String) -> XhsPage? = { url -> calls++; profilePage(url) }
        assertEquals("public-id", resolver.resolveRedId(profileNote(), fetch))
        assertEquals("public-id", resolver.resolveRedId(profileNote(), fetch))
        assertEquals(1, calls)
        clock = 15 * 60_000L
        resolver.resolveRedId(profileNote(), fetch)
        assertEquals(2, calls)
        repeat(128) { index -> resolver.resolveRedId(profileNote().copy(authorId = "uid-$index"), fetch) }
        resolver.resolveRedId(profileNote(), fetch)
        assertEquals(131, calls)

        val failed = XhsAuthorProfileResolver()
        assertNull(failed.resolveRedId(profileNote()) { throw java.io.IOException("offline") })
        assertEquals("public-id", failed.resolveRedId(profileNote(), fetch))
    }

    @Test fun `profile timeouts return unavailable but external cancellation propagates`() = runTest {
        val resolver = XhsAuthorProfileResolver(timeoutMillis = 50)
        assertNull(resolver.resolveRedId(profileNote()) { delay(100); profilePage(it) })
        var cancelled = false
        try {
            resolver.resolveRedId(profileNote()) { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun profileNote() = ResolvedNote("https://www.xiaohongshu.com/explore/note", null, null, "Author", "uid", null, noteId = "note")

    private fun profilePage(url: String) = XhsPage(
        """<script>window.__INITIAL_STATE__={"profile":{"userInfo":{"id":"public-id","redId":"public-id"}}};</script>""",
        url,
    )
}
