package com.neoruaa.xhsdn

import java.lang.reflect.Proxy
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Test

class FileDownloaderCancellationTest {
    @Test
    fun cancelCallsCancelsEveryInFlightCall() {
        val cancelled = mutableListOf<String>()
        val calls = listOf(
            fakeCall("first", cancelled),
            fakeCall("second", cancelled),
            fakeCall("third", cancelled),
        )

        cancelCalls(calls)

        assertEquals(listOf("first", "second", "third"), cancelled)
    }

    private fun fakeCall(name: String, cancelled: MutableList<String>): Call =
        Proxy.newProxyInstance(
            Call::class.java.classLoader,
            arrayOf(Call::class.java),
        ) { _, method, _ ->
            if (method.name == "cancel") cancelled += name
            null
        } as Call
}
