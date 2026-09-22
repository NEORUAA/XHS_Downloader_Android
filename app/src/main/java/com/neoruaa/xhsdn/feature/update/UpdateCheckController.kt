package com.neoruaa.xhsdn.feature.update

import com.neoruaa.xhsdn.data.update.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateCheckState(
    val isChecking: Boolean = false,
    val result: UpdateCheckResult? = null,
    val showDialog: Boolean = false,
)

/** One automatic check per process; a manual request also exposes the result of an in-flight check. */
class UpdateCheckController(private val scope: CoroutineScope, private val check: suspend () -> UpdateCheckResult) {
    private val _state = MutableStateFlow(UpdateCheckState())
    val state = _state.asStateFlow()
    private var startupChecked = false
    private var manualRequested = false

    @Synchronized fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        startCheck(manual = false)
    }

    @Synchronized fun checkManually() = startCheck(manual = true)

    private fun startCheck(manual: Boolean) {
        manualRequested = manualRequested || manual
        if (_state.value.isChecking) return
        _state.update { it.copy(isChecking = true) }
        scope.launch {
            val result = try {
                check()
            } catch (cancelled: CancellationException) {
                synchronized(this@UpdateCheckController) {
                    manualRequested = false
                    _state.update { it.copy(isChecking = false) }
                }
                throw cancelled
            } catch (_: Exception) {
                UpdateCheckResult.Failed(UpdateCheckResult.Reason.NETWORK)
            }
            synchronized(this@UpdateCheckController) {
                val show = manualRequested || result is UpdateCheckResult.Available
                manualRequested = false
                _state.value = UpdateCheckState(result = result.takeIf { show }, showDialog = show)
            }
        }
    }

    fun dismiss() { _state.update { it.copy(showDialog = false) } }

    fun onDismissFinished() {
        _state.update { if (it.showDialog) it else it.copy(result = null) }
    }
}
