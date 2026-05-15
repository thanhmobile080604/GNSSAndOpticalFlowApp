package com.example.gnssandopticalflowapp.util.liquidglass.impl

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.example.gnssandopticalflowapp.util.liquidglass.Config
import kotlin.math.roundToInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation

class FallbackGlassPainter(private val config: Config) {
    private val rect = RectF()
    private val insetRect = RectF()
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val radius = config.CORNER_RADIUS_PX.coerceIn(0f, minOf(width, height) / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.withClip(path) {
            drawSoftMilk(canvas, width, height)
            drawTint(canvas)
            drawTopSheen(canvas, width, height)
            drawInnerDepth(canvas, width, height)
        }

        drawChromaticRim(canvas, radius)
        drawGlassBorder(canvas, width, height, radius)
    }

    private fun drawSoftMilk(canvas: Canvas, width: Int, height: Int) {
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                argb(82f, 255, 255, 255),
                argb(34f, 255, 255, 255),
                argb(18f, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawRect(rect, fillPaint)
        fillPaint.shader = null
    }

    private fun drawTint(canvas: Canvas) {
        val tintAlpha = (config.TINT_ALPHA * 120f).coerceIn(0f, 120f)
        if (tintAlpha <= 0.5f) return

        fillPaint.color = Color.argb(
            tintAlpha.roundToInt(),
            (config.TINT_COLOR_RED * 255f).roundToInt().coerceIn(0, 255),
            (config.TINT_COLOR_GREEN * 255f).roundToInt().coerceIn(0, 255),
            (config.TINT_COLOR_BLUE * 255f).roundToInt().coerceIn(0, 255)
        )
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        canvas.drawRect(rect, fillPaint)
    }

    private fun drawTopSheen(canvas: Canvas, width: Int, height: Int) {
        glowPaint.shader = RadialGradient(
            width * 0.18f,
            height * 0.05f,
            maxOf(width, height) * 0.78f,
            intArrayOf(argb(92f, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.style = Paint.Style.FILL
        glowPaint.maskFilter = null
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null
    }

    private fun drawInnerDepth(canvas: Canvas, width: Int, height: Int) {
        glowPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(
                argb(34f, 255, 255, 255),
                Color.TRANSPARENT,
                argb(40f, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.style = Paint.Style.FILL
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null
    }

    private fun drawChromaticRim(canvas: Canvas, radius: Float) {
        val dispersionAlpha = (config.DISPERSION.coerceIn(0f, 1f) * 80f).coerceIn(20f, 80f)
        insetRect.set(rect)
        insetRect.inset(1.1f, 1.1f)

        strokePaint.shader = null
        strokePaint.strokeWidth = 1.35f
        strokePaint.maskFilter = null

        strokePaint.color = argb(dispersionAlpha, 255, 72, 126)
        canvas.withTranslation(-0.75f, -0.45f) {
            drawRoundRect(insetRect, radius, radius, strokePaint)
        }

        strokePaint.color = argb(dispersionAlpha, 54, 192, 255)
        canvas.withTranslation(0.75f, 0.55f) {
            drawRoundRect(insetRect, radius, radius, strokePaint)
        }
    }

    private fun drawGlassBorder(canvas: Canvas, width: Int, height: Int, radius: Float) {
        insetRect.set(rect)
        insetRect.inset(1.6f, 1.6f)

        strokePaint.strokeWidth = 2f
        strokePaint.maskFilter = null
        strokePaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(
                argb(210f, 255, 255, 255),
                argb(80f, 255, 255, 255),
                argb(46f, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(insetRect, radius, radius, strokePaint)

        strokePaint.shader = null
        strokePaint.strokeWidth = 4f
        strokePaint.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        strokePaint.color = argb(42f, 255, 255, 255)
        canvas.drawRoundRect(insetRect, radius, radius, strokePaint)
        strokePaint.maskFilter = null
    }

    private fun argb(alpha: Float, red: Int, green: Int, blue: Int): Int {
        return Color.argb(alpha.roundToInt().coerceIn(0, 255), red, green, blue)
    }
}
