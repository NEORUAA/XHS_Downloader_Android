package com.neoruaa.xhsdn.feature.comments

import android.graphics.Bitmap
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.*
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.data.*
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.tasks.DownloadSessionEntity
import com.neoruaa.xhsdn.data.xhs.XhsNoteParser
import com.neoruaa.xhsdn.utils.*
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentImageDownloadTest {
    @Test fun webViewCommentImagesUseTheExistingSelectionAndStoragePipeline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XHSApplication>()
        val container = app.appContainer
        container.initialization.await()
        assumeTrue(container.taskRepository.observeTasks().first().none { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING) })
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { connection -> runCatching {
                    val input = connection.getInputStream().bufferedReader()
                    val head = input.readLine().startsWith("HEAD ")
                    while (!input.readLine().isNullOrEmpty()) { }
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (!head) write(bytes)
                    }
                } }
            }
        }
        val mediaUrl = InstrumentationRegistry.getArguments().getString("commentMediaUrl") ?: "http://127.0.0.1:${server.localPort}/comment"
        val noteId = "0123456789abcdef01234567"
        val noteUrl = "https://www.xiaohongshu.com/explore/$noteId"
        val detail = JSONObject().put("note", JSONObject().put("noteId", noteId).put("title", "Comment image fixture").put("imageList", org.json.JSONArray()))
            .put("comments", JSONObject().put("hasMore", true).put("firstRequestFinish", true).put("list", org.json.JSONArray().put(
                JSONObject().put("id", "comment1").put("noteId", noteId).put("pictures", org.json.JSONArray().put(
                    JSONObject().put("urlDefault", mediaUrl).put("urlPre", mediaUrl).put("width", 1440).put("height", 1440))))))
        val state = JSONObject().put("note", JSONObject().put("noteDetailMap", JSONObject().put(noteId, detail)))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val extraction = CompletableDeferred<String>()
        var taskId: Long? = null
        try {
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(app.assets.open("xhs_extractor.js").bufferedReader().use { it.readText() }) {
                            extraction.complete(JSONTokener(it).nextValue() as String)
                            view.destroy()
                        }
                    }
                }
                web.loadDataWithBaseURL(noteUrl, "<script>window.__INITIAL_STATE__=$state;</script>", "text/html", "UTF-8", null)
            }
            val note = XhsNoteParser().parseDetail(JSONObject(withTimeout(10_000) { extraction.await() }), noteUrl, noteId)
            val image = note.orderedMedia.single() as ResolvedMedia.Image
            assertEquals("comment1", image.commentId)
            val id = container.taskRepository.createTask(noteUrl, "Comment image fixture", NoteType.IMAGE, 1)
            taskId = id
            val settings = AppSettings(downloadOptions = DownloadOptions(imageDownload = false, commentImageDownload = true))
            container.taskDatabase.downloadSessionDao().saveSession(DownloadSessionEntity(id, DownloadJson.encodeToString(settings), resolvedJson = DownloadJson.encodeToString(note), requireSelection = true))
            container.downloadQueue.drain()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow, actionOnly: Boolean = false): AccessibilityNodeInfo? {
                if (node == null) return null
                if ((!actionOnly && node.text?.toString() == text) || node.contentDescription?.toString() == text) return node
                return (0 until node.childCount).firstNotNullOfOrNull { find(text, node.getChild(it), actionOnly) }
            }
            suspend fun awaitText(text: String, actionOnly: Boolean = false): AccessibilityNodeInfo = withTimeoutOrNull(8_000) {
                var node = find(text, actionOnly = actionOnly)
                while (node == null) { delay(50); node = find(text, actionOnly = actionOnly) }
                node
            } ?: run {
                automation.takeScreenshot()?.let { screenshot ->
                    java.io.File(app.cacheDir, "comment-image-failure.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                error("Missing comment UI: $text (actionOnly=$actionOnly)")
            }
            awaitText("Comment image fixture")
            // Open the pending task's existing selection action through its row.
            val choose = awaitText(app.getString(R.string.download_select), actionOnly = true)
            var action = choose
            while (!action.isClickable) action = action.parent ?: error("Missing selection action")
            action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitText(app.getString(R.string.selective_type_comment))
            awaitText(app.getString(R.string.download_comments_partial))
            assertNotNull(com.neoruaa.xhsdn.ui.remoteThumbnail(app, image.previewUrl))
            delay(1_000) // Allow the image and sheet transition to settle before capture.
            automation.takeScreenshot()?.let { screenshot ->
                java.io.File(app.cacheDir, "comment-image-selection.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            container.downloadQueue.select(id, setOf(image.id))
            val saved = withTimeout(30_000) { container.taskRepository.observeTask(id).first { it?.status == TaskStatus.COMPLETED } }!!
            assertEquals(1, saved.mediaRefs.size)
            assertTrue(saved.mediaRefs.single().displayName.contains("_comment."))
            assertNotNull(app.decodeSampledBitmap(saved.mediaRefs.single(), 80, 80))
            assertEquals(app.getString(R.string.download_comments_partial), saved.errorMessage)
        } finally {
            taskId?.let { id ->
                container.downloadQueue.cancel(id)
                container.taskRepository.getTaskById(id)?.mediaRefs?.forEach { app.deleteStoredMedia(it) }
                container.downloadQueue.delete(id)
            }
            scenario.close()
            server.close()
            worker.join(1000)
        }
    }
}
