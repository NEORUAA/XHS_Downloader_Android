package com.neoruaa.xhsdn

internal object ImageOrientationUtils {
    const val ORIENTATION_UNDEFINED = 0

    const val ORIENTATION_NORMAL = 1

    const val ORIENTATION_FLIP_HORIZONTAL = 2

    const val ORIENTATION_ROTATE_180 = 3

    const val ORIENTATION_FLIP_VERTICAL = 4

    const val ORIENTATION_TRANSPOSE = 5

    const val ORIENTATION_ROTATE_90 = 6

    const val ORIENTATION_TRANSVERSE = 7

    const val ORIENTATION_ROTATE_270 = 8

    @JvmStatic
    fun swapsWidthAndHeight(orientation: Int): Boolean =
        orientation == ORIENTATION_TRANSPOSE ||
            orientation == ORIENTATION_ROTATE_90 ||
            orientation == ORIENTATION_TRANSVERSE ||
            orientation == ORIENTATION_ROTATE_270

    @JvmStatic
    fun aspectRatio(width: Int, height: Int, orientation: Int): Float? {
        if (width <= 0 || height <= 0) return null
        return if (swapsWidthAndHeight(orientation)) {
            height.toFloat() / width.toFloat()
        } else {
            width.toFloat() / height.toFloat()
        }
    }

    @JvmStatic
    fun rotationDegrees(orientation: Int): Int = when (orientation) {
        ORIENTATION_TRANSPOSE, ORIENTATION_ROTATE_90 -> 90
        ORIENTATION_ROTATE_180, ORIENTATION_FLIP_VERTICAL -> 180
        ORIENTATION_TRANSVERSE, ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
