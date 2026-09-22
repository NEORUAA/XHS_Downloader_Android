package com.neoruaa.xhsdn

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @Test fun repeatedShareCreatesTasksButRecreationDoesNotReplayIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XHSApplication>()
        val container = app.appContainer
        container.initialization.await()
        container.taskRepository.observeTasks().first()
            .filter { it.noteUrl.startsWith("https://xhslink.cn/o/intent_fixture_") }
            .forEach { container.downloadQueue.delete(it.id) }
        val url = "https://xhslink.cn/o/intent_fixture_${System.nanoTime()}"
        suspend fun rows() = container.taskRepository.observeTasks().first().filter { it.noteUrl == url }
        suspend fun awaitCount(count: Int) = withTimeout(5000) { while (rows().size < count) delay(25) }
        val launchIntent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            awaitCount(1)
            repeat(1) {
                scenario.onActivity { activity ->
                    val share = Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    activity.startActivity(share)
                }
                awaitCount(2)
                scenario.onActivity { assertFalse(it.intent.hasExtra(Intent.EXTRA_TEXT)) }
            }
            rows().forEach { container.downloadQueue.pause(it.id) }
            scenario.recreate()
            // A resumed frame after recreation has no unconsumed share payload.
            scenario.onActivity { assertFalse(it.intent.hasExtra(Intent.EXTRA_TEXT)) }
            delay(300)
            assertEquals(2, rows().size)
        } finally {
            rows().forEach { container.downloadQueue.delete(it.id) }
            scenario.close()
        }
    }
}
