package com.example.gnssandopticalflowapp.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var currentScale = MIN_SCALE
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { resetZoom() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                isDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isDragging && currentScale > MIN_SCALE) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = drawMatrix
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = if (event.actionIndex == 0) 1 else 0
                if (pointerIndex < event.pointerCount) {
                    lastX = event.getX(pointerIndex)
                    lastY = event.getY(pointerIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun resetZoom() {
        val image = drawable ?: return
        if (width <= 0 || height <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = image.intrinsicWidth.toFloat()
        val imageHeight = image.intrinsicHeight.toFloat()
        val baseScale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * baseScale) / 2f
        val dy = (viewHeight - imageHeight * baseScale) / 2f

        drawMatrix.reset()
        drawMatrix.postScale(baseScale, baseScale)
        drawMatrix.postTranslate(dx, dy)
        currentScale = MIN_SCALE
        imageMatrix = drawMatrix
    }

    private fun zoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val clampedScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = clampedScale / currentScale
        currentScale = clampedScale
        drawMatrix.postScale(factor, factor, focusX, focusY)
        fixTranslation()
        imageMatrix = drawMatrix
    }

    private fun fixTranslation() {
        val rect = getDisplayRect() ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val dx = when {
            rect.width() <= viewWidth -> (viewWidth - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < viewWidth -> viewWidth - rect.right
            else -> 0f
        }

        val dy = when {
            rect.height() <= viewHeight -> (viewHeight - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < viewHeight -> viewHeight - rect.bottom
            else -> 0f
        }

        drawMatrix.postTranslate(dx, dy)
    }

    private fun getDisplayRect(): RectF? {
        val image = drawable ?: return null
        val rect = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        return rect
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(currentScale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (currentScale <= MIN_SCALE + SCALE_RESET_EPSILON) {
                resetZoom()
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > MIN_SCALE + SCALE_RESET_EPSILON) {
                resetZoom()
            } else {
                zoomTo(DOUBLE_TAP_SCALE, e.x, e.y)
            }
            return true
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val SCALE_RESET_EPSILON = 0.02f
    }
}
