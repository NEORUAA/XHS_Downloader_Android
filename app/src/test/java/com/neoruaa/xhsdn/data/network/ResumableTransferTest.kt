package com.neoruaa.xhsdn.data.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResumableTransferTest {
    private val bytes = ByteArray(4096) { (it % 251).toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[2] = 0xff.toByte() }

    @Test fun resumesOnlyFromConfirmedRangeAndValidator() = runBlocking {
        val directory = Files.createTempDirectory("resume-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var calls = 0
        var range: String? = null
        var ifRange: String? = null
        server.createContext("/media") { exchange ->
            calls++
            exchange.responseHeaders.add("ETag", "\"v1\"")
            if (calls == 1) {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes, 0, 1024)
                exchange.responseBody.flush()
            } else {
                range = exchange.requestHeaders.getFirst("Range")
                ifRange = exchange.requestHeaders.getFirst("If-Range")
                exchange.responseHeaders.add("Content-Range", "bytes 1024-4095/4096")
                exchange.sendResponseHeaders(206, 3072)
                exchange.responseBody.write(bytes, 1024, 3072)
            }
            runCatching { exchange.close() }
        }
        server.start()
        try {
            val transfer = ResumableTransfer(directory)
            val url = "http://127.0.0.1:${server.address.port}/media"
            val client = OkHttpClient()
            assertTrue(runCatching { transfer.fetch(client, 1, "media", listOf(url), 0) }.isFailure)
            val saved = transfer.fetch(client, 1, "media", listOf(url), 0)
            assertEquals("bytes=1024-", range)
            assertEquals("\"v1\"", ifRange)
            assertArrayEquals(bytes, saved.file.readBytes())
            assertEquals("jpg", saved.type.extension)
        } finally { server.stop(0); directory.deleteRecursively() }
    }

    @Test fun rejectsHtmlDisguisedAsImageAndUsesNextCandidate() = runBlocking {
        val directory = Files.createTempDirectory("candidate-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var rejectedCalls = 0
        server.createContext("/blocked") { exchange ->
            rejectedCalls++
            val html = "<html>Sign in</html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.createContext("/good") { exchange -> exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val result = ResumableTransfer(directory).fetch(OkHttpClient(), 1, "media", listOf("$base/blocked", "$base/good"), 3)
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(1, rejectedCalls)
        } finally { server.stop(0); directory.deleteRecursively() }
    }

    @Test fun validatesRangeBoundsWithoutOverflow() {
        assertNull(ResumableTransfer.parseRange("bytes 0-100/100"))
        assertNull(ResumableTransfer.parseRange("bytes 10-2/100"))
        assertNull(ResumableTransfer.parseRange("bytes 0-999999999999999999999999/100"))
        assertEquals(ResumableTransfer.Companion.ByteRange(5, 9, 10), ResumableTransfer.parseRange("bytes 5-9/10"))
    }
}
