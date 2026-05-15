package com.example.gnssandopticalflowapp.util.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.example.gnssandopticalflowapp.util.liquidglass.Config
import com.example.gnssandopticalflowapp.util.liquidglass.LiquidGlass
import com.example.gnssandopticalflowapp.util.liquidglass.LiquidTracker
import com.example.gnssandopticalflowapp.util.liquidglass.Utils
import androidx.core.graphics.withClip

class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var glass: LiquidGlass? = null
    private var customSource: ViewGroup? = null
    private var cornerRadius = Utils.dp2px(resources, 40f)
    private var refractionHeight = Utils.dp2px(resources, 20f)
    private var refractionOffset = -Utils.dp2px(resources, 70f)
    private var tintAlpha = 0f
    private var tintColorRed = 1f
    private var tintColorGreen = 1f
    private var tintColorBlue = 1f
    private var blurRadius = 0.01f
    private var dispersion = 0.5f
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f
    private var draggableEnabled = false
    private var elasticEnabled = false
    private var touchEffectEnabled = false
    private var config: Config? = null
    private val liquidTracker = LiquidTracker(this)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var glowX = 0f
    private var glowY = 0f
    private var isTouching = false

    private val clipPath = Path()
    private val clipRect = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        clipToPadding = false
        clipChildren = false
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.withClip(clipPath) {
            super.dispatchDraw(canvas)
        }

        if (touchEffectEnabled && isTouching) {
            canvas.withClip(clipPath) {
                val radius = width.coerceAtLeast(height) * 0.8f
                glowPaint.shader = RadialGradient(
                    glowX,
                    glowY,
                    radius,
                    intArrayOf(Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                drawRect(clipRect, glowPaint)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        forEachVisibleChild { child ->
            child.layout(
                paddingLeft,
                paddingTop,
                paddingLeft + child.measuredWidth,
                paddingTop + child.measuredHeight
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        forEachVisibleChild { child ->
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun bind(source: ViewGroup?) {
        customSource = source
        if (!isInEditMode && glass != null && source != null) {
            glass?.init(source)
        }
    }

    fun setCornerRadius(px: Float) {
        val maxPx = if (height > 0) height / 2f else Utils.dp2px(resources, 99f)
        cornerRadius = px.coerceIn(0f, maxPx)
        invalidate()
        updateConfig()
    }

    fun setRefractionHeight(px: Float) {
        val minPx = Utils.dp2px(resources, 12f)
        val maxPx = Utils.dp2px(resources, 50f)
        refractionHeight = px.coerceIn(minPx, maxPx)
        updateConfig()
    }

    fun setRefractionOffset(px: Float) {
        val minPx = Utils.dp2px(resources, 20f)
        val maxPx = Utils.dp2px(resources, 120f)
        refractionOffset = -px.coerceIn(minPx, maxPx)
        updateConfig()
    }

    fun setTintColorRed(red: Float) {
        tintColorRed = red
        updateConfig()
    }

    fun setTintColorGreen(green: Float) {
        tintColorGreen = green
        updateConfig()
    }

    fun setTintColorBlue(blue: Float) {
        tintColorBlue = blue
        updateConfig()
    }

    fun setTintAlpha(alpha: Float) {
        tintAlpha = alpha
        updateConfig()
    }

    fun setDispersion(dispersion: Float) {
        this.dispersion = dispersion.coerceIn(0f, 1f)
        updateConfig()
    }

    fun setBlurRadius(radius: Float) {
        blurRadius = radius.coerceIn(0.01f, 50f)
        updateConfig()
    }

    fun setDraggableEnabled(enabled: Boolean) {
        draggableEnabled = enabled
        if (!enabled) {
            liquidTracker.recycle()
        }
    }

    fun setElasticEnabled(enabled: Boolean) {
        elasticEnabled = enabled
        if (!enabled) {
            liquidTracker.recycle()
        }
    }

    fun setTouchEffectEnabled(enabled: Boolean) {
        touchEffectEnabled = enabled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isInEditMode) return
        post(::ensureGlass)
    }

    override fun onDetachedFromWindow() {
        removeGlass()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isInEditMode) return
        if ((w != oldw || h != oldh) && w > 0 && h > 0) {
            val maxPx = h / 2f
            if (cornerRadius > maxPx) {
                cornerRadius = maxPx
            }
            rebuild()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!draggableEnabled && !touchEffectEnabled) return super.onTouchEvent(event)
        if (elasticEnabled) liquidTracker.applyMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (touchEffectEnabled) {
                    isTouching = true
                    liquidTracker.animateScale(1.02f)
                    glowX = event.x
                    glowY = event.y
                    invalidate()
                }

                if (draggableEnabled) {
                    downX = event.rawX
                    downY = event.rawY
                    startTx = translationX
                    startTy = translationY
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchEffectEnabled) {
                    glowX = event.x
                    glowY = event.y
                    invalidate()
                }

                if (draggableEnabled) {
                    dragWithinParent(event)
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (touchEffectEnabled) {
                    isTouching = false
                    liquidTracker.animateScale(1f)
                    invalidate()
                }
                if (draggableEnabled) return true
            }
        }

        val superResult = super.onTouchEvent(event)
        return touchEffectEnabled || superResult
    }

    private fun updateConfig() {
        if (isInEditMode) {
            invalidate()
            return
        }

        val activeConfig = config
        if (activeConfig == null || glass == null) {
            rebuild()
            return
        }

        val viewWidth = width.takeIf { it > 0 } ?: Utils.getDeviceWidthPx(context)
        val viewHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        activeConfig.CORNER_RADIUS_PX = cornerRadius
        activeConfig.REFRACTION_HEIGHT = refractionHeight
        activeConfig.REFRACTION_OFFSET = refractionOffset
        activeConfig.BLUR_RADIUS = blurRadius
        activeConfig.WIDTH = viewWidth.toFloat()
        activeConfig.HEIGHT = viewHeight.toFloat()
        activeConfig.DISPERSION = dispersion
        activeConfig.TINT_ALPHA = tintAlpha
        activeConfig.TINT_COLOR_BLUE = tintColorBlue
        activeConfig.TINT_COLOR_GREEN = tintColorGreen
        activeConfig.TINT_COLOR_RED = tintColorRed

        glass?.post { glass?.updateParameters() }
    }

    private fun rebuild() {
        if (isInEditMode) return
        removeGlass()
        post(::ensureGlass)
    }

    private fun ensureGlass() {
        if (isInEditMode) return
        if (glass != null) return

        val viewWidth = width.takeIf { it > 0 } ?: Utils.getDeviceWidthPx(context)
        val viewHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val nextConfig = Config().configure(
            Config.Overrides()
                .noFilter()
                .contrast(0f)
                .whitePoint(0f)
                .chromaMultiplier(1f)
                .blurRadius(blurRadius)
                .cornerRadius(cornerRadius)
                .refractionHeight(refractionHeight)
                .refractionOffset(refractionOffset)
                .tintAlpha(tintAlpha)
                .tintColorRed(tintColorRed)
                .tintColorGreen(tintColorGreen)
                .tintColorBlue(tintColorBlue)
                .dispersion(dispersion)
                .size(viewWidth, viewHeight)
        )
        config = nextConfig

        val nextGlass = LiquidGlass(context, nextConfig)
        glass = nextGlass
        addView(
            nextGlass,
            0,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )

        val source = customSource ?: (parent as? ViewGroup)
        if (source != null) {
            nextGlass.init(source)
        }
    }

    private fun removeGlass() {
        glass?.let(::removeView)
        glass = null
    }

    private fun dragWithinParent(event: MotionEvent) {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        var tx = startTx + dx
        var ty = startTy + dy

        val parentView = parent as? ViewGroup
        if (parentView != null) {
            val parentWidth = parentView.width
            val parentHeight = parentView.height
            val viewWidth = width
            val viewHeight = height
            if (parentWidth > 0 && parentHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                tx = tx.coerceIn(-left.toFloat(), (parentWidth - left - viewWidth).toFloat())
                ty = ty.coerceIn(-top.toFloat(), (parentHeight - top - viewHeight).toFloat())
            }
        }

        translationX = tx
        translationY = ty
    }

    private inline fun forEachVisibleChild(block: (View) -> Unit) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != GONE) {
                block(child)
            }
        }
    }
}
