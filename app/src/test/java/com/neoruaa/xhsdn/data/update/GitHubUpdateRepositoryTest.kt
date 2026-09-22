package com.neoruaa.xhsdn.data.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateRepositoryTest {
    private fun release(tag: String) = JSONObject().put("tag_name", tag).put("draft", false)
        .put("prerelease", false).put("published_at", "2026-09-22T00:00:00Z").put("body", "Release notes")

    @Test fun comparesNumericSegmentsAndIgnoresBuildMetadata() {
        fun compare(left: String, right: String) = ReleaseVersion.parse(left)!!.compareTo(ReleaseVersion.parse(right)!!)
        assertTrue(compare("v1.10.0", "1.9.9") > 0)
        assertTrue(compare("1.3.5", "1.4.0") < 0)
        assertEquals(0, compare("V1.4", "1.4.0+build.123"))
        assertTrue(compare("1.4.0.1", "1.4") > 0)
        assertTrue(compare("1.4.0", "1.4.0-rc.10") > 0)
        assertTrue(compare("1.4.0-rc.10", "1.4.0-rc.2") > 0)
        assertTrue(compare("1.4.0-beta.1", "1.4.0-beta") > 0)
        assertTrue(compare("1.4.0-beta.a", "1.4.0-beta.9") > 0)
        listOf("latest", "v1..4", "1.4.0-", "release/1.4.0", "1.4.0+", "").forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }

    @Test fun offersOnlyNewerPublishedReleasesAndConstructsTheRepositoryUrl() = runBlocking {
        val json = release("v1.5.0").put("html_url", "https://example.com/unrelated")
        withResponse(200, json.toString()) { repository ->
            val result = repository.check() as UpdateCheckResult.Available
            assertEquals("v1.5.0", result.release.tag)
            assertEquals("Release notes", result.release.notes)
            assertEquals("https://github.com/NEORUAA/XHS_Downloader_Android/releases/tag/v1.5.0", result.release.url)
        }
        for (tag in listOf("1.3.5", "v1.4.0", "1.4+build.1")) {
            withResponse(200, release(tag).toString()) { assertEquals(UpdateCheckResult.UpToDate(tag), it.check()) }
        }
        for (field in listOf("draft", "prerelease")) {
            withResponse(200, release("2.0.0").put(field, true).toString()) { assertEquals(UpdateCheckResult.NoRelease, it.check()) }
        }
    }

    @Test fun distinguishesMissingReleasesRateLimitsAndServerFailures() = runBlocking {
        withResponse(404, "{}") { assertEquals(UpdateCheckResult.NoRelease, it.check()) }
        withResponse(429, "{}") { assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.RATE_LIMITED), it.check()) }
        withResponse(403, "{}", mapOf("X-RateLimit-Remaining" to "0")) {
            assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.RATE_LIMITED), it.check())
        }
        withResponse(403, "{}", mapOf("Retry-After" to "60")) {
            assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.RATE_LIMITED), it.check())
        }
        for (code in listOf(403, 500, 503)) withResponse(code, "{}") {
            assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.HTTP, code), it.check())
        }
    }

    @Test fun rejectsMalformedReleaseDataAndUnknownVersions() = runBlocking {
        for (body in listOf("not JSON", "{}", release("2.0").put("published_at", JSONObject.NULL).toString())) {
            withResponse(200, body) { assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_RESPONSE), it.check()) }
        }
        withResponse(200, release("latest").toString()) {
            assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_VERSION), it.check())
        }
        assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_VERSION),
            GitHubUpdateRepository.evaluateRelease(release("2.0.0"), "unknown"))
    }

    @Test fun reportsAnUnreachableServerAsNetworkFailure() = runBlocking {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val repository = GitHubUpdateRepository("1.4.0", OkHttpClient(), "http://127.0.0.1:$port/latest")
        assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.NETWORK), repository.check())
    }

    @Test fun reportsTimeoutsAndCancelsTheUnderlyingCall() = runBlocking {
        val timeoutClient = OkHttpClient.Builder().addInterceptor { throw java.net.SocketTimeoutException() }.build()
        assertEquals(UpdateCheckResult.Failed(UpdateCheckResult.Reason.TIMEOUT),
            GitHubUpdateRepository("1.4.0", timeoutClient).check())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
            if (chain.call().isCanceled()) cancelled.countDown()
            throw java.io.IOException("Fixture request ended")
        }.build()
        val job = async { GitHubUpdateRepository("1.4.0", client).check() }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            release.countDown()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
        } finally { release.countDown(); client.dispatcher.cancelAll() }
    }

    private suspend fun withResponse(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        check: suspend (GitHubUpdateRepository) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/latest") { exchange ->
            assertEquals("application/vnd.github+json", exchange.requestHeaders.getFirst("Accept"))
            headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            check(GitHubUpdateRepository("1.4.0", OkHttpClient(), "http://127.0.0.1:${server.address.port}/latest"))
        } finally { server.stop(0) }
    }
}
