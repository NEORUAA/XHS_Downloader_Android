package com.neoruaa.xhsdn.feature.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.*
import com.neoruaa.xhsdn.viewmodels.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class MediaPreviewSheetTest {
    private val app = ApplicationProvider.getApplicationContext<XHSApplication>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun displaysSizeResolutionAndMediaRolesAndKeepsSelectionInteractive() {
        val directory = File(app.cacheDir, "selection-ui-fixture").apply { mkdirs() }
        fun picture(name: String, color: Int): String {
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            canvas.drawCircle(200f, 230f, 90f, Paint().apply { this.color = Color.WHITE })
            val file = File(directory, "$name.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            return file.path
        }
        val items = listOf(
            CachedMediaItem(picture("cover", Color.rgb(42, 111, 180)), "1", MediaType.IMAGE, width = 1200, height = 800, cover = true),
            CachedMediaItem(picture("live", Color.rgb(80, 155, 120)), "2", MediaType.IMAGE, width = 800, height = 1200, live = true),
            CachedMediaItem(picture("video", Color.rgb(185, 125, 65)), "3", MediaType.VIDEO, width = 1920, height = 1080, sizeBytes = 1_500_000),
        )
        val state = mutableStateOf(MainUiState(selectiveDownload = SelectiveDownloadUiState(show = true,
            phase = SelectiveDownloadPhase.Ready, items = items, selectedPaths = setOf(items[1].path))))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent { MiuixTheme {
                    SelectiveDownloadSheet(state.value, onCancel = { state.value = MainUiState() }, onSave = {}, onToggleItem = { path ->
                        val selection = state.value.selectiveDownload
                        val selected = if (path in selection.selectedPaths) selection.selectedPaths - path else selection.selectedPaths + path
                        state.value = state.value.copy(selectiveDownload = selection.copy(selectedPaths = selected))
                    })
                } }
            }
            awaitText(app.getString(R.string.selective_type_cover))
            awaitText(app.getString(R.string.selective_type_live))
            awaitText(app.getString(R.string.selective_dimensions, 1200, 800))
            awaitText(android.text.format.Formatter.formatShortFileSize(app, 1_500_000))
            var node = awaitText(app.getString(R.string.selective_type_cover))
            while (!node.isClickable) node = node.parent ?: error("Missing clickable preview")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText(app.getString(R.string.selective_download_ready, 2, 3))
            val screenshot = automation.takeScreenshot()
            File(app.cacheDir, "selection-metadata-ui.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally { scenario.close(); directory.deleteRecursively() }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) { find(text)?.let { return it }; Thread.sleep(50) }
        error("Missing preview text: $text")
    }
}
