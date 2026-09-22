package com.neoruaa.xhsdn.feature.update

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.data.update.GitHubRelease
import com.neoruaa.xhsdn.data.update.UpdateCheckResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    private val app = ApplicationProvider.getApplicationContext<XHSApplication>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun automaticFailuresStaySilentAndManualResultsRemainDismissible() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var result: UpdateCheckResult = UpdateCheckResult.Failed(UpdateCheckResult.Reason.NETWORK)
        val controller = UpdateCheckController(scope) { result }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    MiuixTheme {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { UpdateDialog(controller) }
                    }
                }
                controller.checkOnStartup()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(controller.state.value.showDialog)
            assertNull(find(app.getString(R.string.update_failed)))

            val outcomes = listOf(
                UpdateCheckResult.UpToDate("1.3.5") to R.string.update_up_to_date,
                UpdateCheckResult.NoRelease to R.string.update_no_release,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.NETWORK) to R.string.update_failed,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.TIMEOUT) to R.string.update_failed,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.RATE_LIMITED) to R.string.update_failed,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.HTTP, 503) to R.string.update_failed,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_RESPONSE) to R.string.update_failed,
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_VERSION) to R.string.update_failed,
            )
            for ((outcome, title) in outcomes) {
                scenario.onActivity { result = outcome; controller.checkManually() }
                awaitText(app.getString(title))
                click(app.getString(R.string.update_close))
                awaitCondition { controller.state.value.result == null }
            }

            val notes = (1..50).joinToString("\n") { "Release note $it: improved download reliability." }
            scenario.onActivity {
                result = UpdateCheckResult.Available(GitHubRelease("2.0.0", notes,
                    "https://github.com/NEORUAA/XHS_Downloader_Android/releases/tag/2.0.0"))
                controller.checkManually()
            }
            awaitText(app.getString(R.string.update_available))
            val download = awaitText(app.getString(R.string.update_open_release))
            assertTrue(download.isVisibleToUser)
            val screenshot = automation.takeScreenshot()
            assertNotNull(screenshot)
            File(app.cacheDir, "update-dialog-long-notes.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            click(app.getString(R.string.update_later))
            awaitCondition { controller.state.value.result == null }
        } finally { scenario.close(); scope.cancel() }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitCondition {
            match = find(text)?.takeIf { it.isVisibleToUser }
            match != null
        }
        return checkNotNull(match)
    }

    private fun click(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = node.parent ?: error("No clickable parent for $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for update dialog")
            Thread.sleep(50)
        }
    }
}
