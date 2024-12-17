package com.jdsanchez.pokecardscanner.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect

/**
 * Utility class for manipulating images.
 */
object ImageUtils {
    fun rotateAndCrop(
        bitmap: Bitmap,
        imageRotationDegrees: Int,
        cropRect: Rect
    ): Bitmap {
        val matrix = Matrix()
        matrix.preRotate(imageRotationDegrees.toFloat())
        return Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
            matrix,
            true
        )
    }
}