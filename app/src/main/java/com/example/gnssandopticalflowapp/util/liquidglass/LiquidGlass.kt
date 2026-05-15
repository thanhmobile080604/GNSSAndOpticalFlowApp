package com.example.gnssandopticalflowapp.util.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import com.example.gnssandopticalflowapp.util.liquidglass.impl.Impl
import com.example.gnssandopticalflowapp.util.liquidglass.impl.LiquidGlassImpl

class LiquidGlass @JvmOverloads constructor(
    context: Context,
    private val config: Config = Config(),
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var impl: Impl? = null
    private var target: View? = null
    private var targetObserver: ViewTreeObserver? = null

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        impl?.onPreDraw()
        postInvalidateOnAnimation()
        true
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun init(source: View?) {
        if (target === source && impl != null) {
            updateParameters()
            return
        }

        detachPreDrawListener()
        impl?.dispose()
        impl = null
        target = source

        val renderTarget = source ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl = LiquidGlassImpl(this, renderTarget, config)
            impl?.onSizeChanged(width, height)
            attachPreDrawListener(renderTarget)
        }
        invalidate()
    }

    fun updateParameters() {
        impl?.onPreDraw()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        target?.let(::attachPreDrawListener)
    }

    override fun onDetachedFromWindow() {
        detachPreDrawListener()
        impl?.dispose()
        impl = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        impl?.onSizeChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        impl?.draw(canvas)
    }

    private fun attachPreDrawListener(view: View) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive || targetObserver === observer) return

        detachPreDrawListener()
        observer.addOnPreDrawListener(preDrawListener)
        targetObserver = observer
    }

    private fun detachPreDrawListener() {
        val observer = targetObserver
        if (observer?.isAlive == true) {
            observer.removeOnPreDrawListener(preDrawListener)
        }
        targetObserver = null
    }
}
