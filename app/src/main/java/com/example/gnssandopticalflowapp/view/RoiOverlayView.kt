package com.example.gnssandopticalflowapp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    enum class SelectionShape {
        FREEHAND,
        RECTANGLE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 220, 203, 255)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 203, 255)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val drawingPoints = mutableListOf<PointF>()
    private val normalizedPathPoints = mutableListOf<PointF>()
    private val drawPath = Path()
    private val drawingRect = RectF()
    private var rectangleStart: PointF? = null
    private var rectangleEnd: PointF? = null
    private var touchStart: PointF? = null
    private var touchMoved = false

    var normalizedRoi: RectF? = null
        private set

    val normalizedPath: List<PointF>
        get() = normalizedPathPoints.map { point -> PointF(point.x, point.y) }

    var selectionEnabled = false
        private set

    var selectionShape: SelectionShape = SelectionShape.FREEHAND
        set(value) {
            if (field == value) return
            field = value
            clearSelection(notify = true)
        }

    var onRoiChanged: (() -> Unit)? = null
    var onInvalidSelection: (() -> Unit)? = null
    var onSingleTap: (() -> Boolean)? = null

    fun setSelectionEnabled(enabled: Boolean) {
        selectionEnabled = enabled
        visibility = if (enabled || normalizedRoi != null) VISIBLE else GONE
        invalidate()
    }

    fun clearSelection() {
        clearSelection(notify = true)
    }

    fun setNormalizedRoi(rect: RectF?, notify: Boolean = true) {
        normalizedRoi = rect?.let {
            RectF(
                it.left.coerceIn(0f, 1f),
                it.top.coerceIn(0f, 1f),
                it.right.coerceIn(0f, 1f),
                it.bottom.coerceIn(0f, 1f)
            )
        }
        normalizedPathPoints.clear()
        drawingPoints.clear()
        rectangleStart = null
        rectangleEnd = null
        visibility = if (selectionEnabled || normalizedRoi != null) VISIBLE else GONE
        if (notify) onRoiChanged?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!selectionEnabled && normalizedRoi == null) return

        if (selectionShape == SelectionShape.RECTANGLE) {
            drawRectangleSelection(canvas)
            return
        }

        val closedPath = closedSelectionPath()
        if (closedPath != null) {
            drawClosedArea(canvas, closedPath)
            return
        }

        val activePath = activeDrawingPath()
        if (activePath == null) return

        canvas.drawPath(activePath, borderPaint)
        drawingPoints.firstOrNull()?.let { start ->
            canvas.drawCircle(start.x, start.y, 9f, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false

        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        if (selectionShape == SelectionShape.RECTANGLE) {
            return handleRectangleTouch(event, x, y)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStart = PointF(x, y)
                touchMoved = false
                clearSelection(notify = false)
                drawingPoints.add(PointF(x, y))
                onRoiChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateTouchMoved(x, y)
                addDrawingPoint(x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (handleSingleTapIfNeeded(x, y)) return true
                addDrawingPoint(x, y, force = true)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (commitClosedArea()) {
                    onRoiChanged?.invoke()
                } else {
                    clearSelection(notify = true)
                    onInvalidSelection?.invoke()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchStart = null
                clearSelection(notify = true)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleRectangleTouch(event: MotionEvent, x: Float, y: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStart = PointF(x, y)
                touchMoved = false
                clearSelection(notify = false)
                rectangleStart = PointF(x, y)
                rectangleEnd = PointF(x, y)
                onRoiChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateTouchMoved(x, y)
                rectangleEnd = PointF(x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (handleSingleTapIfNeeded(x, y)) return true
                rectangleEnd = PointF(x, y)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (commitRectangle()) {
                    onRoiChanged?.invoke()
                } else {
                    clearSelection(notify = true)
                    onInvalidSelection?.invoke()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchStart = null
                clearSelection(notify = true)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateTouchMoved(x: Float, y: Float) {
        val start = touchStart ?: return
        if (distance(start, x, y) >= tapSlopPx()) {
            touchMoved = true
        }
    }

    private fun handleSingleTapIfNeeded(x: Float, y: Float): Boolean {
        val start = touchStart ?: return false
        val isTap = !touchMoved && distance(start, x, y) < tapSlopPx()
        touchStart = null
        if (!isTap) return false
        val handled = onSingleTap?.invoke() == true
        if (!handled) return false

        parent?.requestDisallowInterceptTouchEvent(false)
        drawingPoints.clear()
        rectangleStart = null
        rectangleEnd = null
        invalidate()
        return true
    }

    private fun addDrawingPoint(x: Float, y: Float, force: Boolean = false) {
        val last = drawingPoints.lastOrNull()
        if (!force && last != null && distance(last, x, y) < minPointDistancePx()) return
        drawingPoints.add(PointF(x, y))
    }

    private fun commitClosedArea(): Boolean {
        if (drawingPoints.size < MIN_PATH_POINTS) return false
        val first = drawingPoints.first()
        val last = drawingPoints.last()
        if (distance(first, last.x, last.y) > closeThresholdPx()) return false

        val bounds = boundsFor(drawingPoints)
        if (bounds.width() < minRoiSizePx() || bounds.height() < minRoiSizePx()) return false

        normalizedRoi = RectF(
            bounds.left / width.toFloat(),
            bounds.top / height.toFloat(),
            bounds.right / width.toFloat(),
            bounds.bottom / height.toFloat()
        )
        normalizedPathPoints.clear()
        normalizedPathPoints.addAll(
            drawingPoints.map { point ->
                PointF(point.x / width.toFloat(), point.y / height.toFloat())
            }
        )
        drawingPoints.clear()
        return true
    }

    private fun commitRectangle(): Boolean {
        val start = rectangleStart ?: return false
        val end = rectangleEnd ?: return false
        val bounds = normalizedBoundsFor(start, end)
        if (bounds.width() < minRoiSizePx() || bounds.height() < minRoiSizePx()) return false

        normalizedRoi = RectF(
            bounds.left / width.toFloat(),
            bounds.top / height.toFloat(),
            bounds.right / width.toFloat(),
            bounds.bottom / height.toFloat()
        )
        normalizedPathPoints.clear()
        rectangleStart = null
        rectangleEnd = null
        return true
    }

    private fun drawRectangleSelection(canvas: Canvas) {
        val selectedRect = normalizedRoi?.let { normalized ->
            RectF(
                normalized.left * width,
                normalized.top * height,
                normalized.right * width,
                normalized.bottom * height
            )
        } ?: activeRectangle()

        if (selectedRect == null) {
            return
        }

        canvas.drawRect(selectedRect, fillPaint)
        canvas.drawRect(selectedRect, borderPaint)
        canvas.drawCircle(selectedRect.left, selectedRect.top, 8f, handlePaint)
    }

    private fun drawClosedArea(canvas: Canvas, path: Path) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, borderPaint)
        normalizedPathPoints.firstOrNull()?.let { normalized ->
            canvas.drawCircle(normalized.x * width, normalized.y * height, 8f, handlePaint)
        }
    }

    private fun activeDrawingPath(): Path? {
        if (drawingPoints.isEmpty()) return null
        drawPath.reset()
        drawPath.moveTo(drawingPoints.first().x, drawingPoints.first().y)
        drawingPoints.drop(1).forEach { point ->
            drawPath.lineTo(point.x, point.y)
        }
        return drawPath
    }

    private fun activeRectangle(): RectF? {
        val start = rectangleStart ?: return null
        val end = rectangleEnd ?: return null
        return normalizedBoundsFor(start, end)
    }

    private fun closedSelectionPath(): Path? {
        if (normalizedPathPoints.isEmpty()) return null
        drawPath.reset()
        val first = normalizedPathPoints.first()
        drawPath.moveTo(first.x * width, first.y * height)
        normalizedPathPoints.drop(1).forEach { point ->
            drawPath.lineTo(point.x * width, point.y * height)
        }
        drawPath.close()
        return drawPath
    }

    private fun clearSelection(notify: Boolean) {
        normalizedRoi = null
        drawingPoints.clear()
        normalizedPathPoints.clear()
        rectangleStart = null
        rectangleEnd = null
        visibility = if (selectionEnabled) VISIBLE else GONE
        if (notify) onRoiChanged?.invoke()
        invalidate()
    }

    private fun boundsFor(points: List<PointF>): RectF {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        points.forEach { point ->
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun normalizedBoundsFor(start: PointF, end: PointF): RectF {
        drawingRect.set(
            min(start.x, end.x),
            min(start.y, end.y),
            max(start.x, end.x),
            max(start.y, end.y)
        )
        return drawingRect
    }

    private fun distance(from: PointF, toX: Float, toY: Float): Float {
        return hypot(from.x - toX, from.y - toY)
    }

    private fun closeThresholdPx(): Float {
        return CLOSE_THRESHOLD_DP * resources.displayMetrics.density
    }

    private fun minPointDistancePx(): Float {
        return MIN_POINT_DISTANCE_DP * resources.displayMetrics.density
    }

    private fun minRoiSizePx(): Float {
        return MIN_ROI_SIZE_DP * resources.displayMetrics.density
    }

    private fun tapSlopPx(): Float {
        return TAP_SLOP_DP * resources.displayMetrics.density
    }

    private companion object {
        const val MIN_PATH_POINTS = 8
        const val MIN_POINT_DISTANCE_DP = 3f
        const val MIN_ROI_SIZE_DP = 48f
        const val CLOSE_THRESHOLD_DP = 44f
        const val TAP_SLOP_DP = 8f
    }
}
