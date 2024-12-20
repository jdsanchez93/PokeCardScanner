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

    class RectGraphic(overlay: GraphicOverlay, private val rect: Rect) : Graphic(overlay) {
        private val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(rect, paint)
        }
    }
}