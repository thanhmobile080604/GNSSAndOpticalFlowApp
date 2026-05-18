package com.example.gnssandopticalflowapp.optical_flow.classes

import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Farneback : OpticalFlow {
    enum class VisualizationMode {
        VECTORS,
        HEATMAP
    }

    private val scaledPrevGray: Mat = Mat()
    private val scaledCurrGray: Mat = Mat()
    private val flowGray: Mat = Mat()
    private val backwardFlowGray: Mat = Mat()
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
    private var minMotionMagnitude = 0.22
    private val dotRadius = 4
    private val vectorThickness = 4
    private val vectorLengthMultiplier = 4.2
    private val minDisplayVectorLength = 9.0
    private var vectorDirectionSign = -1.0
    private var currentSensitivity = 50
    private var frameIndex = 0L
    private var visualizationMode = VisualizationMode.VECTORS
    private val ofOutput: OFOutput = OFOutput()
    private val flowColor = Scalar(0.0, 255.0, 0.0)

    private data class FlowStats(
        val avgMotion: Point?,
        val sampleCount: Int,
        val activeVectorCount: Int,
        val avgDx: Double,
        val avgDy: Double,
        val avgMagnitude: Double,
        val confidence: Double
    )

    override fun run(newFrame: Mat): OFOutput {
        val startNanos = System.nanoTime()
        Imgproc.cvtColor(newFrame, currGray, Imgproc.COLOR_RGBA2GRAY)
        resizeForFlow(currGray, scaledCurrGray)

        val flowInputSizeChanged =
            scaledPrevGray.rows() != scaledCurrGray.rows() || scaledPrevGray.cols() != scaledCurrGray.cols()
        if (scaledPrevGray.empty() || flowInputSizeChanged) {
            scaledCurrGray.copyTo(scaledPrevGray)
            return buildOutput(
                frame = newFrame,
                position = null,
                startNanos = startNanos,
                stats = FlowStats(
                    avgMotion = null,
                    sampleCount = 0,
                    activeVectorCount = 0,
                    avgDx = 0.0,
                    avgDy = 0.0,
                    avgMagnitude = 0.0,
                    confidence = 0.0
                )
            )
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

        // Backward flow for FBE
        Video.calcOpticalFlowFarneback(
            scaledCurrGray,
            scaledPrevGray,
            backwardFlowGray,
            pyrScale,
            levels,
            winSize,
            iterations,
            polyN,
            polySigma,
            flags
        )

        val stats = drawOptFlowMap(flowGray, backwardFlowGray, newFrame, drawStep, flowColor)

        scaledCurrGray.copyTo(scaledPrevGray)

        return buildOutput(
            frame = newFrame,
            position = stats.avgMotion,
            startNanos = startNanos,
            stats = stats
        )
    }

    override fun resetMotionVector() {
        // TBD
    }

    override fun updateFeatures() {
        // Do nothing
    }

    override fun setMovingMode(isMoving: Boolean) {
        vectorDirectionSign = if (isMoving) 1.0 else -1.0
    }

    override fun setSensitivity(value: Int) {
        currentSensitivity = value.coerceIn(0, 100)
        val normalized = (currentSensitivity / 100.0)
        frameScale = 0.35 + (normalized * 0.35)
        drawStep = (40 - (normalized * 20)).toInt().coerceIn(20, 40)
        levels = if (normalized >= 0.65) 3 else 2
        winSize = (11 + (normalized * 8)).toInt().coerceIn(11, 19)
        iterations = if (normalized >= 0.5) 3 else 2
        minMotionMagnitude = (0.35 - (normalized * 0.23)).coerceIn(0.12, 0.35)
    }

    fun setVisualizationMode(mode: VisualizationMode) {
        visualizationMode = mode
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

    private fun drawOptFlowMap(flow: Mat, backwardFlow: Mat, flowmap: Mat, step: Int, color: Scalar): FlowStats {
        if (flow.empty() || backwardFlow.empty()) {
            return FlowStats(
                avgMotion = null,
                sampleCount = 0,
                activeVectorCount = 0,
                avgDx = 0.0,
                avgDy = 0.0,
                avgMagnitude = 0.0,
                confidence = 0.0
            )
        }

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
        var totalMagnitude = 0.0
        var gridSampleCount = 0
        var sampleCount = 0
        var fbeInliers = 0
        val heatmapOverlay = if (visualizationMode == VisualizationMode.HEATMAP) {
            Mat.zeros(flowmap.rows(), flowmap.cols(), flowmap.type())
        } else {
            null
        }
        var screenY = startY
        try {
            while (screenY < mapRows) {
                var screenX = startX
                while (screenX < mapCols) {
                    gridSampleCount++
                    val flowX = (screenX / xScale).roundToInt().coerceIn(0, flowCols - 1)
                    val flowY = (screenY / yScale).roundToInt().coerceIn(0, flowRows - 1)
                    val vector = flow.get(flowY, flowX) ?: doubleArrayOf(0.0, 0.0)
                    val fx = vector[0] * xScale
                    val fy = vector[1] * yScale
                    val magnitudeSquared = (fx * fx) + (fy * fy)
                    val magnitude = sqrt(magnitudeSquared)

                    if (magnitudeSquared >= minMotionSquared) {
                        // Check FBE
                        var fbeValid = false
                        val bx = (flowX + fx).roundToInt().coerceIn(0, flowCols - 1)
                        val by = (flowY + fy).roundToInt().coerceIn(0, flowRows - 1)
                        val bVec = backwardFlow.get(by, bx)
                        if (bVec != null) {
                            val bdx = bVec[0] * xScale
                            val bdy = bVec[1] * yScale
                            val errX = fx + bdx
                            val errY = fy + bdy
                            val fbeSquared = errX * errX + errY * errY
                            if (fbeSquared <= 2.25) { // Threshold 1.5 pixels
                                fbeValid = true
                            }
                        }

                        if (fbeValid) {
                            fbeInliers++
                        }

                        val start = Point(screenX.toDouble(), screenY.toDouble())
                        if (visualizationMode == VisualizationMode.HEATMAP && heatmapOverlay != null) {
                            Imgproc.circle(
                                heatmapOverlay,
                                start,
                                (step * 0.58f).roundToInt().coerceAtLeast(8),
                                heatColor(magnitude),
                                -1
                            )
                        } else {
                            var displayFx = fx * vectorDirectionSign * vectorLengthMultiplier
                            var displayFy = fy * vectorDirectionSign * vectorLengthMultiplier
                            val displayMagnitude = sqrt((displayFx * displayFx) + (displayFy * displayFy))
                            if (displayMagnitude < minDisplayVectorLength && displayMagnitude > 0.0) {
                                val scaleUp = minDisplayVectorLength / displayMagnitude
                                displayFx *= scaleUp
                                displayFy *= scaleUp
                            }
                            val end = Point(
                                start.x + displayFx,
                                start.y + displayFy
                            )

                            Imgproc.line(flowmap, start, end, color, vectorThickness)
                            Imgproc.circle(flowmap, start, dotRadius, color, -1)
                        }
                        sumX += fx * vectorDirectionSign
                        sumY += fy * vectorDirectionSign
                        totalMagnitude += magnitude
                        sampleCount++
                    }

                    screenX += step
                }
                screenY += step
            }
            if (heatmapOverlay != null) {
                Core.addWeighted(flowmap, 1.0, heatmapOverlay, 0.72, 0.0, flowmap)
            }
        } finally {
            heatmapOverlay?.release()
        }

        return if (sampleCount > 0) {
            val avgDx = sumX / sampleCount
            val avgDy = sumY / sampleCount
            val confidence = if (sampleCount > 0) (fbeInliers.toDouble() / sampleCount.toDouble()) * 100.0 else 0.0
            FlowStats(
                avgMotion = Point(avgDx, avgDy),
                sampleCount = gridSampleCount,
                activeVectorCount = sampleCount,
                avgDx = avgDx,
                avgDy = avgDy,
                avgMagnitude = totalMagnitude / sampleCount,
                confidence = confidence.coerceIn(0.0, 100.0)
            )
        } else {
            FlowStats(
                avgMotion = null,
                sampleCount = gridSampleCount,
                activeVectorCount = 0,
                avgDx = 0.0,
                avgDy = 0.0,
                avgMagnitude = 0.0,
                confidence = 0.0
            )
        }
    }

    private fun heatColor(magnitude: Double): Scalar {
        val normalized = (magnitude / (minMotionMagnitude * 8.0)).coerceIn(0.0, 1.0)
        val red = (255.0 * normalized).coerceIn(0.0, 255.0)
        val green = (255.0 * (1.0 - kotlin.math.abs(normalized - 0.5) * 2.0)).coerceIn(0.0, 255.0)
        val blue = (255.0 * (1.0 - normalized)).coerceIn(0.0, 255.0)
        return Scalar(red, green, blue, 255.0)
    }

    private fun computeCenteredGridStart(size: Int, step: Int): Int {
        if (size <= step) return size / 2

        val halfStep = step / 2
        val sampleCount = (((size - 1) - halfStep) / step) + 1
        val occupiedSpan = (sampleCount - 1) * step
        return ((size - 1 - occupiedSpan) / 2.0).roundToInt()
    }

    private fun buildOutput(
        frame: Mat,
        position: Point?,
        startNanos: Long,
        stats: FlowStats
    ): OFOutput {
        val processTimeMs = ((System.nanoTime() - startNanos) / 1_000_000.0).coerceAtLeast(0.001)
        ofOutput.ofFrame = frame
        ofOutput.position = position
        ofOutput.metrics = OpticalFlowMetrics(
            algorithm = "Farneback",
            frameIndex = frameIndex++,
            processTimeMs = processTimeMs,
            instantFps = 1000.0 / processTimeMs,
            featureCount = stats.sampleCount,
            activeVectorCount = stats.activeVectorCount,
            avgDx = stats.avgDx,
            avgDy = stats.avgDy,
            avgMagnitude = stats.avgMagnitude,
            confidence = stats.confidence.coerceIn(0.0, 100.0),
            threshold = minMotionMagnitude,
            sensitivity = currentSensitivity
        )
        return ofOutput
    }
}
