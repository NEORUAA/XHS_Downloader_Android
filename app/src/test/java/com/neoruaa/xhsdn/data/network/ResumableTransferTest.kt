package com.neoruaa.xhsdn.data.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
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
            val received = AtomicLong()
            val record: (Long) -> Unit = { received.addAndGet(it) }
            assertTrue(runCatching { transfer.fetch(client, 1, "media", listOf(url), 0, onBytesReceived = record) }.isFailure)
            val saved = transfer.fetch(client, 1, "media", listOf(url), 0, onBytesReceived = record)
            assertEquals("bytes=1024-", range)
            assertEquals("\"v1\"", ifRange)
            assertArrayEquals(bytes, saved.file.readBytes())
            assertEquals("jpg", saved.type.extension)
            assertEquals(bytes.size.toLong(), received.get())
            transfer.fetch(client, 1, "media", listOf(url), 0, onBytesReceived = record)
            assertEquals("Cached bytes must not count as network traffic", bytes.size.toLong(), received.get())
            assertEquals(2, calls)
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

    @Test fun fullResponseReplacesPartialFileWhenServerIgnoresRange() = runBlocking {
        checkpointResponse(200, "\"v2\"", null) { transfer, client, url, calls ->
            val received = AtomicLong()
            val result = transfer.fetch(client, 1, "media", listOf(url), 0, onBytesReceived = { received.addAndGet(it) })
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals("A restarted response counts only its received bytes", bytes.size.toLong(), received.get())
            assertEquals(1, calls())
        }
    }

    @Test fun rejectsPartialResponseWithChangedValidator() = runBlocking {
        checkpointResponse(206, "\"v2\"", "bytes 1024-4095/4096") { transfer, client, url, _ ->
            val failure = runCatching { transfer.fetch(client, 1, "media", listOf(url), 0) }.exceptionOrNull()
            assertEquals(TransferException.Reason.RANGE, (failure as TransferException).reason)
        }
    }

    @Test fun accepts416OnlyWhenCompletedLengthAndValidatorMatch() = runBlocking {
        checkpointResponse(416, "\"v1\"", "bytes */4096", bytes.size) { transfer, client, url, calls ->
            repeat(2) {
                assertArrayEquals(bytes, transfer.fetch(client, 1, "media", listOf(url), 0).file.readBytes())
            }
            assertEquals(1, calls())
        }
        checkpointResponse(416, "\"v2\"", "bytes */4096", bytes.size) { transfer, client, url, _ ->
            val failure = runCatching { transfer.fetch(client, 1, "media", listOf(url), 0) }.exceptionOrNull()
            assertEquals(TransferException.Reason.RANGE, (failure as TransferException).reason)
        }
    }

    @Test fun countsChunkedResponseBytesWithoutContentLength() = runBlocking {
        val directory = Files.createTempDirectory("chunked-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val received = AtomicLong()
            val result = ResumableTransfer(directory).fetch(OkHttpClient(), 1, "media",
                listOf("http://127.0.0.1:${server.address.port}/media"), 0,
                onBytesReceived = { received.addAndGet(it) })
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(bytes.size.toLong(), received.get())
        } finally { server.stop(0); directory.deleteRecursively() }
    }

    private suspend fun checkpointResponse(
        code: Int, etag: String, contentRange: String?, savedLength: Int = 1024,
        check: suspend (ResumableTransfer, OkHttpClient, String, () -> Int) -> Unit,
    ) {
        val directory = Files.createTempDirectory("checkpoint-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var calls = 0
        server.createContext("/media") { exchange ->
            calls++
            exchange.responseHeaders.add("ETag", etag)
            contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
            val start = if (code == 206) savedLength else 0
            exchange.sendResponseHeaders(code, if (code == 416) -1 else (bytes.size - start).toLong())
            exchange.responseBody.use { if (code != 416) it.write(bytes, start, bytes.size - start) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media"
            val task = File(directory, "1").apply { mkdirs() }
            val key = ResumableTransfer.fingerprint("media")
            File(task, "$key.part").writeBytes(bytes.copyOf(savedLength))
            File(task, "$key.json").writeText(JSONObject().put("identity", ResumableTransfer.fingerprint(url))
                .put("etag", "\"v1\"").put("total", bytes.size).put("complete", false).toString())
            check(ResumableTransfer(directory), OkHttpClient(), url) { calls }
        } finally { server.stop(0); directory.deleteRecursively() }
    }
}
