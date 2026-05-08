package com.example.gnssandopticalflowapp.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.gnssandopticalflowapp.R
import kotlin.math.max

class DotsIndicatorViewRound @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var selectedColor: Int = ContextCompat.getColor(context, R.color.color_957BFE)
    private var unselectedColor: Int = Color.argb(90, 255, 255, 255)
    private var numDots: Int = 3
    private var selectedDot = 0
    private var dotRadius = 12f
    private var dotSpacing = 16f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animator: ValueAnimator? = null
    private var animatedPosition = selectedDot.toFloat()

    init {
        // Set the default attributes if needed
    }

    // Set up number of dots
    fun setDotCount(count: Int) {
        numDots = max(1, count)
        selectedDot = selectedDot.coerceIn(0, numDots - 1)
        animatedPosition = selectedDot.toFloat()
        requestLayout()
        invalidate()
    }

    // Set up colors for selected and unselected dots
    fun setColors(selectedColor: Int, unselectedColor: Int) {
        this.selectedColor = selectedColor
        this.unselectedColor = unselectedColor
        invalidate()
    }

    // Method to animate to the new selected dot
    fun setSelectedDot(newSelectedDot: Int) {
        if (newSelectedDot < 0 || newSelectedDot >= numDots) return

        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedPosition, newSelectedDot.toFloat()).apply {
            duration = 300
            addUpdateListener { animation ->
                animatedPosition = animation.animatedValue as Float
                invalidate() // Trigger a redraw
            }
            start()
        }
        selectedDot = newSelectedDot
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = ((dotRadius * 2 + dotSpacing) * numDots).toInt()
        val height = (dotRadius * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.apply {
            for (i in 0 until numDots) {
                val cx = dotRadius + i * (dotRadius * 2 + dotSpacing)
                val cy = height / 2f
                dotPaint.color =
                    if (i == animatedPosition.toInt()) selectedColor else unselectedColor
                drawCircle(cx, cy, dotRadius, dotPaint)

                // Animate transition effect for the selected dot
                if (i == animatedPosition.toInt() && animatedPosition % 1 != 0f) {
                    dotPaint.color = unselectedColor
                    val nextDotCx =
                        dotRadius + ((animatedPosition.toInt() + 1) * (dotRadius * 2 + dotSpacing))
                    drawCircle(nextDotCx, cy, dotRadius, dotPaint)
                }
            }
        }
    }
}

