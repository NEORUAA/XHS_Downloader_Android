package com.neoruaa.xhsdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOrientationUtilsTest {
    @Test
    fun swapsWidthAndHeightForQuarterTurns() {
        assertTrue(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_ROTATE_90))
        assertTrue(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_ROTATE_270))
        assertTrue(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_TRANSPOSE))
        assertTrue(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_TRANSVERSE))
    }

    @Test
    fun swapsWidthAndHeightForOtherOrientationsIsFalse() {
        assertFalse(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_UNDEFINED))
        assertFalse(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_NORMAL))
        assertFalse(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_FLIP_HORIZONTAL))
        assertFalse(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_ROTATE_180))
        assertFalse(ImageOrientationUtils.swapsWidthAndHeight(ImageOrientationUtils.ORIENTATION_FLIP_VERTICAL))
    }

    @Test
    fun rotationDegreesMatchesExifSemantics() {
        assertEquals(0, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_NORMAL))
        assertEquals(90, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_ROTATE_90))
        assertEquals(180, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_ROTATE_180))
        assertEquals(270, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_ROTATE_270))
        assertEquals(90, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_TRANSPOSE))
        assertEquals(270, ImageOrientationUtils.rotationDegrees(ImageOrientationUtils.ORIENTATION_TRANSVERSE))
    }
}
