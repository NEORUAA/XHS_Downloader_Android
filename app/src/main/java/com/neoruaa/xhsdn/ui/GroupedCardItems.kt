package com.neoruaa.xhsdn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun <T> LazyListScope.groupedCardItems(
    items: List<T>,
    key: (T) -> Any,
    bottomPadding: Dp = 12.dp,
    itemContent: @androidx.compose.runtime.Composable (T) -> Unit
) {
    items.forEachIndexed { index, entry ->
        item(key = key(entry)) {
            val isFirst = index == 0
            val isLast = index == items.lastIndex
            val color = MiuixTheme.colorScheme.surfaceContainer
            val segmentModifier = when {
                items.size == 1 -> Modifier.squircleSurface(color, 16.dp)
                isFirst -> Modifier.squircleSurface(
                    color = color,
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomEnd = 0.dp,
                    bottomStart = 0.dp
                )
                isLast -> Modifier.squircleSurface(
                    color = color,
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomEnd = 16.dp,
                    bottomStart = 16.dp
                )
                else -> Modifier.background(color)
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = if (isLast) bottomPadding else 0.dp)
                    .fillMaxWidth()
                    .then(segmentModifier)
            ) {
                itemContent(entry)
            }
        }
    }
}
