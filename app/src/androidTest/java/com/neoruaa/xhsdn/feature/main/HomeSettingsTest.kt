package com.neoruaa.xhsdn.feature.main

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class HomeSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<XHSApplication>()
    private val automation by lazy { InstrumentationRegistry.getInstrumentation().uiAutomation }

    @Test
    fun wideTopBarLeavesUpwardDragAvailableForItsNestedContent() = runBlocking<Unit> {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            lateinit var behavior: ScrollBehavior
            lateinit var retainedState: TopAppBarState
            val isWide = mutableStateOf(true)
            val composed = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.setContent {
                    MiuixTheme {
                        behavior = MiuixScrollBehavior(rememberTopAppBarState())
                        AdaptiveTopAppBar(app.getString(R.string.app_name), isWideScreen = isWide.value, scrollBehavior = behavior)
                        SideEffect { composed.countDown() }
                    }
                }
            }
            assertTrue("Wide top bar did not finish composition", composed.await(5, TimeUnit.SECONDS))
            suspend fun awaitHeightOffsetLimit(matches: (Float) -> Boolean) = withTimeout(5_000) {
                var ready = false
                while (!ready) {
                    scenario.onActivity { ready = matches(behavior.state.heightOffsetLimit) }
                    if (!ready) delay(50)
                }
            }
            scenario.onActivity {
                assertEquals(0f, behavior.state.heightOffsetLimit, 0f)
                assertEquals(Offset.Zero, behavior.nestedScrollConnection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput))
                assertEquals(0f, behavior.state.heightOffset, 0f)
                retainedState = behavior.state
                behavior.nestedScrollConnection.onPostScroll(Offset(0f, -240f), Offset.Zero, NestedScrollSource.UserInput)
                assertEquals(-240f, behavior.state.contentOffset, 0f)
                isWide.value = false
            }
            awaitHeightOffsetLimit { it.isFinite() && it < 0f && it > -Float.MAX_VALUE }
            scenario.onActivity {
                assertSame(retainedState, behavior.state)
                assertEquals("Scrolled content must restore a collapsed phone bar",
                    behavior.state.heightOffsetLimit, behavior.state.heightOffset, 0f)
                isWide.value = true
            }
            awaitHeightOffsetLimit { it == 0f }
            scenario.onActivity {
                assertSame(retainedState, behavior.state)
                assertEquals(0f, behavior.state.heightOffset, 0f)
                assertEquals(-240f, behavior.state.contentOffset, 0f)
                behavior.state.contentOffset = 0f
                isWide.value = false
            }
            awaitHeightOffsetLimit { it.isFinite() && it < 0f && it > -Float.MAX_VALUE }
            scenario.onActivity {
                assertSame(retainedState, behavior.state)
                assertEquals("Content at the top must restore an expanded phone bar", 0f, behavior.state.heightOffset, 0f)
                assertEquals(0f, behavior.state.contentOffset, 0f)
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
            val launchedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = launchedScenario
            launchedScenario.onActivity { activity ->
                val liveApp = activity.application as XHSApplication
                assertSame("Home uses a different application", app, liveApp)
                assertSame("Home uses a different settings repository", repository, liveApp.appContainer.settingsRepository)
            }
            awaitText(app.getString(R.string.start_download_from_clipboard))
            // Reproduce a persisted setting arriving after the navigation entry was created.
            repository.setManualInputLinks(true)
            assertTrue("Manual input setting was not persisted", repository.currentSettings.manualInputLinks)
            val manualAction = try {
                awaitText(app.getString(R.string.manual_input_links))
            } catch (error: AssertionError) {
                var liveState = "<activity unavailable>"
                launchedScenario.onActivity { activity ->
                    val liveApp = activity.application as XHSApplication
                    val liveRepository = liveApp.appContainer.settingsRepository
                    liveState = "lifecycle=${activity.lifecycle.currentState}, " +
                        "sameApplication=${liveApp === app}, sameRepository=${liveRepository === repository}, " +
                        "sourceManual=${repository.currentSettings.manualInputLinks}, " +
                        "activityManual=${liveRepository.currentSettings.manualInputLinks}"
                }
                throw AssertionError("${error.message}; $liveState", error)
            }
            click(manualAction)
            awaitText(app.getString(R.string.main_url_example))
            awaitText(app.getString(R.string.common_download))
            click(awaitText(app.getString(R.string.cancel)))
            withTimeout(5_000) {
                while (find(app.getString(R.string.main_url_example)) != null) delay(50)
            }
            click(awaitText(app.getString(R.string.more_options)))
            click(awaitText(app.getString(R.string.download_info_only)))
            awaitText(app.getString(R.string.main_url_example))
            val saveInfoText = app.getString(R.string.common_save_info)
            assertEquals(saveInfoText, awaitText(saveInfoText).text?.toString())
            val saveBounds = awaitActionBounds(saveInfoText)
            val cancelBounds = awaitActionBounds(app.getString(R.string.cancel))
            assertEquals("Dialog action heights must stay equal", cancelBounds.height(), saveBounds.height())
            assertEquals("Dialog actions must share a row", cancelBounds.exactCenterY(), saveBounds.exactCenterY(), 1.5f)
            val screenshot = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "manual-save-info-dialog.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
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

    @Test
    fun topBarSearchClearsQueryAfterCloseAndBack() = runBlocking<Unit> {
        val repository = app.appContainer.settingsRepository
        repository.awaitReady()
        val original = repository.currentSettings
        repository.update { it.copy(manualInputLinks = false, autoReadClipboard = false, showClipboardBubble = false) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val homeAction = app.getString(R.string.start_download_from_clipboard)
            val searchAction = app.getString(R.string.main_search_history)
            val noResults = app.getString(R.string.main_search_no_results)
            val query = "xhs-search-regression-${System.nanoTime()}"
            awaitText(homeAction)

            click(awaitText(searchAction))
            var input = awaitEditable()
            assertEquals("", input.text?.toString().orEmpty())
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }))
            awaitText(noResults)
            scenario.recreate()
            awaitText(noResults)
            assertEquals(query, awaitEditable().text?.toString())
            click(awaitText(app.getString(R.string.cancel)))
            withTimeout(5_000) {
                while (findEditable() != null) delay(50)
            }
            awaitText(homeAction)

            click(awaitText(searchAction))
            input = awaitEditable()
            assertEquals("", input.text?.toString().orEmpty())
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$query-back")
            }))
            awaitText(noResults)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            withTimeout(5_000) {
                while (findEditable() != null) delay(50)
            }
            awaitText(homeAction)

            click(awaitText(searchAction))
            assertEquals("", awaitEditable().text?.toString().orEmpty())
            click(awaitText(app.getString(R.string.cancel)))
        } finally {
            scenario?.close()
            repository.update { it.copy(manualInputLinks = original.manualInputLinks,
                autoReadClipboard = original.autoReadClipboard, showClipboardBubble = original.showClipboardBubble) }
        }
    }

    private suspend fun awaitActionBounds(text: String): android.graphics.Rect = withTimeout(8_000) {
        var previous: android.graphics.Rect? = null
        var settled: android.graphics.Rect? = null
        while (settled == null) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            val current = node?.takeIf { it.refresh() && it.isVisibleToUser }?.let {
                android.graphics.Rect().also(it::getBoundsInScreen)
            }
            if (current != null && current == previous) settled = current
            previous = current
            if (settled == null) delay(100)
        }
        checkNotNull(settled)
    }

    private fun findEditable(node: AccessibilityNodeInfo? = automation.rootInActiveWindow?.takeIf { it.refresh() }): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findEditable(node.getChild(it)) }
    }

    private suspend fun awaitEditable(): AccessibilityNodeInfo = withTimeout(8_000) {
        var node = findEditable()
        while (node == null) { delay(50); node = findEditable() }
        node
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow?.takeIf { it.refresh() }): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(text, node.getChild(it)) }
    }

    private suspend fun awaitText(text: String): AccessibilityNodeInfo {
        val node = withTimeoutOrNull(8_000) {
            var match = find(text)
            while (match == null) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                delay(50)
                match = find(text)
            }
            match
        }
        return node ?: throw AssertionError("Timed out waiting for '$text'. Active window ${visibleLabels()}")
    }

    private fun visibleLabels(): String {
        val root = automation.rootInActiveWindow?.takeIf { it.refresh() } ?: return "<none>"
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val labels = linkedSetOf<String>()
        pending.add(root)
        while (pending.isNotEmpty() && labels.size < 24) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) {
                listOfNotNull(node.text, node.contentDescription).forEach {
                    val label = it.toString().replace('\n', ' ').take(120)
                    if (label.isNotBlank()) labels.add(label)
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return "${root.packageName} ${labels.joinToString(prefix = "[", postfix = "]")}"
    }

    private fun click(target: AccessibilityNodeInfo) {
        val label = target.text?.toString() ?: target.contentDescription?.toString()
        var node = label?.let { find(it) } ?: target
        assertTrue("Click target is no longer available '$label'", node.refresh())
        while (!node.isClickable) node = node.parent ?: error("Missing click action for '$label'")
        assertTrue("Click action failed for '$label'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
