package com.neoruaa.xhsdn.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neoruaa.xhsdn.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PlainTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DefaultStoragePreference(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var lineHeightPx by remember { mutableIntStateOf(0) }
    val summaryStyle = MiuixTheme.textStyles.body2
    val helpSize = with(density) {
        if (lineHeightPx > 0) lineHeightPx.toDp() else summaryStyle.fontSize.toDp()
    } * 0.8f
    val helpDescription = stringResource(R.string.settings_default_storage_help)
    BasicComponent(
        modifier = modifier,
        onClick = onClick,
        endActions = {
            Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        },
    ) {
        Text(stringResource(R.string.storage_location),
            fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_default_storage_active),
                modifier = Modifier.weight(1f, fill = false),
                fontSize = summaryStyle.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                onTextLayout = { lineHeightPx = (it.getLineBottom(0) - it.getLineTop(0)).toInt() })
            TooltipBox(
                modifier = Modifier.padding(start = 6.dp),
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                state = tooltip,
                focusable = true,
                tooltip = {
                    PlainTooltip(maxWidth = 320.dp) {
                        Text(stringResource(R.string.default_save_path_info), fontSize = summaryStyle.fontSize)
                    }
                },
            ) {
                Box(Modifier.size(helpSize).clip(CircleShape)
                    .background(colorResource(R.color.account_avatar_background))
                    .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                    .semantics { contentDescription = helpDescription },
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.settings_help_symbol), color = Color.White,
                        fontSize = summaryStyle.fontSize * 0.8f, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
