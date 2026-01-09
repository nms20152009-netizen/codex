package com.example.facetracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint().apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val rects = mutableListOf<RectF>()
    private val transform = Matrix()

    fun updateFaces(
        faces: List<RectF>,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ) {
        rects.clear()
        rects.addAll(faces)
        updateTransform(imageWidth, imageHeight, rotationDegrees)
        postInvalidateOnAnimation()
    }

    fun clearFaces() {
        rects.clear()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) return
        for (rect in rects) {
            val mapped = RectF(rect)
            transform.mapRect(mapped)
            canvas.drawRect(mapped, paint)
        }
    }

    private fun updateTransform(imageWidth: Int, imageHeight: Int, rotationDegrees: Int) {
        transform.reset()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        val rotatedWidth = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
        val rotatedHeight = if (rotationDegrees % 180 == 0) imageHeight else imageWidth

        val scale = max(viewWidth / rotatedWidth.toFloat(), viewHeight / rotatedHeight.toFloat())
        transform.postScale(scale, scale)
        val dx = (viewWidth - rotatedWidth * scale) / 2f
        val dy = (viewHeight - rotatedHeight * scale) / 2f
        transform.postTranslate(dx, dy)
    }
}
