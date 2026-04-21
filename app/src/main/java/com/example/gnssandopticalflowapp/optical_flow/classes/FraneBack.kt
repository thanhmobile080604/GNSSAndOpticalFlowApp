package com.example.gnssandopticalflowapp.optical_flow.classes

import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.roundToInt

class FraneBack : OpticalFlow {
    private val scaledPrevGray: Mat = Mat()
    private val scaledCurrGray: Mat = Mat()
    private val flowGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val pyrScale = 0.5
    private var levels = 2
    private var winSize = 13
    private var iterations = 2
    private val polyN = 5
    private val polySigma = 1.1
    private val flags = 0
    private var frameScale = 0.5
    private var drawStep = 28
    private var minMotionMagnitude = 0.5
    private val dotRadius = 6
    private val vectorThickness = 3
    private val vectorLengthMultiplier = 1.8
    private val ofOutput: OFOutput = OFOutput()
    private val flowColor = Scalar(0.0, 255.0, 0.0)

    override fun run(newFrame: Mat): OFOutput {
        Imgproc.cvtColor(newFrame, currGray, Imgproc.COLOR_RGBA2GRAY)
        resizeForFlow(currGray, scaledCurrGray)

        val flowInputSizeChanged =
            scaledPrevGray.rows() != scaledCurrGray.rows() || scaledPrevGray.cols() != scaledCurrGray.cols()
        if (scaledPrevGray.empty() || flowInputSizeChanged) {
            scaledCurrGray.copyTo(scaledPrevGray)
            ofOutput.ofFrame = newFrame
            ofOutput.position = null
            return ofOutput
        }

        Video.calcOpticalFlowFarneback(
            scaledPrevGray,
            scaledCurrGray,
            flowGray,
            pyrScale,
            levels,
            winSize,
            iterations,
            polyN,
            polySigma,
            flags
        )

        val avgMotion = drawOptFlowMap(flowGray, newFrame, drawStep, flowColor)

        scaledCurrGray.copyTo(scaledPrevGray)

        ofOutput.ofFrame = newFrame
        ofOutput.position = avgMotion
        return ofOutput
    }

    override fun resetMotionVector() {
        // TBD
    }

    override fun updateFeatures() {
        // Do nothing
    }

    override fun setSensitivity(value: Int) {
        val normalized = (value.coerceIn(0, 100) / 100.0)
        frameScale = 0.35 + (normalized * 0.35)
        drawStep = (40 - (normalized * 20)).toInt().coerceIn(20, 40)
        levels = if (normalized >= 0.65) 3 else 2
        winSize = (11 + (normalized * 8)).toInt().coerceIn(11, 19)
        iterations = if (normalized >= 0.5) 3 else 2
        minMotionMagnitude = (1.0 - (normalized * 0.7)).coerceIn(0.3, 1.0)
    }

    private fun resizeForFlow(sourceGray: Mat, targetGray: Mat) {
        if (frameScale >= 0.99) {
            sourceGray.copyTo(targetGray)
            return
        }

        Imgproc.resize(
            sourceGray,
            targetGray,
            Size(),
            frameScale,
            frameScale,
            Imgproc.INTER_AREA
        )
    }

    private fun drawOptFlowMap(flow: Mat, flowmap: Mat, step: Int, color: Scalar): Point? {
        if (flow.empty()) return null

        val flowCols = flow.cols().coerceAtLeast(1)
        val flowRows = flow.rows().coerceAtLeast(1)
        val mapCols = flowmap.cols()
        val mapRows = flowmap.rows()
        val xScale = mapCols.toDouble() / flowCols
        val yScale = mapRows.toDouble() / flowRows
        val startX = computeCenteredGridStart(mapCols, step)
        val startY = computeCenteredGridStart(mapRows, step)
        val minMotionSquared = minMotionMagnitude * minMotionMagnitude
        var sumX = 0.0
        var sumY = 0.0
        var sampleCount = 0
        var screenY = startY
        while (screenY < mapRows) {
            var screenX = startX
            while (screenX < mapCols) {
                val flowX = (screenX / xScale).roundToInt().coerceIn(0, flowCols - 1)
                val flowY = (screenY / yScale).roundToInt().coerceIn(0, flowRows - 1)
                val vector = flow.get(flowY, flowX) ?: doubleArrayOf(0.0, 0.0)
                val fx = vector[0] * xScale
                val fy = vector[1] * yScale
                val magnitudeSquared = (fx * fx) + (fy * fy)

                if (magnitudeSquared >= minMotionSquared) {
                    val start = Point(screenX.toDouble(), screenY.toDouble())
                    val end = Point(
                        start.x + (fx * vectorLengthMultiplier),
                        start.y + (fy * vectorLengthMultiplier)
                    )

                    Imgproc.line(flowmap, start, end, color, vectorThickness)
                    Imgproc.circle(flowmap, start, dotRadius, color, -1)
                    sumX += fx
                    sumY += fy
                    sampleCount++
                }

                screenX += step
            }
            screenY += step
        }

        return if (sampleCount > 0) {
            Point(sumX / sampleCount, sumY / sampleCount)
        } else {
            null
        }
    }

    private fun computeCenteredGridStart(size: Int, step: Int): Int {
        if (size <= step) return size / 2

        val halfStep = step / 2
        val sampleCount = (((size - 1) - halfStep) / step) + 1
        val occupiedSpan = (sampleCount - 1) * step
        return ((size - 1 - occupiedSpan) / 2.0).roundToInt()
    }
}
