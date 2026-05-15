package com.example.gnssandopticalflowapp.util.liquidglass.impl

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.util.liquidglass.Config
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LiquidGlassImpl(
    private val host: View,
    private val target: View,
    private val config: Config
) : Impl {

    private val node = RenderNode("AndroidLiquidGlassView")
    private val targetPosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private val liquidShader = loadAgsl(target.resources, R.raw.liquidglass_effect)
    private var cachedBlurEffect: RenderEffect? = null

    private var lastCornerRadius = Float.NaN
    private var lastEccentricFactor = Float.NaN
    private var lastRefractionHeight = Float.NaN
    private var lastRefractionAmount = Float.NaN
    private var lastContrast = Float.NaN
    private var lastWhitePoint = Float.NaN
    private var lastChromaMultiplier = Float.NaN
    private var lastSigma = Float.NaN
    private var lastChromaticAberration = Float.NaN
    private var lastDepthEffect = Float.NaN
    private var lastBlurLevel = Float.NaN
    private var lastTintRed = Float.NaN
    private var lastTintGreen = Float.NaN
    private var lastTintBlue = Float.NaN
    private var lastTintAlpha = Float.NaN
    private var needsUpdate = true
    private var lastBlurUpdateTime = 0L

    init {
        host.post(::applyRenderEffect)
    }

    override fun onSizeChanged(w: Int, h: Int) {
        node.setPosition(0, 0, w, h)
        record()
        applyRenderEffect()
    }

    override fun onPreDraw() {
        record()

        val cornerRadius = config.CORNER_RADIUS_PX
        val eccentricFactor = config.ECCENTRIC_FACTOR
        val refractionHeight = config.REFRACTION_HEIGHT
        val refractionAmount = config.REFRACTION_OFFSET
        val contrast = config.CONTRAST
        val whitePoint = config.WHITE_POINT
        val chromaMultiplier = config.CHROMA_MULTIPLIER
        val blurLevel = config.BLUR_RADIUS
        val chromaticAberration = config.DISPERSION
        val depthEffect = config.DEPTH_EFFECT
        val tintRed = config.TINT_COLOR_RED
        val tintGreen = config.TINT_COLOR_GREEN
        val tintBlue = config.TINT_COLOR_BLUE
        val tintAlpha = config.TINT_ALPHA

        val paramsChanged = lastCornerRadius != cornerRadius ||
            lastEccentricFactor != eccentricFactor ||
            lastRefractionHeight != refractionHeight ||
            lastRefractionAmount != refractionAmount ||
            lastContrast != contrast ||
            lastWhitePoint != whitePoint ||
            lastChromaMultiplier != chromaMultiplier ||
            lastBlurLevel != blurLevel ||
            lastChromaticAberration != chromaticAberration ||
            lastDepthEffect != depthEffect ||
            lastTintRed != tintRed ||
            lastTintGreen != tintGreen ||
            lastTintBlue != tintBlue ||
            lastTintAlpha != tintAlpha ||
            needsUpdate

        if (paramsChanged) {
            lastCornerRadius = cornerRadius
            lastEccentricFactor = eccentricFactor
            lastRefractionHeight = refractionHeight
            lastRefractionAmount = refractionAmount
            lastContrast = contrast
            lastWhitePoint = whitePoint
            lastChromaMultiplier = chromaMultiplier
            lastBlurLevel = blurLevel
            lastChromaticAberration = chromaticAberration
            lastDepthEffect = depthEffect
            lastTintRed = tintRed
            lastTintGreen = tintGreen
            lastTintBlue = tintBlue
            lastTintAlpha = tintAlpha
            needsUpdate = false
            applyRenderEffect()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        canvas.drawRenderNode(node)
    }

    private fun record() {
        val width = target.width
        val height = target.height
        if (width == 0 || height == 0) return

        val recordingCanvas = node.beginRecording(width, height)
        target.getLocationInWindow(targetPosition)
        host.getLocationInWindow(hostPosition)
        recordingCanvas.translate(
            -(hostPosition[0] - targetPosition[0]).toFloat(),
            -(hostPosition[1] - targetPosition[1]).toFloat()
        )
        target.draw(recordingCanvas)
        node.endRecording()
    }

    private fun applyRenderEffect() {
        val width = target.width
        val height = target.height
        if (width == 0 || height == 0) return

        val cornerRadiusPx = config.CORNER_RADIUS_PX
        val refractionHeight = config.REFRACTION_HEIGHT
        val refractionAmount = config.REFRACTION_OFFSET
        val contrast = config.CONTRAST
        val whitePoint = config.WHITE_POINT
        val chromaMultiplier = config.CHROMA_MULTIPLIER
        val blurLevel = config.BLUR_RADIUS.coerceAtLeast(0f)
        val chromaticAberration = config.DISPERSION
        val depthEffect = config.DEPTH_EFFECT
        val tintRed = config.TINT_COLOR_RED
        val tintGreen = config.TINT_COLOR_GREEN
        val tintBlue = config.TINT_COLOR_BLUE
        val tintAlpha = config.TINT_ALPHA
        val shaderWidth = config.WIDTH.takeIf { it > 0f } ?: host.width.toFloat()
        val shaderHeight = config.HEIGHT.takeIf { it > 0f } ?: host.height.toFloat()

        val contentEffect = buildBlurEffect(blurLevel)

        liquidShader.setFloatUniform("size", floatArrayOf(shaderWidth, shaderHeight))
        liquidShader.setFloatUniform("offset", floatArrayOf(0f, 0f))
        liquidShader.setFloatUniform(
            "cornerRadii",
            floatArrayOf(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx)
        )
        liquidShader.setFloatUniform("refractionHeight", refractionHeight)
        liquidShader.setFloatUniform("refractionAmount", refractionAmount)
        liquidShader.setFloatUniform("depthEffect", depthEffect)
        liquidShader.setFloatUniform("chromaticAberration", chromaticAberration)
        liquidShader.setFloatUniform("contrast", contrast)
        liquidShader.setFloatUniform("whitePoint", whitePoint)
        liquidShader.setFloatUniform("chromaMultiplier", chromaMultiplier)
        liquidShader.setFloatUniform("tintColor", floatArrayOf(tintRed, tintGreen, tintBlue))
        liquidShader.setFloatUniform("tintAlpha", tintAlpha)

        val shaderEffect = RenderEffect.createRuntimeShaderEffect(liquidShader, "content")
        val finalEffect = if (contentEffect != null) {
            RenderEffect.createChainEffect(shaderEffect, contentEffect)
        } else {
            shaderEffect
        }

        node.setRenderEffect(finalEffect)
    }

    private fun buildBlurEffect(blurLevel: Float): RenderEffect? {
        if (blurLevel <= 0.01f) return null

        val now = System.currentTimeMillis()
        val shouldRefresh = cachedBlurEffect == null ||
            kotlin.math.abs(blurLevel - lastSigma) > 0.3f ||
            now - lastBlurUpdateTime > BLUR_REFRESH_INTERVAL_MS

        if (!shouldRefresh) return cachedBlurEffect

        return runCatching {
            RenderEffect.createBlurEffect(blurLevel, blurLevel, Shader.TileMode.CLAMP)
        }.fold(
            onSuccess = {
                cachedBlurEffect = it
                lastSigma = blurLevel
                lastBlurUpdateTime = now
                it
            },
            onFailure = { cachedBlurEffect }
        )
    }

    private fun loadAgsl(resources: Resources, resourceId: Int): RuntimeShader {
        return RuntimeShader(loadRaw(resources, resourceId))
    }

    private fun loadRaw(resources: Resources, resourceId: Int): String {
        return try {
            resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
        } catch (exception: IOException) {
            throw IllegalStateException("Error loading shader: $resourceId", exception)
        }
    }

    private companion object {
        const val BLUR_REFRESH_INTERVAL_MS = 120L
    }
}
