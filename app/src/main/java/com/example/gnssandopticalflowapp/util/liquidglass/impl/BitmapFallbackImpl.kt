package com.example.gnssandopticalflowapp.util.liquidglass.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.example.gnssandopticalflowapp.util.liquidglass.Config
import kotlin.math.ceil
import kotlin.math.roundToInt

class BitmapFallbackImpl(
    private val host: View,
    private val target: View,
    private val config: Config
) : Impl {
    private val targetPosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private val destinationRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val painter = FallbackGlassPainter(config)

    private var capturedBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var lastRecordTime = 0L

    override fun onSizeChanged(w: Int, h: Int) {
        ensureBitmaps(w, h)
        record(force = true)
    }

    override fun onPreDraw() {
        record(force = false)
    }

    override fun draw(canvas: Canvas) {
        blurredBitmap?.let { bitmap ->
            destinationRect.set(0f, 0f, host.width.toFloat(), host.height.toFloat())
            canvas.drawBitmap(bitmap, null, destinationRect, bitmapPaint)
        }
        painter.draw(canvas, host.width, host.height)
    }

    override fun dispose() {
        capturedBitmap?.recycle()
        blurredBitmap?.recycle()
        capturedBitmap = null
        blurredBitmap = null
        bitmapCanvas = null
    }

    private fun record(force: Boolean) {
        val width = host.width
        val height = host.height
        if (width <= 0 || height <= 0 || target.width <= 0 || target.height <= 0) return

        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRecordTime < FRAME_THROTTLE_MS && blurredBitmap != null) return
        lastRecordTime = now

        ensureBitmaps(width, height)
        val capture = capturedBitmap ?: return
        val canvas = bitmapCanvas ?: return
        capture.eraseColor(Color.TRANSPARENT)

        target.getLocationInWindow(targetPosition)
        host.getLocationInWindow(hostPosition)

        val save = canvas.save()
        canvas.scale(DOWNSAMPLE, DOWNSAMPLE)
        canvas.translate(
            (targetPosition[0] - hostPosition[0]).toFloat(),
            (targetPosition[1] - hostPosition[1]).toFloat()
        )
        target.draw(canvas)
        canvas.restoreToCount(save)

        val blurTarget = blurredBitmap ?: return
        blurTarget.eraseColor(Color.TRANSPARENT)
        Canvas(blurTarget).drawBitmap(capture, 0f, 0f, null)
        blurTarget.applyBoxBlur(effectiveBlurRadius())
    }

    private fun ensureBitmaps(width: Int, height: Int) {
        val bitmapWidth = ceil(width * DOWNSAMPLE).roundToInt().coerceAtLeast(1)
        val bitmapHeight = ceil(height * DOWNSAMPLE).roundToInt().coerceAtLeast(1)
        val current = capturedBitmap
        if (current != null && current.width == bitmapWidth && current.height == bitmapHeight) return

        dispose()
        capturedBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        blurredBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(capturedBitmap!!)
    }

    private fun effectiveBlurRadius(): Int {
        val blurRadius = if (config.BLUR_RADIUS > MIN_CONFIGURED_BLUR) {
            config.BLUR_RADIUS
        } else {
            DEFAULT_FALLBACK_BLUR
        }
        return (blurRadius * DOWNSAMPLE).roundToInt().coerceIn(2, 14)
    }

    private fun Bitmap.applyBoxBlur(radius: Int) {
        if (radius <= 0 || width <= 1 || height <= 1) return

        var pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) {
            pixels = boxBlur(pixels, width, height, radius)
        }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(source.size)
        val output = IntArray(source.size)
        val windowSize = radius * 2 + 1

        for (y in 0 until height) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            val row = y * width

            for (offset in -radius..radius) {
                val color = source[row + offset.coerceIn(0, width - 1)]
                alpha += color ushr 24
                red += color shr 16 and 0xff
                green += color shr 8 and 0xff
                blue += color and 0xff
            }

            for (x in 0 until width) {
                horizontal[row + x] = Color.argb(
                    alpha / windowSize,
                    red / windowSize,
                    green / windowSize,
                    blue / windowSize
                )

                val removeColor = source[row + (x - radius).coerceIn(0, width - 1)]
                val addColor = source[row + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += (addColor ushr 24) - (removeColor ushr 24)
                red += (addColor shr 16 and 0xff) - (removeColor shr 16 and 0xff)
                green += (addColor shr 8 and 0xff) - (removeColor shr 8 and 0xff)
                blue += (addColor and 0xff) - (removeColor and 0xff)
            }
        }

        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0

            for (offset in -radius..radius) {
                val color = horizontal[offset.coerceIn(0, height - 1) * width + x]
                alpha += color ushr 24
                red += color shr 16 and 0xff
                green += color shr 8 and 0xff
                blue += color and 0xff
            }

            for (y in 0 until height) {
                output[y * width + x] = Color.argb(
                    alpha / windowSize,
                    red / windowSize,
                    green / windowSize,
                    blue / windowSize
                )

                val removeColor = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val addColor = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += (addColor ushr 24) - (removeColor ushr 24)
                red += (addColor shr 16 and 0xff) - (removeColor shr 16 and 0xff)
                green += (addColor shr 8 and 0xff) - (removeColor shr 8 and 0xff)
                blue += (addColor and 0xff) - (removeColor and 0xff)
            }
        }

        return output
    }

    private companion object {
        const val DOWNSAMPLE = 0.25f
        const val FRAME_THROTTLE_MS = 48L
        const val BLUR_PASSES = 2
        const val MIN_CONFIGURED_BLUR = 0.01f
        const val DEFAULT_FALLBACK_BLUR = 18f
    }
}
