package com.neoruaa.xhsdn.feature.main

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.SettingsActivity
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class HomeSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<XHSApplication>()
    private val automation by lazy { InstrumentationRegistry.getInstrumentation().uiAutomation }

    @Test
    fun wideTopBarLeavesUpwardDragAvailableForItsNestedContent() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            lateinit var behavior: ScrollBehavior
            val composed = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.setContent {
                    MiuixTheme {
                        behavior = MiuixScrollBehavior(rememberTopAppBarState())
                        AdaptiveTopAppBar(app.getString(R.string.app_name), isWideScreen = true, scrollBehavior = behavior)
                        SideEffect { composed.countDown() }
                    }
                }
            }
            assertTrue("Wide top bar did not finish composition", composed.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals(0f, behavior.state.heightOffsetLimit, 0f)
                assertEquals(Offset.Zero, behavior.nestedScrollConnection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput))
                assertEquals(0f, behavior.state.heightOffset, 0f)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun activeHomeReceivesSettingsWithoutNavigationOrResume() = runBlocking<Unit> {
        val repository = app.appContainer.settingsRepository
        repository.awaitReady()
        val original = repository.currentSettings
        // Suppress clipboard side effects while exercising the real home destination.
        repository.update { it.copy(manualInputLinks = false, autoReadClipboard = false, showClipboardBubble = false) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitText(app.getString(R.string.start_download_from_clipboard))
            // Reproduce a persisted setting arriving after the navigation entry was created.
            repository.setManualInputLinks(true)
            click(awaitText(app.getString(R.string.manual_input_links)))
            awaitText(app.getString(R.string.main_url_example))
            click(awaitText(app.getString(R.string.cancel)))
            withTimeout(5_000) {
                while (find(app.getString(R.string.main_url_example)) != null) delay(50)
            }
            repository.setManualInputLinks(false)
            awaitText(app.getString(R.string.start_download_from_clipboard))
            assertNull(find(app.getString(R.string.manual_input_links)))
        } finally {
            scenario?.close()
            repository.update { it.copy(manualInputLinks = original.manualInputLinks,
                autoReadClipboard = original.autoReadClipboard, showClipboardBubble = original.showClipboardBubble) }
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(text, node.getChild(it)) }
    }

    private suspend fun awaitText(text: String): AccessibilityNodeInfo = withTimeout(8_000) {
        var node = find(text)
        while (node == null) { delay(50); node = find(text) }
        node
    }

    private fun click(target: AccessibilityNodeInfo) {
        var node = target
        while (!node.isClickable) node = node.parent ?: error("Missing click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
