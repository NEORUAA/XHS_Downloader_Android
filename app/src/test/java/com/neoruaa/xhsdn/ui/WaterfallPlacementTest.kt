package com.neoruaa.xhsdn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterfallPlacementTest {
    @Test
    fun `equal placeholders populate both lanes immediately`() {
        val placement = calculateBalancedWaterfallPlacement(
            itemHeights = listOf(100, 100, 100, 100),
            spacing = 10
        )

        assertEquals(listOf(0, 1, 0, 1), placement.lanes)
        assertEquals(listOf(0, 0, 110, 110), placement.yOffsets)
        assertEquals(210, placement.leftHeight)
        assertEquals(210, placement.rightHeight)
    }

    @Test
    fun `items are assigned to the shorter lane using measured heights`() {
        val placement = calculateBalancedWaterfallPlacement(
            itemHeights = listOf(100, 200, 50, 80),
            spacing = 10
        )

        assertEquals(listOf(0, 1, 0, 0), placement.lanes)
        assertEquals(listOf(0, 0, 110, 170), placement.yOffsets)
        assertTrue(placement.leftHeight > placement.rightHeight)
    }

    @Test
    fun `longer logical right lane is displayed on the physical left`() {
        val placement = calculateBalancedWaterfallPlacement(listOf(100, 250, 80), 10)
        assertTrue(placement.swapVisualLanes)
        val visualBottoms = intArrayOf(0, 0)
        listOf(100, 250, 80).forEachIndexed { index, height ->
            val lane = if (placement.swapVisualLanes) 1 - placement.lanes[index] else placement.lanes[index]
            visualBottoms[lane] = placement.yOffsets[index] + height
        }
        assertTrue(visualBottoms[0] >= visualBottoms[1])
    }

    @Test
    fun `empty input has no placement or height`() {
        val placement = calculateBalancedWaterfallPlacement(emptyList(), spacing = 10)

        assertTrue(placement.lanes.isEmpty())
        assertTrue(placement.yOffsets.isEmpty())
        assertEquals(0, placement.leftHeight)
        assertEquals(0, placement.rightHeight)
    }
}
