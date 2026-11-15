// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.neoruaa.xhsdn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun CapsuleTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    if (tabs.isEmpty()) return
    val selectedTab = selectedIndex.coerceIn(tabs.indices)
    val trackColor = GlassSegmentedTabRowDefaults.restingTrackColor()
    val indicatorColor = GlassSegmentedTabRowDefaults.indicatorColor()
    val material = GlassSegmentedTabRowDefaults.material()
    val stroke = GlassSegmentedTabRowDefaults.stroke()
    val style = GlassDefaults.style(isSystemInDarkTheme())
    val useGlass = useMiuixOs4() && backdrop != null
    val shadow = GlassShadows.Float
    val capsuleShadow = Shadow(
        radius = (shadow.radius / GlassDefaults.SourceDensity).dp,
        color = shadow.color,
        offset = DpOffset(
            (shadow.offsetX / GlassDefaults.SourceDensity).dp,
            (shadow.offsetY / GlassDefaults.SourceDensity).dp
        )
    )
    val density = LocalDensity.current
    var trackWidth by remember { mutableIntStateOf(0) }
    val gap = with(density) { GlassSegmentedTabRowDefaults.TabGap.toPx() }
    val slotWidth = (trackWidth - gap * (tabs.size - 1)).coerceAtLeast(0f) / tabs.size
    val targetOffset = selectedTab * (slotWidth + gap)
    val indicatorOffset = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }

    LaunchedEffect(targetOffset, slotWidth) {
        if (slotWidth <= 0f) return@LaunchedEffect
        if (indicatorWidth.value <= 0f) {
            indicatorOffset.snapTo(targetOffset)
            indicatorWidth.snapTo(slotWidth)
        } else {
            launch { indicatorOffset.animateTo(targetOffset, GlassMotion.default()) }
            launch { indicatorWidth.animateTo(slotWidth, GlassMotion.default()) }
        }
    }
    // Capsule supplies the sole outline around the unshaded rectangular glass material.
    val glassBody = if (useGlass) {
        Modifier.glass(
            backdrop = checkNotNull(backdrop),
            shape = GlassShape(0.dp),
            style = style,
            material = material,
            shading = false,
            stroke = null
        )
    } else Modifier

    Box(
        modifier = modifier
            .height(GlassSegmentedTabRowDefaults.Height)
            .dropShadow(ContinuousCapsule, capsuleShadow)
            .clip(ContinuousCapsule)
            .then(glassBody)
            .background(if (useGlass) Color.Transparent else trackColor, ContinuousCapsule)
            .border(stroke.width, stroke.color, ContinuousCapsule)
            .padding(GlassSegmentedTabRowDefaults.TrackPadding)
            .onSizeChanged { trackWidth = it.width }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val width = indicatorWidth.value.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(IntOffset(indicatorOffset.value.roundToInt(), 0))
                    }
                }
                .clip(ContinuousCapsule)
                .background(indicatorColor, ContinuousCapsule)
        )
        Row(
            modifier = Modifier.fillMaxHeight().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(GlassSegmentedTabRowDefaults.TabGap)
        ) {
            tabs.forEachIndexed { index, label ->
                key(index) {
                    val selected = index == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(ContinuousCapsule)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = null,
                                indication = null,
                                onClick = { onSelect(index) }
                            )
                            .padding(horizontal = GlassSegmentedTabRowDefaults.TabPaddingHorizontal),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = if (selected) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
