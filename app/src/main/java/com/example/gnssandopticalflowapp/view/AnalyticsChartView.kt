package com.example.gnssandopticalflowapp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AnalyticsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Metric {
        FPS,
        CONFIDENCE,
        MAGNITUDE,
        PROCESS_MS,
        TRACKS,
        FLOW_X
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartRect = RectF()
    private val backgroundRect = RectF()
    private var samples: List<AnalyticsSample> = emptyList()
    private var title: String = "Chart"
    private var unit: String = ""
    private var metric: Metric = Metric.FPS
    private var selectedIndex = -1
    private var onSampleSelected: ((Int) -> Unit)? = null
    private var onChartClicked: ((Int) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    var isInteractive: Boolean = true

    private val kltColor = Color.rgb(240, 230, 140)
    private val farnebackColor = Color.rgb(0, 255, 102)
    private val gridColor = Color.argb(46, 255, 255, 255)
    private val textColor = Color.rgb(236, 231, 252)
    private val mutedTextColor = Color.rgb(182, 169, 214)

    fun setData(
        title: String,
        unit: String,
        metric: Metric,
        samples: List<AnalyticsSample>
    ) {
        this.title = title
        this.unit = unit
        this.metric = metric
        this.samples = samples
        selectedIndex = selectedIndex.coerceIn(-1, samples.lastIndex)
        invalidate()
    }

    fun setOnSampleSelectedListener(listener: ((Int) -> Unit)?) {
        onSampleSelected = listener
    }

    fun setOnChartClickedListener(listener: ((Int) -> Unit)?) {
        onChartClicked = listener
    }

    fun setSelectedIndex(index: Int) {
        if (samples.isEmpty()) {
            selectedIndex = -1
            invalidate()
            return
        }

        selectedIndex = index.coerceIn(0, samples.lastIndex)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawTitle(canvas)

        if (samples.isEmpty()) {
            drawEmpty(canvas)
            return
        }

        val leftPad = 46f
        val topPad = 64f
        val rightPad = 18f
        val bottomPad = 44f
        chartRect.set(leftPad, topPad, width - rightPad, height - bottomPad)

        val kltValues = samples.map { metricValue(it, klt = true) }
        val farnebackValues = samples.map { metricValue(it, klt = false) }
        val allValues = (kltValues + farnebackValues).filter { it.isFinite() }
        val maxValue = max(1.0, allValues.maxOrNull() ?: 1.0)
        val minValue = if (metric == Metric.PROCESS_MS) 0.0 else min(0.0, allValues.minOrNull() ?: 0.0)
        val range = max(0.001, maxValue - minValue)

        drawGrid(canvas, minValue, maxValue)
        drawSeries(canvas, kltValues, minValue, range, kltColor)
        drawSeries(canvas, farnebackValues, minValue, range, farnebackColor)
        drawPeak(canvas, kltValues, minValue, range, kltColor)
        drawPeak(canvas, farnebackValues, minValue, range, farnebackColor)
        drawSelection(canvas, kltValues, farnebackValues, minValue, range)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return super.onTouchEvent(event)

        if (!isInteractive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx < 20f && dy < 20f) {
                        val index = getIndexForX(event.x)
                        onChartClicked?.invoke(index)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchSelection(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouchSelection(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                handleTouchSelection(event.x)
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (dx < 20f && dy < 20f) {
                    onChartClicked?.invoke(selectedIndex)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return true
    }

    private fun handleTouchSelection(x: Float) {
        val index = getIndexForX(x)
        if (selectedIndex != index) {
            selectedIndex = index
            invalidate()
            onSampleSelected?.invoke(index)
        }
    }

    private fun getIndexForX(x: Float): Int {
        ensureChartRect()
        if (chartRect.width() <= 0f) return 0
        val ratio = ((x - chartRect.left) / chartRect.width()).coerceIn(0f, 1f)
        return (ratio * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
    }

    private fun drawBackground(canvas: Canvas) {
        backgroundRect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(42, 31, 72), Color.rgb(19, 13, 36), Color.rgb(12, 22, 25)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(backgroundRect, 24f, 24f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = Color.argb(74, 255, 255, 255)
        canvas.drawRoundRect(backgroundRect.insetCopy(1.5f), 23f, 23f, paint)
    }

    private fun drawTitle(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textSize = 16f
        paint.isFakeBoldText = true
        canvas.drawText(title, 20f, 28f, paint)
        canvas.drawText(
            "Time",
            width - 60f,
            height - 20f,
            paint
        )
        paint.isFakeBoldText = false

        drawLegend(canvas, width - 226f, 26f, kltColor, "KLT")
        drawLegend(canvas, width - 146f, 26f, farnebackColor, "Farneback")
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, color: Int, label: String) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawLine(x, y, x + 20f, y, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        paint.textSize = 22f
        paint.color = mutedTextColor
        canvas.drawText(label, x + 26f, y + 4f, paint)
    }

    private fun drawGrid(canvas: Canvas, minValue: Double, maxValue: Double) {
        paint.strokeWidth = 1f
        paint.color = gridColor
        paint.style = Paint.Style.STROKE
        for (i in 0..4) {
            val y = chartRect.top + (chartRect.height() * i / 4f)
            canvas.drawLine(chartRect.left, y, chartRect.right, y, paint)
        }
        for (i in 0..4) {
            val x = chartRect.left + (chartRect.width() * i / 4f)
            canvas.drawLine(x, chartRect.top, x, chartRect.bottom, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = mutedTextColor
        paint.textSize = 9.5f
        canvas.drawText(formatAxis(maxValue), 12f, chartRect.top + 4f, paint)
        canvas.drawText(formatAxis(minValue), 12f, chartRect.bottom, paint)
    }

    private fun drawSeries(
        canvas: Canvas,
        values: List<Double>,
        minValue: Double,
        range: Double,
        color: Int
    ) {
        if (values.isEmpty()) return

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = xForIndex(index, values.size)
            val y = yForValue(value, minValue, range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val fillPath = Path(path)
        fillPath.lineTo(chartRect.right, chartRect.bottom)
        fillPath.lineTo(chartRect.left, chartRect.bottom)
        fillPath.close()
        paint.shader = LinearGradient(
            0f,
            chartRect.top,
            0f,
            chartRect.bottom,
            Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawPath(fillPath, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.2f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawPath(path, paint)

        val pointStep = max(1, values.size / 16)
        paint.style = Paint.Style.FILL
        values.forEachIndexed { index, value ->
            if (index % pointStep == 0 || index == values.lastIndex) {
                canvas.drawCircle(xForIndex(index, values.size), yForValue(value, minValue, range), 3.2f, paint)
            }
        }
    }

    private fun drawPeak(
        canvas: Canvas,
        values: List<Double>,
        minValue: Double,
        range: Double,
        color: Int
    ) {
        val peakIndex = values.indices.maxByOrNull { values[it] } ?: return
        val x = xForIndex(peakIndex, values.size)
        val y = yForValue(values[peakIndex], minValue, range)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(x, y, 8.5f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, 4.5f, paint)
    }

    private fun drawSelection(
        canvas: Canvas,
        kltValues: List<Double>,
        farnebackValues: List<Double>,
        minValue: Double,
        range: Double
    ) {
        val index = selectedIndex
        if (index !in samples.indices || index !in kltValues.indices || index !in farnebackValues.indices) return

        val x = xForIndex(index, samples.size)
        val kltY = yForValue(kltValues[index], minValue, range)
        val farnebackY = yForValue(farnebackValues[index], minValue, range)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(170, 255, 255, 255)
        canvas.drawLine(x, chartRect.top, x, chartRect.bottom, paint)

        paint.style = Paint.Style.FILL
        paint.color = kltColor
        canvas.drawCircle(x, kltY, 6f, paint)
        paint.color = farnebackColor
        canvas.drawCircle(x, farnebackY, 6f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(210, 16, 10, 31)
        val label = "${samples[index].elapsedMs / 1000.0}s"
        val labelWidth = paint.measureText(label) + 16f
        val labelLeft = (x - labelWidth / 2f).coerceIn(chartRect.left, chartRect.right - labelWidth)
        canvas.drawRoundRect(RectF(labelLeft, chartRect.top + 8f, labelLeft + labelWidth, chartRect.top + 32f), 10f, 10f, paint)
        paint.color = textColor
        paint.textSize = 10f
        canvas.drawText(label, labelLeft + 8f, chartRect.top + 24f, paint)
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = mutedTextColor
        paint.textSize = 13f
        canvas.drawText("No samples", 20f, height / 2f, paint)
    }

    private fun metricValue(sample: AnalyticsSample, klt: Boolean): Double {
        return when (metric) {
            Metric.FPS -> if (klt) sample.kltFps else sample.farnebackFps
            Metric.CONFIDENCE -> if (klt) sample.kltConfidence else sample.farnebackConfidence
            Metric.MAGNITUDE -> if (klt) sample.kltAvgMagnitude else sample.farnebackAvgMagnitude
            Metric.PROCESS_MS -> if (klt) sample.kltProcessMs else sample.farnebackProcessMs
            Metric.TRACKS -> if (klt) sample.kltFeatureCount.toDouble() else sample.farnebackActiveVectorCount.toDouble()
            Metric.FLOW_X -> if (klt) sample.kltAvgDx else sample.farnebackAvgDx
        }
    }

    private fun xForIndex(index: Int, size: Int): Float {
        if (size <= 1) return chartRect.left
        return chartRect.left + (chartRect.width() * index / (size - 1).toFloat())
    }

    private fun ensureChartRect() {
        if (chartRect.width() > 0f && chartRect.height() > 0f) return
        chartRect.set(46f, 64f, width - 18f, height - 44f)
    }

    private fun yForValue(value: Double, minValue: Double, range: Double): Float {
        val normalized = ((value - minValue) / range).coerceIn(0.0, 1.0)
        return chartRect.bottom - (chartRect.height() * normalized).toFloat()
    }

    private fun RectF.insetCopy(inset: Float): RectF {
        return RectF(left + inset, top + inset, right - inset, bottom - inset)
    }

    private fun formatAxis(value: Double): String {
        return if (value >= 100) value.roundToInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private fun formatValue(value: Double): String {
        return when {
            metric == Metric.CONFIDENCE -> String.format(Locale.US, "%.0f%%", value)
            metric == Metric.TRACKS -> value.roundToInt().toString()
            value >= 100 -> value.roundToInt().toString()
            else -> String.format(Locale.US, "%.2f %s", value, unit).trim()
        }
    }
}
