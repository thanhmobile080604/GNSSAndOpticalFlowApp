package com.example.gnssandopticalflowapp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 203, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private val roiRect = RectF()

    var normalizedRoi: RectF? = null
        private set

    var selectionEnabled = false
        private set

    var onRoiChanged: (() -> Unit)? = null

    fun setSelectionEnabled(enabled: Boolean) {
        selectionEnabled = enabled
        visibility = if (enabled || normalizedRoi != null) VISIBLE else GONE
        invalidate()
    }

    fun clearSelection() {
        normalizedRoi = null
        roiRect.setEmpty()
        visibility = if (selectionEnabled) VISIBLE else GONE
        onRoiChanged?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!selectionEnabled && normalizedRoi == null) return

        val rect = currentRect()
        if (rect == null) {
            canvas.drawColor(Color.argb(40, 0, 0, 0))
            canvas.drawText("Drag to select ROI", 28f, 48f, labelPaint)
            return
        }

        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        canvas.drawRoundRect(rect, 18f, 18f, borderPaint)

        val radius = 8f
        canvas.drawCircle(rect.left, rect.top, radius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, radius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, radius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, radius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = event.x.coerceIn(0f, width.toFloat())
                dragStartY = event.y.coerceIn(0f, height.toFloat())
                normalizedRoi = null
                roiRect.set(dragStartX, dragStartY, dragStartX, dragStartY)
                onRoiChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateRect(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateRect(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (roiRect.width() < MIN_ROI_SIZE_PX || roiRect.height() < MIN_ROI_SIZE_PX) {
                    clearSelection()
                } else {
                    normalizedRoi = RectF(
                        roiRect.left / width.toFloat(),
                        roiRect.top / height.toFloat(),
                        roiRect.right / width.toFloat(),
                        roiRect.bottom / height.toFloat()
                    )
                    onRoiChanged?.invoke()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private fun updateRect(x: Float, y: Float) {
        val endX = x.coerceIn(0f, width.toFloat())
        val endY = y.coerceIn(0f, height.toFloat())
        roiRect.set(
            min(dragStartX, endX),
            min(dragStartY, endY),
            max(dragStartX, endX),
            max(dragStartY, endY)
        )
    }

    private fun currentRect(): RectF? {
        normalizedRoi?.let { normalized ->
            return RectF(
                normalized.left * width,
                normalized.top * height,
                normalized.right * width,
                normalized.bottom * height
            )
        }
        return roiRect.takeIf {
            abs(it.width()) >= MIN_ROI_SIZE_PX && abs(it.height()) >= MIN_ROI_SIZE_PX
        }
    }

    private companion object {
        const val MIN_ROI_SIZE_PX = 48f
    }
}
