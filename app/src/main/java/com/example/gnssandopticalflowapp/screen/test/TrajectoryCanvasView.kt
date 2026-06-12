package com.example.gnssandopticalflowapp.screen.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Canvas vẽ quỹ đạo dead reckoning (đơn vị mét, hệ local: x = đông, y = bắc).
 *
 * Gesture:
 *  - 1 ngón kéo  -> pan (move)
 *  - 2 ngón xoay -> rotate (kèm pinch để zoom)
 *
 * Thread-safety: addPoint/addStopMarker có thể gọi từ UI thread (fragment ticker).
 */
class TrajectoryCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StopMark(val xM: Float, val yM: Float, val index: Int)

    private val pathPoints = ArrayList<PointF>(2048) // meters
    private val stopMarks = ArrayList<StopMark>()
    private val drawPath = Path()

    /** Heading hiện tại (độ, 0 = bắc) để vẽ mũi tên vị trí hiện tại. */
    var currentHeadingDeg: Float = 0f

    /** Tự pan theo điểm mới nhất cho tới khi người dùng chạm vào canvas. */
    private var followCurrent = true

    // ---- View transform (px) ----
    private var scalePxPerMeter = 40f
    private var panX = 0f
    private var panY = 0f
    private var rotationDeg = 0f

    // ---- Gesture state ----
    private var gestureMode = MODE_NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastAngle = 0f
    private var lastSpan = 0f

    // ---- Paints ----
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255)
        strokeWidth = 1f
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(123, 92, 255)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 217, 100)
        style = Paint.Style.FILL
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 69, 58)
        style = Paint.Style.FILL
    }
    private val stopRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowPath = Path()

    // ================== Public API ==================

    fun reset() {
        pathPoints.clear()
        stopMarks.clear()
        panX = 0f
        panY = 0f
        rotationDeg = 0f
        followCurrent = true
        currentHeadingDeg = 0f
        invalidate()
    }

    fun addPoint(xMeters: Float, yMeters: Float) {
        pathPoints.add(PointF(xMeters, yMeters))
        if (followCurrent) centerOn(xMeters, yMeters)
        invalidate()
    }

    fun addStopMarker(xMeters: Float, yMeters: Float) {
        stopMarks.add(StopMark(xMeters, yMeters, stopMarks.size + 1))
        invalidate()
    }

    fun stopCount(): Int = stopMarks.size

    fun recenter() {
        followCurrent = true
        rotationDeg = 0f
        pathPoints.lastOrNull()?.let { centerOn(it.x, it.y) } ?: run {
            panX = 0f
            panY = 0f
        }
        invalidate()
    }

    private fun centerOn(xMeters: Float, yMeters: Float) {
        // Pan sao cho điểm (xM, yM) nằm giữa view (sau khi đã rotate quanh tâm)
        panX = -xMeters * scalePxPerMeter
        panY = yMeters * scalePxPerMeter
    }

    // ================== Drawing ==================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(16, 16, 30))

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)
        canvas.translate(panX, panY)

        drawGrid(canvas)
        drawTrajectory(canvas)

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        // Lưới 1m, vẽ trong vùng nhìn thấy quanh tâm hiện tại
        val gridStepPx = scalePxPerMeter
        if (gridStepPx < 12f) return
        val half = (hypot(width.toFloat(), height.toFloat()) / 2f) + gridStepPx
        val originX = -panX
        val originY = -panY
        var gx = ((originX - half) / gridStepPx).toInt() * gridStepPx
        while (gx <= originX + half) {
            canvas.drawLine(gx, originY - half, gx, originY + half, gridPaint)
            gx += gridStepPx
        }
        var gy = ((originY - half) / gridStepPx).toInt() * gridStepPx
        while (gy <= originY + half) {
            canvas.drawLine(originX - half, gy, originX + half, gy, gridPaint)
            gy += gridStepPx
        }
    }

    private fun drawTrajectory(canvas: Canvas) {
        if (pathPoints.isEmpty()) return

        // World meters -> canvas px: x sang phải, y (bắc) hướng lên => đảo dấu y
        drawPath.reset()
        val first = pathPoints.first()
        drawPath.moveTo(first.x * scalePxPerMeter, -first.y * scalePxPerMeter)
        for (i in 1 until pathPoints.size) {
            val p = pathPoints[i]
            drawPath.lineTo(p.x * scalePxPerMeter, -p.y * scalePxPerMeter)
        }
        canvas.drawPath(drawPath, pathPaint)

        // Điểm xuất phát
        canvas.drawCircle(first.x * scalePxPerMeter, -first.y * scalePxPerMeter, 12f, startPaint)

        // Điểm dừng
        stopMarks.forEach { mark ->
            val sx = mark.xM * scalePxPerMeter
            val sy = -mark.yM * scalePxPerMeter
            canvas.drawCircle(sx, sy, 14f, stopPaint)
            canvas.drawCircle(sx, sy, 14f, stopRingPaint)
            canvas.drawText(
                String.format(Locale.US, "%d", mark.index),
                sx,
                sy - 22f,
                labelPaint
            )
        }

        // Vị trí hiện tại: mũi tên theo heading
        val last = pathPoints.last()
        val lx = last.x * scalePxPerMeter
        val ly = -last.y * scalePxPerMeter
        canvas.save()
        canvas.translate(lx, ly)
        canvas.rotate(currentHeadingDeg)
        arrowPath.reset()
        arrowPath.moveTo(0f, -20f)
        arrowPath.lineTo(13f, 14f)
        arrowPath.lineTo(0f, 6f)
        arrowPath.lineTo(-13f, 14f)
        arrowPath.close()
        canvas.drawPath(arrowPath, currentPaint)
        canvas.restore()
    }

    // ================== Gestures: pan / rotate / pinch ==================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                followCurrent = false
                gestureMode = MODE_PAN
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    gestureMode = MODE_ROTATE
                    lastAngle = angleBetween(event)
                    lastSpan = spanBetween(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    MODE_PAN -> {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        // Pan trong hệ màn hình nhưng transform đang rotate
                        // => quy đổi delta về hệ đã xoay
                        val rad = Math.toRadians(-rotationDeg.toDouble())
                        val cos = Math.cos(rad).toFloat()
                        val sin = Math.sin(rad).toFloat()
                        panX += dx * cos - dy * sin
                        panY += dx * sin + dy * cos
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                    }

                    MODE_ROTATE -> if (event.pointerCount >= 2) {
                        val angle = angleBetween(event)
                        rotationDeg += angle - lastAngle
                        lastAngle = angle

                        val span = spanBetween(event)
                        if (lastSpan > 1f) {
                            val newScale = (scalePxPerMeter * (span / lastSpan))
                                .coerceIn(MIN_SCALE, MAX_SCALE)
                            scalePxPerMeter = newScale
                        }
                        lastSpan = span
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 == 1) {
                    // Còn lại 1 ngón -> quay về pan, lấy tọa độ ngón còn lại
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    gestureMode = MODE_PAN
                    lastTouchX = event.getX(remaining)
                    lastTouchY = event.getY(remaining)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureMode = MODE_NONE
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun angleBetween(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun spanBetween(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy)
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_PAN = 1
        const val MODE_ROTATE = 2
        const val MIN_SCALE = 5f    // 5 px/m  (nhìn xa)
        const val MAX_SCALE = 300f  // 300 px/m (nhìn gần)
    }
}
