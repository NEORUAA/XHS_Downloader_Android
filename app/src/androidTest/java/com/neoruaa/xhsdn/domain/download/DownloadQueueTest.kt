package com.neoruaa.xhsdn.domain.download

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.data.settings.DownloadJson
import com.neoruaa.xhsdn.data.tasks.DownloadSessionEntity
import com.neoruaa.xhsdn.utils.deleteStoredMedia
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadQueueTest {
    @Test fun selectionDefersFullTransferAndPausedTaskResumesItsRange() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XHSApplication>()
        val container = app.appContainer
        container.initialization.await()
        val server = ServerSocket(0)
        val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val bytes = ByteArray(192 * 1024) { (it % 253).toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[2] = 0xff.toByte() }
        val serverThread = thread(isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { connection -> runCatching {
                        val input = connection.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
                        requests.add(path to range)
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val output = connection.getOutputStream()
                        if (path == "/blocked") {
                            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@runCatching
                        }
                        val status = if (range != null) "206 Partial Content" else "200 OK"
                        val contentRange = if (range != null) "Content-Range: bytes $start-${bytes.lastIndex}/${bytes.size}\r\n" else ""
                        output.write(("HTTP/1.1 $status\r\nContent-Length: ${bytes.size - start}\r\nETag: \"fixture-v1\"\r\n$contentRange" +
                            "Content-Type: image/jpeg\r\nConnection: close\r\n\r\n").toByteArray())
                        for (offset in start until bytes.size step 2048) {
                            output.write(bytes, offset, minOf(2048, bytes.size - offset)); output.flush()
                            Thread.sleep(35)
                        }
                    } }
                }
            }
        }
        val taskIds = mutableListOf<Long>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val id = container.taskRepository.createTask("https://www.xiaohongshu.com/explore/fixture", "Shared caption", NoteType.IMAGE, 2)
            taskIds += id
            val note = ResolvedNote("https://www.xiaohongshu.com/explore/fixture", "Queue fixture", "Fixture", null, null, null, noteId = "fixture_${System.nanoTime()}",
                items = listOf(ResolvedMedia.Image("http://127.0.0.1:${server.localPort}/selected", id = "selected"),
                    ResolvedMedia.Image("http://127.0.0.1:${server.localPort}/unselected", id = "unselected")))
            container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(id, DownloadJson.encodeToString(AppSettings()),
                resolvedJson = DownloadJson.encodeToString(note), requireSelection = true))
            container.downloadQueue.drain()
            assertEquals(TaskStatus.WAITING_FOR_USER, container.taskRepository.getTaskById(id)?.status)
            assertTrue(requests.isEmpty())
            container.downloadQueue.select(id, setOf("selected"))
            withTimeout(10_000) { while (requests.isEmpty()) delay(25) }
            val speed = withTimeout(10_000) { container.downloadQueue.downloadSpeeds.first { (it[id] ?: 0) > 0 } }
            assertTrue(speed.getValue(id) > 0)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun speedVisible(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.text?.toString()?.matches(Regex(".*[1-9].*/s")) == true) return true
                return (0 until node.childCount).any { speedVisible(node.getChild(it)) }
            }
            withTimeout(5_000) { while (!speedVisible(automation.rootInActiveWindow)) delay(50) }
            val screenshot = automation.takeScreenshot()
            assertNotNull(screenshot)
            java.io.File(app.cacheDir, "download-speed-ui.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            container.downloadQueue.pause(id)
            assertEquals(TaskStatus.PAUSED, container.taskRepository.getTaskById(id)?.status)
            assertFalse(container.downloadQueue.downloadSpeeds.value.containsKey(id))
            container.downloadQueue.resume(id)
            withTimeout(10_000) { container.downloadQueue.downloadSpeeds.first { (it[id] ?: 0) > 0 } }
            val completed = withTimeout(20_000) { container.taskRepository.observeTask(id).first { it?.isCompleted == true } }!!
            assertEquals(completed.errorMessage, TaskStatus.COMPLETED, completed.status)
            assertEquals("Shared caption", completed.noteTitle)
            assertEquals(1, completed.totalFiles)
            assertEquals(1, completed.mediaRefs.size)
            assertTrue(requests.all { it.first == "/selected" })
            assertTrue(requests.any { it.second?.startsWith("bytes=") == true })
            withTimeout(5_000) { container.downloadQueue.downloadSpeeds.first { id !in it } }
            suspend fun repeatTask(skip: Boolean): com.neoruaa.xhsdn.data.DownloadTask {
                val next = container.taskRepository.createTask(note.canonicalUrl, "Queue fixture", NoteType.IMAGE, 1)
                taskIds += next
                val settings = AppSettings(downloadOptions = com.neoruaa.xhsdn.data.settings.DownloadOptions(skipExisting = skip))
                container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(next, DownloadJson.encodeToString(settings),
                    resolvedJson = DownloadJson.encodeToString(note), selectedJson = DownloadJson.encodeToString(setOf("selected"))))
                container.downloadQueue.wake()
                return withTimeout(20_000) { container.taskRepository.observeTask(next).first { it?.isCompleted == true } }!!
            }
            val beforeRepeat = requests.size
            assertEquals(TaskStatus.COMPLETED, repeatTask(false).status)
            assertTrue(requests.size > beforeRepeat)
            val beforeSkip = requests.size
            assertEquals(TaskStatus.SKIPPED, repeatTask(true).status)
            assertEquals(beforeSkip, requests.size)

            suspend fun outputTask(note: ResolvedNote, options: com.neoruaa.xhsdn.data.settings.DownloadOptions): com.neoruaa.xhsdn.data.DownloadTask {
                val next = container.taskRepository.createTask(note.canonicalUrl, "Output fixture", NoteType.IMAGE, note.mediaCount)
                taskIds += next
                container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(next,
                    DownloadJson.encodeToString(AppSettings(downloadOptions = options)), resolvedJson = DownloadJson.encodeToString(note)))
                container.downloadQueue.wake()
                return withTimeout(20_000) { container.taskRepository.observeTask(next).first { it?.isCompleted == true } }!!
            }
            val blocked = ResolvedMedia.Video("http://127.0.0.1:${server.localPort}/blocked", id = "blocked")
            val partialNote = note.copy(items = listOf(note.items.first(), blocked))
            val partial = outputTask(partialNote, com.neoruaa.xhsdn.data.settings.DownloadOptions(maxRetries = 0))
            assertEquals(TaskStatus.PARTIAL, partial.status)
            assertEquals(1, partial.completedFiles)
            assertEquals(1, partial.failedFiles)
            assertEquals(1, partial.mediaRefs.size)
            val selectedRequests = requests.count { it.first == "/selected" }
            container.downloadQueue.resume(partial.id)
            withTimeout(20_000) { container.taskRepository.observeTask(partial.id).first { it?.status == TaskStatus.PARTIAL } }
            assertEquals(selectedRequests, requests.count { it.first == "/selected" })

            val livePartial = outputTask(note.copy(items = listOf(ResolvedMedia.LivePhoto(note.items.first() as ResolvedMedia.Image, blocked))),
                com.neoruaa.xhsdn.data.settings.DownloadOptions(maxRetries = 0))
            assertEquals(TaskStatus.PARTIAL, livePartial.status)
            assertEquals(1, livePartial.mediaRefs.size)

            val text = outputTask(note.copy(items = emptyList(), body = "Line one\nLine two"),
                com.neoruaa.xhsdn.data.settings.DownloadOptions(noteFormat = com.neoruaa.xhsdn.data.settings.NoteFormat.BOTH, noteArchive = true))
            assertEquals(TaskStatus.COMPLETED, text.status)
            assertEquals(setOf("txt", "md"), text.mediaRefs.map { it.displayName.substringAfterLast('.') }.toSet())
            text.mediaRefs.forEach { ref ->
                val content = com.neoruaa.xhsdn.data.storage.AndroidStorageSink(app).open(ref)!!.bufferedReader().use { it.readText() }
                assertTrue(content.contains("Line one\nLine two"))
            }

        } finally {
            taskIds.forEach { id ->
                container.downloadQueue.cancel(id)
                container.taskRepository.getTaskById(id)?.mediaRefs?.forEach { app.deleteStoredMedia(it) }
                container.downloadQueue.delete(id)
            }
            scenario.close()
            running.set(false); server.close(); serverThread.join(1000)
        }
    }
}
