package com.example.gnssandopticalflowapp.util.liquidglass.impl

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.example.gnssandopticalflowapp.util.liquidglass.Config
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.S)
class RenderEffectFallbackImpl(
    private val host: View,
    private val target: View,
    private val config: Config
) : Impl {
    private val node = RenderNode("AndroidLiquidGlassFallback")
    private val targetPosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private val painter = FallbackGlassPainter(config)
    private var cachedBlurEffect: RenderEffect? = null
    private var lastBlurRadius = Float.NaN

    init {
        host.post(::applyBlurEffect)
    }

    override fun onSizeChanged(w: Int, h: Int) {
        node.setPosition(0, 0, w, h)
        record()
        applyBlurEffect()
    }

    override fun onPreDraw() {
        record()
        applyBlurEffect()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            canvas.drawRenderNode(node)
        }
        painter.draw(canvas, host.width, host.height)
    }

    private fun record() {
        val width = host.width
        val height = host.height
        if (width <= 0 || height <= 0 || target.width <= 0 || target.height <= 0) return

        val recordingCanvas = node.beginRecording(width, height)
        target.getLocationInWindow(targetPosition)
        host.getLocationInWindow(hostPosition)
        recordingCanvas.translate(
            (targetPosition[0] - hostPosition[0]).toFloat(),
            (targetPosition[1] - hostPosition[1]).toFloat()
        )
        target.draw(recordingCanvas)
        node.endRecording()
    }

    private fun applyBlurEffect() {
        val blurRadius = effectiveBlurRadius()
        if (cachedBlurEffect == null || abs(blurRadius - lastBlurRadius) > 0.3f) {
            cachedBlurEffect = RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                Shader.TileMode.CLAMP
            )
            lastBlurRadius = blurRadius
        }
        node.setRenderEffect(cachedBlurEffect)
    }

    private fun effectiveBlurRadius(): Float {
        return if (config.BLUR_RADIUS > MIN_CONFIGURED_BLUR) {
            config.BLUR_RADIUS
        } else {
            DEFAULT_FALLBACK_BLUR
        }.coerceIn(2f, 32f)
    }

    private companion object {
        const val MIN_CONFIGURED_BLUR = 0.01f
        const val DEFAULT_FALLBACK_BLUR = 18f
    }
}
