package com.example.gnssandopticalflowapp.util.liquidglass

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

class LiquidTracker(private val view: View) {
    private var velocityTracker: VelocityTracker? = null
    private val liquidHandler = Handler(Looper.getMainLooper())
    private val interpolator = OvershootInterpolator(1.2f)

    fun applyMovement(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> ensureAddMovement(event)
            MotionEvent.ACTION_MOVE -> {
                ensureAddMovement(event)
                val (scaleX, scaleY) = getLiquidScale()
                animateToFinalPosition(scaleX, scaleY)

                liquidHandler.removeCallbacksAndMessages(null)
                liquidHandler.postDelayed(
                    { animateToFinalPosition(1f, 1f) },
                    RESET_DELAY_MS
                )
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                recycle()
                animateToFinalPosition(1f, 1f)
            }
        }
    }

    fun recycle() {
        liquidHandler.removeCallbacksAndMessages(null)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    fun animateScale(scale: Float) {
        animateToFinalPosition(scale, scale)
    }

    fun animateTilt(rotX: Float, rotY: Float) {
        view.animate()
            .rotationX(rotX)
            .rotationY(rotY)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    private fun getLiquidScale(): Pair<Float, Float> {
        val tracker = velocityTracker ?: return 1f to 1f

        tracker.computeCurrentVelocity(1)
        val velocityX = tracker.xVelocity
        val velocityY = tracker.yVelocity
        val absVx = abs(velocityX)
        val absVy = abs(velocityY)
        val stretchFactor = 0.5f

        val (scaleX, scaleY) = if (absVx > absVy) {
            1f + absVx * stretchFactor to 1f - absVx * stretchFactor * 0.5f
        } else {
            1f - absVy * stretchFactor * 0.5f to 1f + absVy * stretchFactor
        }

        return scaleX.coerceIn(0.6f, 1.4f) to scaleY.coerceIn(0.6f, 1.4f)
    }

    private fun ensureAddMovement(event: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
    }

    private fun animateToFinalPosition(scaleX: Float, scaleY: Float) {
        view.animate()
            .scaleX(scaleX)
            .scaleY(scaleY)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    @Suppress("unused")
    private fun getVelocity(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1)
        val velocityX = tracker.xVelocity
        val velocityY = tracker.yVelocity
        return sqrt(velocityX * velocityX + velocityY * velocityY) *
            if (velocityX > 0f) 1f else -1f
    }

    private companion object {
        const val ANIMATION_DURATION_MS = 140L
        const val RESET_DELAY_MS = 200L
    }
}
