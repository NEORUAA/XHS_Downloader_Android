package com.neoruaa.xhsdn.feature.detail

import android.graphics.Bitmap
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.*
import com.neoruaa.xhsdn.core.model.*
import com.neoruaa.xhsdn.data.*
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.tasks.DownloadSessionEntity
import com.neoruaa.xhsdn.utils.deleteStoredMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailProgressTest {
    @Test fun independentFileProgressSurvivesPauseReentryAndResume() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XHSApplication>()
        val container = app.appContainer
        container.initialization.await()
        assumeTrue(container.taskRepository.observeTasks().first().none { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING) })
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nodes(node: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        suspend fun awaitUi(predicate: (List<AccessibilityNodeInfo>) -> Boolean) = withTimeout(8_000) {
            while (!predicate(nodes())) delay(50)
        }
        val image = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(46, 128, 181)) }
        val thumbnail = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        image.recycle()
        val payloads = mapOf("/a" to thumbnail.copyOf(320 * 1024), "/b" to thumbnail.copyOf(640 * 1024), "/thumb" to thumbnail)
        val server = ServerSocket(0)
        val running = AtomicBoolean(true)
        val ranges = CopyOnWriteArrayList<String>()
        thread(isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { connection -> runCatching {
                        val input = connection.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
                        range?.let { ranges += it }
                        val bytes = payloads.getValue(path)
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val contentRange = if (range != null) "Content-Range: bytes $start-${bytes.lastIndex}/${bytes.size}\r\n" else ""
                        val status = if (range != null) "206 Partial Content" else "200 OK"
                        val out = connection.getOutputStream()
                        out.write("HTTP/1.1 $status\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size - start}\r\nETag: \"progress-fixture\"\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
                        for (offset in start until bytes.size step 4096) {
                            out.write(bytes, offset, minOf(4096, bytes.size - offset)); out.flush()
                            if (path != "/thumb") Thread.sleep(if (path == "/a") 80 else 100)
                        }
                    } }
                }
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var taskId: Long? = null
        var drain: Job? = null
        try {
            val id = container.taskRepository.createTask("https://www.xiaohongshu.com/explore/detail_progress_fixture", "Independent progress fixture", NoteType.IMAGE, 2)
            taskId = id
            val base = "http://127.0.0.1:${server.localPort}"
            val note = ResolvedNote("", "Independent progress fixture", null, null, null, null, noteId = "detail_progress_fixture",
                items = listOf(ResolvedMedia.Image("$base/a", id = "a", previewUrl = "$base/thumb", width = 800, height = 1200),
                    ResolvedMedia.Image("$base/b", id = "b", previewUrl = "$base/thumb", width = 800, height = 1200)))
            container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(id, DownloadJson.encodeToString(AppSettings()), resolvedJson = DownloadJson.encodeToString(note)))
            drain = launch(Dispatchers.IO) { container.downloadQueue.drain() }
            val live = withTimeout(8_000) { container.downloadQueue.mediaProgress.first { values -> values[id]?.let { it.size == 2 && it.values.all { value -> value.downloaded > 0 && !value.complete } } == true } }.getValue(id)
            assertNotEquals(live.getValue("a").fraction, live.getValue("b").fraction)
            awaitUi { it.any { node -> node.text?.toString() == "Independent progress fixture" } }
            var card = nodes().first { it.text?.toString() == "Independent progress fixture" }
            while (!card.isClickable) card = card.parent ?: error("Missing task card")
            assertTrue(card.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitUi { it.count { node -> node.text?.toString()?.matches(Regex("\\d+%")) == true } >= 2 }
            container.downloadQueue.pause(id)
            drain.join()
            awaitUi { it.count { node -> node.text?.toString() == app.getString(R.string.detail_transfer_paused) } == 2 }
            val stored = container.taskDatabase.downloadSessionDao().resources(id).filter { it.mediaId.endsWith(":transfer") }
            assertEquals(2, stored.size)
            assertTrue(stored.all { it.bytesDownloaded > 0 && it.totalBytes > it.bytesDownloaded })
            assertFalse(container.downloadQueue.mediaProgress.value.containsKey(id))
            scenario.recreate()
            awaitUi { it.count { node -> node.text?.toString() == app.getString(R.string.detail_transfer_paused) } == 2 }
            val screenshot = automation.takeScreenshot()
            File(app.cacheDir, "detail-independent-progress.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            container.downloadQueue.resume(id)
            val completed = withTimeout(30_000) { container.taskRepository.observeTask(id).first { it?.status == TaskStatus.COMPLETED } }!!
            assertEquals(2, completed.mediaRefs.size)
            assertTrue(ranges.isNotEmpty())
            awaitUi { it.any { node -> node.text?.toString() == app.getString(R.string.downloaded_files_title_lower) } && it.none { node -> node.text?.toString()?.matches(Regex("\\d+%")) == true } }
        } finally {
            taskId?.let { id ->
                container.downloadQueue.cancel(id)
                container.taskRepository.getTaskById(id)?.mediaRefs?.forEach { app.deleteStoredMedia(it) }
                container.downloadQueue.delete(id)
            }
            drain?.cancelAndJoin()
            scenario.close()
            running.set(false); server.close()
        }
    }
}
