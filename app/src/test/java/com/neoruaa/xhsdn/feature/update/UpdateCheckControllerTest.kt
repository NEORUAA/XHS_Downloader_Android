package com.neoruaa.xhsdn.feature.update

import com.neoruaa.xhsdn.data.update.GitHubRelease
import com.neoruaa.xhsdn.data.update.UpdateCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckControllerTest {
    private val available = UpdateCheckResult.Available(GitHubRelease("2.0.0", "Notes", "https://github.com/NEORUAA/XHS_Downloader_Android/releases/tag/2.0.0"))
    private val silentResults = listOf(UpdateCheckResult.UpToDate("1.4.0"), UpdateCheckResult.NoRelease,
        UpdateCheckResult.Failed(UpdateCheckResult.Reason.NETWORK), UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_VERSION))

    @Test fun startupRunsOnlyOnceAndKeepsNonUpdatesSilent() = runTest {
        for (result in silentResults) {
            var requests = 0
            val controller = UpdateCheckController(this) { requests++; result }
            controller.checkOnStartup()
            controller.checkOnStartup()
            runCurrent()
            controller.checkOnStartup()
            runCurrent()
            assertEquals(1, requests)
            assertEquals(UpdateCheckState(), controller.state.value)
        }
    }

    @Test fun startupShowsAnUpdateAndRetainsContentUntilDismissalFinishes() = runTest {
        val controller = UpdateCheckController(this) { available }
        controller.checkOnStartup()
        runCurrent()
        assertTrue(controller.state.value.showDialog)
        controller.dismiss()
        assertFalse(controller.state.value.showDialog)
        assertEquals(available, controller.state.value.result)
        controller.onDismissFinished()
        assertNull(controller.state.value.result)
        controller.checkOnStartup()
        runCurrent()
        assertFalse(controller.state.value.showDialog)
    }

    @Test fun manualChecksShowEveryOutcomeAndCanBeRepeated() = runTest {
        for (result in silentResults + available) {
            var requests = 0
            val controller = UpdateCheckController(this) { requests++; result }
            repeat(2) {
                controller.checkManually()
                assertTrue(controller.state.value.isChecking)
                runCurrent()
                assertTrue(controller.state.value.showDialog)
                assertEquals(result, controller.state.value.result)
                controller.dismiss()
                controller.onDismissFinished()
            }
            assertEquals(2, requests)
        }
    }

    @Test fun aManualRequestSharesTheStartupCallAndShowsItsFailure() = runTest {
        val pending = CompletableDeferred<UpdateCheckResult>()
        var requests = 0
        val controller = UpdateCheckController(this) { requests++; pending.await() }
        controller.checkOnStartup()
        runCurrent()
        controller.checkManually()
        controller.checkManually()
        runCurrent()
        assertEquals(1, requests)
        val failure = UpdateCheckResult.Failed(UpdateCheckResult.Reason.TIMEOUT)
        pending.complete(failure)
        runCurrent()
        assertEquals(UpdateCheckState(result = failure, showDialog = true), controller.state.value)
    }

    @Test fun anOldDismissalCallbackCannotClearANewVisibleResult() = runTest {
        val controller = UpdateCheckController(this) { available }
        controller.checkManually()
        runCurrent()
        controller.dismiss()
        controller.checkManually()
        runCurrent()
        controller.onDismissFinished()
        assertEquals(available, controller.state.value.result)
        assertTrue(controller.state.value.showDialog)
    }
}
