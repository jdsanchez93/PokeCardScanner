package com.jdsanchez.pokecardscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class GraphicOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val graphics = mutableListOf<Graphic>()

    fun clear() {
        graphics.clear()
        postInvalidate()
    }

    fun add(graphic: Graphic) {
        graphics.add(graphic)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        graphics.forEach { it.draw(canvas) }
    }

    abstract class Graphic(private val overlay: GraphicOverlay) {
        abstract fun draw(canvas: Canvas)
    }

    class RectGraphic(overlay: GraphicOverlay, private val rect: Rect, private val text: String) : Graphic(overlay) {
        private val boundingRectPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
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

        private val contentPadding = 25
        private var textWidth = contentTextPaint.measureText(text).toInt()

        override fun draw(canvas: Canvas) {
            canvas.drawRect(rect, boundingRectPaint)
            canvas.drawRect(
                Rect(
                    rect.left,
                    rect.bottom + contentPadding/2,
                    rect.left + textWidth + contentPadding*2,
                    rect.bottom + contentTextPaint.textSize.toInt() + contentPadding),
                contentRectPaint
            )
            canvas.drawText(
                text,
                (rect.left + contentPadding).toFloat(),
                (rect.bottom + contentPadding*2).toFloat(),
                contentTextPaint
            )
        }
    }
}