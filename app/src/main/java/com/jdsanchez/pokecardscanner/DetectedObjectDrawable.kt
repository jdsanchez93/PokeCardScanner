package com.jdsanchez.pokecardscanner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

class DetectedObjectDrawable(detectedObjectViewModel: DetectedObjectViewModel): Drawable() {
    private val boundingRectPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 5F
        alpha = 200
    }

    private val contentRectPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
        alpha = 255
    }

    private val contentTextPaint = Paint().apply {
        color = Color.DKGRAY
        alpha = 255
        textSize = 36F
    }

    private val detectedObjectViewModel = detectedObjectViewModel
    private val contentPadding = 25
    private var textWidth = contentTextPaint.measureText(detectedObjectViewModel.content).toInt()


    override fun draw(canvas: Canvas) {
        canvas.drawRect(detectedObjectViewModel.boundingBox, boundingRectPaint)
        canvas.drawRect(
            Rect(
                detectedObjectViewModel.boundingBox.left,
                detectedObjectViewModel.boundingBox.bottom + contentPadding/2,
                detectedObjectViewModel.boundingBox.left + textWidth + contentPadding*2,
                detectedObjectViewModel.boundingBox.bottom + contentTextPaint.textSize.toInt() + contentPadding),
            contentRectPaint
        )
        canvas.drawText(
            detectedObjectViewModel.content,
            (detectedObjectViewModel.boundingBox.left + contentPadding).toFloat(),
            (detectedObjectViewModel.boundingBox.bottom + contentPadding*2).toFloat(),
            contentTextPaint
        )
    }

    override fun setAlpha(p0: Int) {
        boundingRectPaint.alpha = alpha
        contentRectPaint.alpha = alpha
        contentTextPaint.alpha = alpha    }

    override fun setColorFilter(p0: ColorFilter?) {
        boundingRectPaint.colorFilter = colorFilter
        contentRectPaint.colorFilter = colorFilter
        contentTextPaint.colorFilter = colorFilter    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

}