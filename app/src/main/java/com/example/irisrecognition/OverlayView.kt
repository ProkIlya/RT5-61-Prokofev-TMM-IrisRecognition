package com.example.irisrecognition

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var leftEyeRect: RectF? = null
    private var rightEyeRect: RectF? = null
    private var isIdentified: Boolean = false

    private val paintRect = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }

    fun updateEyeRects(left: RectF?, right: RectF?, identified: Boolean) {
        leftEyeRect = left
        rightEyeRect = right
        isIdentified = identified
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        paintRect.color = if (isIdentified) Color.GREEN else Color.RED

        leftEyeRect?.let {
            canvas.drawRect(it, paintRect)
        }
        rightEyeRect?.let {
            canvas.drawRect(it, paintRect)
        }

        if (isIdentified && (leftEyeRect != null || rightEyeRect != null)) {
            val text = "Идентифицирован"
            val textWidth = paintText.measureText(text)
            val x = (width - textWidth) / 2
            canvas.drawText(text, x, 120f, paintText)
        }
    }
}