package com.neoruaa.xhsdn.ui

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RemoteMediaSizeTest {
    @Test fun readsSourceLengthUsingOnlyHead() = runBlocking {
        val client = client(200, "image/jpeg", "2345678")
        assertEquals(2345678L, requestMediaSize(client, "https://cdn.example/image"))
    }

    @Test fun acceptsOriginalCdnBinaryMediaWithAnExplicitLength() = runBlocking {
        for (type in listOf("application/octet-stream", "Application/Octet-Stream; charset=binary")) {
            assertEquals(9804323L, requestMediaSize(client(200, type, "9804323"), "https://cdn.example/image"))
        }
        assertNull(requestMediaSize(client(403, "application/octet-stream", "100"), "https://cdn.example/image"))
        assertNull(requestMediaSize(client(200, "application/octet-stream", null), "https://cdn.example/image"))
    }

    @Test fun unavailableOrNonMediaResponsesLeaveResolutionAsFallback() = runBlocking {
        for (client in listOf(client(405, "image/jpeg", "100"), client(200, "text/html", "100"),
            client(200, "video/mp4", null), client(200, "video/mp4", "-1"))) {
            assertNull(requestMediaSize(client, "https://cdn.example/media"))
        }
        assertNull(requestMediaSize(OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build(), "https://cdn.example/media"))
        assertNull(requestMediaSize(OkHttpClient(), "invalid"))
    }

    private fun client(code: Int, type: String, length: String?) = OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("HEAD", chain.request().method)
        assertEquals("identity", chain.request().header("Accept-Encoding"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .header("Content-Type", type).apply { length?.let { header("Content-Length", it) } }
            .body(ByteArray(0).toResponseBody()).build()
    }.build()
}
