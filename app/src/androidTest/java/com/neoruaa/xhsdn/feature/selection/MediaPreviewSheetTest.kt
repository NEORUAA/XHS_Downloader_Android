package com.neoruaa.xhsdn.feature.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.*
import com.neoruaa.xhsdn.ui.LocalMiuixComponentScope
import com.neoruaa.xhsdn.ui.MiuixBottomSheet
import com.neoruaa.xhsdn.ui.MiuixComponentHost
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.miuixBackdropSource
import com.neoruaa.xhsdn.viewmodels.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.SelectAll
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
            CachedMediaItem(picture("live", Color.rgb(80, 155, 120)), "2", MediaType.IMAGE, width = 800, height = 1600, live = true),
            CachedMediaItem(picture("video", Color.rgb(185, 125, 65)), "3", MediaType.VIDEO, width = 1920, height = 1080, sizeBytes = 1_500_000),
        )
        val saveCalls = AtomicInteger(0)
        val state = mutableStateOf(MainUiState(selectiveDownload = SelectiveDownloadUiState(show = true,
            phase = SelectiveDownloadPhase.Ready, items = items, selectedPaths = setOf(items[1].path))))
        val repository = app.appContainer.settingsRepository
        val originalResolution = runBlocking { repository.awaitReady(); repository.currentSettings.showMediaResolution }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            runBlocking { repository.update { it.copy(showMediaResolution = false) } }
            scenario.onActivity { activity ->
                activity.setContent {
                    MiuixTheme {
                        MiuixComponentHost {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MiuixTheme.colorScheme.surface)
                                    .miuixBackdropSource(LocalMiuixComponentScope.current.pageBackdrop)
                            )
                            SelectiveDownloadSheet(state.value, onCancel = { state.value = MainUiState() }, onSave = { saveCalls.incrementAndGet() }, onToggleItem = { path ->
                                val selection = state.value.selectiveDownload
                                val selected = if (path in selection.selectedPaths) selection.selectedPaths - path else selection.selectedPaths + path
                                state.value = state.value.copy(selectiveDownload = selection.copy(selectedPaths = selected))
                            })
                        }
                    }
                }
            }
            awaitText(app.getString(R.string.selective_type_cover))
            awaitText(app.getString(R.string.selective_type_live))
            awaitText(app.getString(R.string.selective_dimensions, 1200, 800))
            awaitText(android.text.format.Formatter.formatShortFileSize(app, 1_500_000))
            awaitText(app.getString(R.string.selective_download_title, 1, 3))
            click(app.getString(R.string.download_select_all))
            awaitText(app.getString(R.string.selective_download_title, 3, 3))
            val download = app.getString(R.string.download_button)
            val enabledDownloadBounds = android.graphics.Rect()
            awaitAction(download, enabled = true).getBoundsInScreen(enabledDownloadBounds)
            click(app.getString(R.string.download_select_all))
            awaitText(app.getString(R.string.selective_download_title, 0, 3))
            val disabledDownload = awaitAction(download, enabled = false)
            val disabledDownloadBounds = android.graphics.Rect()
            disabledDownload.getBoundsInScreen(disabledDownloadBounds)
            assertEquals(enabledDownloadBounds, disabledDownloadBounds)
            tap(disabledDownload)
            automation.waitForIdle(500, 5_000)
            assertEquals(0, saveCalls.get())
            click(app.getString(R.string.selective_type_cover))
            awaitText(app.getString(R.string.selective_download_title, 1, 3))
            click(app.getString(R.string.selective_type_live))
            awaitText(app.getString(R.string.selective_download_title, 2, 3))
            val coverBounds = android.graphics.Rect()
            val liveBounds = android.graphics.Rect()
            val videoBounds = android.graphics.Rect()
            awaitText(app.getString(R.string.selective_type_cover)).getBoundsInScreen(coverBounds)
            awaitText(app.getString(R.string.selective_type_live)).getBoundsInScreen(liveBounds)
            awaitText(app.getString(R.string.selective_type_video)).getBoundsInScreen(videoBounds)
            assertTrue("The longer lane must be on the left", liveBounds.left < coverBounds.left)
            assertTrue("The left lane must end below the right lane", liveBounds.bottom >= videoBounds.bottom)
            runBlocking { repository.update { it.copy(showMediaResolution = true) } }
            awaitText(app.getString(R.string.selective_dimensions, 1920, 1080))
            assertNull(find(android.text.format.Formatter.formatShortFileSize(app, 1_500_000)))
            runBlocking { repository.update { it.copy(showMediaResolution = false) } }
            awaitText(android.text.format.Formatter.formatShortFileSize(app, 1_500_000))
            Thread.sleep(3_000) // Let the system clipboard toast and checkbox animation finish.
            val screenshot = automation.takeScreenshot()
            File(app.cacheDir, "selection-metadata-ui.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally {
            scenario.close()
            runBlocking { repository.update { it.copy(showMediaResolution = originalResolution) } }
            directory.deleteRecursively()
        }
    }

    @Test fun sheetHeaderStaysFixedWhileContentScrollsAndCloseDismisses() {
        val show = mutableStateOf(true)
        val dismissed = AtomicBoolean(false)
        val saved = AtomicBoolean(false)
        val saveEnabled = mutableStateOf(true)
        val selectedAll = AtomicBoolean(false)
        val clickedRow = AtomicInteger(0)
        val scrollState = ScrollState(0)
        val panelCoordinates = AtomicReference<LayoutCoordinates>()
        val viewportCoordinates = AtomicReference<LayoutCoordinates>()
        val closeCoordinates = AtomicReference<LayoutCoordinates>()
        val selectAllCoordinates = AtomicReference<LayoutCoordinates>()
        val saveCoordinates = AtomicReference<LayoutCoordinates>()
        val topPaddingPx = AtomicReference(0f)
        val bottomPaddingPx = AtomicReference(0f)
        val actionSpacingPx = AtomicReference(0f)
        val actionGapPx = AtomicReference(0f)
        val cornerCenterPx = AtomicReference(0f)
        val useGlass = AtomicBoolean(false)
        val title = app.getString(R.string.selective_download_title, 13, 13)
        val close = app.getString(R.string.cancel)
        val save = app.getString(R.string.download_button)
        val selectAll = app.getString(R.string.download_select_all)
        val firstRow = "Sheet regression row 1"
        val lastRow = "Sheet regression row 30"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun currentBounds(coordinatesReference: AtomicReference<LayoutCoordinates>): RectF {
            var panelBounds: RectF? = null
            scenario.onActivity { activity ->
                val coordinates = checkNotNull(coordinatesReference.get())
                check(coordinates.isAttached)
                val bounds = coordinates.boundsInWindow()
                val windowOrigin = IntArray(2)
                activity.window.decorView.getLocationOnScreen(windowOrigin)
                panelBounds = RectF(bounds.left + windowOrigin[0], bounds.top + windowOrigin[1],
                    bounds.right + windowOrigin[0], bounds.bottom + windowOrigin[1])
            }
            return checkNotNull(panelBounds)
        }
        fun assertConcentricActions() {
            if (!useGlass.get()) return
            var panel = RectF()
            val closeBounds = android.graphics.Rect()
            val saveBounds = android.graphics.Rect()
            val selectAllBounds = android.graphics.Rect()
            val expectedCenter = cornerCenterPx.get()
            var previousSample: List<Float>? = null
            awaitCondition("Sheet header geometry did not settle", pollIntervalMillis = 100) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                val root = automation.rootInActiveWindow?.takeIf { it.refresh() }
                val closeNode = find(close, root)?.takeIf { it.refresh() && it.isVisibleToUser }
                val saveNode = find(save, root)?.takeIf { it.refresh() && it.isVisibleToUser }
                val selectAllNode = find(selectAll, root)?.takeIf { it.refresh() && it.isVisibleToUser }
                if (closeNode == null || saveNode == null || selectAllNode == null) {
                    previousSample = null
                    return@awaitCondition false
                }
                closeNode.getBoundsInScreen(closeBounds)
                saveNode.getBoundsInScreen(saveBounds)
                selectAllNode.getBoundsInScreen(selectAllBounds)
                panel = currentBounds(panelCoordinates)
                val sample = listOf(panel.left, panel.top, panel.right, panel.bottom,
                    closeBounds.exactCenterX(), closeBounds.exactCenterY(),
                    saveBounds.exactCenterX(), saveBounds.exactCenterY(),
                    selectAllBounds.exactCenterX(), selectAllBounds.exactCenterY())
                val centersMatch =
                    abs(closeBounds.exactCenterX() - panel.left - expectedCenter) <= 1.5f &&
                        abs(closeBounds.exactCenterY() - panel.top - expectedCenter) <= 1.5f &&
                        abs(panel.right - saveBounds.exactCenterX() - expectedCenter) <= 1.5f &&
                        abs(saveBounds.exactCenterY() - panel.top - expectedCenter) <= 1.5f &&
                        abs(saveBounds.exactCenterX() - selectAllBounds.exactCenterX() - actionSpacingPx.get()) <= 1.5f
                val settled = centersMatch && sample == previousSample
                previousSample = sample
                settled
            }
            assertEquals("Close horizontal corner center", expectedCenter, closeBounds.exactCenterX() - panel.left, 1.5f)
            assertEquals("Close vertical corner center", expectedCenter, closeBounds.exactCenterY() - panel.top, 1.5f)
            assertEquals("Save horizontal corner center", expectedCenter, panel.right - saveBounds.exactCenterX(), 1.5f)
            assertEquals("Save vertical corner center", expectedCenter, saveBounds.exactCenterY() - panel.top, 1.5f)
            assertEquals("Right action center spacing", actionSpacingPx.get(), saveBounds.exactCenterX() - selectAllBounds.exactCenterX(), 1.5f)
        }
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    MiuixTheme {
                        MiuixComponentHost {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MiuixTheme.colorScheme.surface)
                                    .miuixBackdropSource(LocalMiuixComponentScope.current.pageBackdrop)
                            )
                            val density = LocalDensity.current
                            val scope = LocalMiuixComponentScope.current
                            MiuixBottomSheet(
                                show = show.value,
                                onDismissRequest = { show.value = false },
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    panelCoordinates.set(coordinates)
                                    cornerCenterPx.set(with(density) { 46.dp.toPx() })
                                    actionSpacingPx.set(with(density) { 52.dp.toPx() })
                                    actionGapPx.set(with(density) { 8.dp.toPx() })
                                    useGlass.set(scope.useOs4)
                                },
                                title = stringResource(R.string.selective_download_title, 13, 13),
                                startAction = {
                                    TopAppBarIconButton(
                                        imageVector = MiuixIcons.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                        onClick = { show.value = false },
                                        modifier = Modifier.onGloballyPositioned { closeCoordinates.set(it) }
                                    )
                                },
                                endAction = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TopAppBarIconButton(
                                            imageVector = MiuixIcons.Regular.SelectAll,
                                            contentDescription = stringResource(R.string.download_select_all),
                                            onClick = {
                                                selectedAll.set(true)
                                                saveEnabled.value = !saveEnabled.value
                                            },
                                            modifier = Modifier.onGloballyPositioned { selectAllCoordinates.set(it) }
                                        )
                                        TopAppBarIconButton(
                                            imageVector = MiuixIcons.Regular.Download,
                                            contentDescription = stringResource(R.string.download_button),
                                            onClick = { saved.set(true) },
                                            modifier = Modifier.onGloballyPositioned { saveCoordinates.set(it) },
                                            enabled = saveEnabled.value
                                        )
                                    }
                                },
                                visualContentOffset = { -scrollState.value.toFloat() },
                                onDismissFinished = { dismissed.set(true) }
                            ) { contentPadding ->
                                Column(
                                    Modifier.fillMaxSize()
                                        .onGloballyPositioned { coordinates ->
                                            viewportCoordinates.set(coordinates)
                                            topPaddingPx.set(with(density) { contentPadding.calculateTopPadding().toPx() })
                                            bottomPaddingPx.set(with(density) { contentPadding.calculateBottomPadding().toPx() })
                                        }
                                        .verticalScroll(scrollState)
                                        .padding(contentPadding)
                                ) {
                                    (1..30).forEach { index ->
                                        Text(
                                            text = "Sheet regression row $index",
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable { clickedRow.set(index) }
                                                .padding(vertical = 20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            awaitText(firstRow)
            automation.waitForIdle(500, 5_000)
            assertNull(find(lastRow))
            val headerBounds = android.graphics.Rect()
            assertConcentricActions()
            awaitText(title).getBoundsInScreen(headerBounds)
            val panelBounds = currentBounds(panelCoordinates)
            awaitAction(close, enabled = true)
            awaitAction(selectAll, enabled = true)
            awaitAction(save, enabled = true)
            val closeActionBounds = currentBounds(closeCoordinates)
            val selectAllActionBounds = currentBounds(selectAllCoordinates)
            val saveActionBounds = currentBounds(saveCoordinates)
            val actionGap = saveActionBounds.left - selectAllActionBounds.right
            assertEquals("Right action gap", actionGapPx.get(), actionGap, 1.5f)
            val titleStartLimit = closeActionBounds.right + actionGap
            val titleEndLimit = selectAllActionBounds.left - actionGap - headerBounds.width()
            assertTrue("Sheet title must fit between its actions", titleEndLimit >= titleStartLimit)
            val expectedTitleLeft = (panelBounds.centerX() - headerBounds.width() / 2f)
                .coerceIn(titleStartLimit, titleEndLimit)
            assertEquals("Sheet title uses the clamped window center", expectedTitleLeft, headerBounds.left.toFloat(), 1.5f)
            val viewportBounds = currentBounds(viewportCoordinates)
            assertEquals("Viewport left edge", panelBounds.left, viewportBounds.left, 1.5f)
            assertEquals("Viewport top edge", panelBounds.top, viewportBounds.top, 1.5f)
            assertEquals("Viewport right edge", panelBounds.right, viewportBounds.right, 1.5f)
            assertEquals("Viewport bottom edge", panelBounds.bottom, viewportBounds.bottom, 1.5f)
            val firstRowBounds = android.graphics.Rect()
            awaitText(firstRow).getBoundsInScreen(firstRowBounds)
            assertTrue("First row must consume the measured header padding",
                firstRowBounds.top >= viewportBounds.top + topPaddingPx.get() - 1.5f)
            tap(awaitText(firstRow))
            awaitCondition("First sheet row did not receive a pointer tap") { clickedRow.get() == 1 }

            awaitCondition("Sheet content did not scroll to the last row") {
                val scrollable = findScrollable()
                if (scrollable == null && find(firstRow) == null && find(lastRow) != null) {
                    true
                } else {
                    scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }
            }
            awaitCondition("Sheet scroll animation did not finish") {
                var settled = false
                scenario.onActivity {
                    settled = !scrollState.isScrollInProgress && scrollState.value == scrollState.maxValue
                }
                settled
            }
            automation.waitForIdle(500, 5_000)
            val screenshot = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "sheet-header-scrolled.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            val scrolledHeaderBounds = android.graphics.Rect()
            assertConcentricActions()
            awaitText(title).getBoundsInScreen(scrolledHeaderBounds)
            assertEquals(headerBounds, scrolledHeaderBounds)
            val lastNode = awaitText(lastRow)
            val lastRowBounds = android.graphics.Rect()
            lastNode.getBoundsInScreen(lastRowBounds)
            val scrolledPanelBounds = currentBounds(panelCoordinates)
            val scrolledViewportBounds = currentBounds(viewportCoordinates)
            Log.i("SheetHeaderTest", "Last row bounds=$lastRowBounds, panel bounds=$scrolledPanelBounds")
            assertTrue("Last row must consume the navigation-safe bottom padding",
                lastRowBounds.bottom <= scrolledViewportBounds.bottom - bottomPaddingPx.get() + 1.5f)
            tap(lastNode)
            awaitCondition("Last sheet row did not receive a pointer tap, row=$lastRowBounds, panel=$scrolledPanelBounds") {
                clickedRow.get() == 30
            }
            val enabledSaveBounds = android.graphics.Rect()
            awaitAction(save, enabled = true).getBoundsInScreen(enabledSaveBounds)
            tap(awaitText(selectAll))
            awaitCondition("Sheet select-all action did not receive a pointer tap") { selectedAll.get() }
            val disabledSave = awaitAction(save, enabled = false)
            val disabledSaveBounds = android.graphics.Rect()
            disabledSave.getBoundsInScreen(disabledSaveBounds)
            assertEquals(enabledSaveBounds, disabledSaveBounds)
            assertConcentricActions()
            val disabledScreenshot = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "selection-disabled-toolbar.png").outputStream().use {
                disabledScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            disabledScreenshot.recycle()
            tap(disabledSave)
            automation.waitForIdle(500, 5_000)
            assertFalse(saved.get())
            assertFalse(dismissed.get())
            tap(awaitText(selectAll))
            val enabledSave = awaitAction(save, enabled = true)
            val restoredSaveBounds = android.graphics.Rect()
            enabledSave.getBoundsInScreen(restoredSaveBounds)
            assertEquals(enabledSaveBounds, restoredSaveBounds)
            tap(enabledSave)
            awaitCondition("Sheet save action did not receive a pointer tap") { saved.get() }
            assertFalse(dismissed.get())
            tap(awaitText(close))
            awaitCondition("Sheet did not finish dismissing") { dismissed.get() && find(title) == null }
        } finally {
            scenario.close()
        }
    }

    private fun awaitAction(text: String, enabled: Boolean): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        var previousBounds: android.graphics.Rect? = null
        awaitCondition("Action did not settle with enabled=$enabled: $text", pollIntervalMillis = 100) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            var node = find(text)
            while (node != null && !node.isClickable && node.isEnabled) node = node.parent
            if (node == null || !node.refresh() || !node.isVisibleToUser || node.isEnabled != enabled) {
                previousBounds = null
                return@awaitCondition false
            }
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val settled = bounds == previousBounds
            previousBounds = bounds
            if (settled) match = node
            settled
        }
        return checkNotNull(match)
    }

    private fun click(text: String) {
        awaitCondition("Selection action did not accept a click: $text") {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.takeIf { it.refresh() && it.isVisibleToUser && it.isEnabled && it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Pointer event was rejected", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser &&
            (node.text?.toString() == text || node.contentDescription?.toString() == text)
        ) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isScrollable && node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        ) return node
        for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
        return null
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        awaitCondition("Missing preview text: $text") {
            node = find(text)
            node != null
        }
        return checkNotNull(node)
    }

    private fun awaitCondition(message: String, pollIntervalMillis: Long = 50, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail(message)
            Thread.sleep(pollIntervalMillis)
        }
    }
}
