package com.neoruaa.xhsdn.feature.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoruaa.xhsdn.BuildConfig
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.data.update.UpdateCheckResult
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun UpdateDialog(controller: UpdateCheckController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    val result = state.result ?: return
    val context = LocalContext.current
    val title = stringResource(when (result) {
        is UpdateCheckResult.Available -> R.string.update_available
        is UpdateCheckResult.UpToDate -> R.string.update_up_to_date
        UpdateCheckResult.NoRelease -> R.string.update_no_release
        is UpdateCheckResult.Failed -> R.string.update_failed
    })
    val summary = when (result) {
        is UpdateCheckResult.Available -> stringResource(R.string.update_versions, BuildConfig.VERSION_NAME, result.release.tag)
        is UpdateCheckResult.UpToDate -> stringResource(R.string.update_versions, BuildConfig.VERSION_NAME, result.latestTag)
        UpdateCheckResult.NoRelease -> stringResource(R.string.update_no_release_hint)
        is UpdateCheckResult.Failed -> when (result.reason) {
            UpdateCheckResult.Reason.NETWORK -> stringResource(R.string.update_error_network)
            UpdateCheckResult.Reason.TIMEOUT -> stringResource(R.string.update_error_timeout)
            UpdateCheckResult.Reason.RATE_LIMITED -> stringResource(R.string.update_error_rate_limit)
            UpdateCheckResult.Reason.HTTP -> stringResource(R.string.update_error_http, result.httpCode)
            UpdateCheckResult.Reason.INVALID_RESPONSE -> stringResource(R.string.update_error_response)
            UpdateCheckResult.Reason.INVALID_VERSION -> stringResource(R.string.update_error_version)
        }
    }
    WindowDialog(
        show = state.showDialog,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = controller::dismiss,
        onDismissFinished = controller::onDismissFinished,
    ) {
        Column(Modifier.heightIn(max = 500.dp)) {
            if (result is UpdateCheckResult.Available) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(
                        text = result.release.notes.ifBlank { stringResource(R.string.update_notes_empty) },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(stringResource(R.string.update_later), controller::dismiss, Modifier.weight(1f))
                    TextButton(stringResource(R.string.update_open_release), {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.release.url)))
                            controller.dismiss()
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
                        } catch (_: SecurityException) {
                            Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            } else {
                TextButton(stringResource(R.string.update_close), controller::dismiss,
                    Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
