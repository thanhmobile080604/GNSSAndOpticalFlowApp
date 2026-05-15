package com.example.gnssandopticalflowapp.optical_flow.classes

import android.util.Log
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import com.example.gnssandopticalflowapp.optical_flow.interfaces.OpticalFlow
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt
import kotlin.math.sqrt

class KLT : OpticalFlow {
    private val prevGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val prevPts: MatOfPoint2f = MatOfPoint2f()
    private val currPts: MatOfPoint2f = MatOfPoint2f()
    private val status: MatOfByte = statusInit()
    private val err: MatOfFloat = MatOfFloat()
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)
    private val displayVectorLengthMultiplier = 4.8
    private val minDisplayVectorLength = 9.0
    private val vectorThickness = 4
    private var vectorDirectionSign = -1.0
    private var subtractDominantMotion = true
    private var flowPts: Int = 0
    private var maxCorners: Int = 240
    private var qualityLevel: Double = 0.005
    private var minDistance: Double = 2.0
    private var minTrackedMotionMagnitude: Double = 0.55
    private var updateFeatures: Boolean = false
    private var prevMv: Point? = null
    private var currMv: Point? = null
    private var currentSensitivity: Int = 50
    private var frameIndex: Long = 0L
    // LK parameters for improved tracking
    private val lkWinSize: Size = Size(21.0, 21.0)
    private val lkMaxLevel: Int = 3
    private val lkCriteria: TermCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
    private val semaphore: Semaphore = Semaphore(1)
    private val ofOutput: OFOutput = OFOutput()

    private data class TrackMotion(val start: Point, val dx: Double, val dy: Double)

    private fun statusInit() = MatOfByte()

    override fun setSensitivity(value: Int) {
        try {
            semaphore.acquire()
            currentSensitivity = value.coerceIn(0, 100)
            val normalized = currentSensitivity / 100.0
            maxCorners = (12 + (normalized * 408.0)).roundToInt()
            qualityLevel = (0.10 - (normalized * 0.095)).coerceIn(0.005, 0.10)
            minDistance = (14.0 - (normalized * 12.0)).coerceIn(2.0, 14.0)
            minTrackedMotionMagnitude = (0.80 - (normalized * 0.50)).coerceIn(0.30, 0.80)
            updateFeatures = true
            semaphore.release()
        } catch (e: Exception) {
            Log.e("SENSITIVITY", "Failed to acquire semaphore")
        }
    }

    override fun resetMotionVector() {
        prevMv = null
        currMv = null
    }

    override fun updateFeatures() {
        this.updateFeatures = true
    }

    override fun setMovingMode(isMoving: Boolean) {
        vectorDirectionSign = if (isMoving) 1.0 else -1.0
        subtractDominantMotion = !isMoving
    }

    private fun updatePoints(prevGray: Mat, currGray: Mat, prevPts: MatOfPoint2f) {
        currGray.copyTo(prevGray)
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, qualityLevel, minDistance)
        prevPts.fromArray(*corners.toArray())
    }

    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    override fun run(newFrame: Mat): OFOutput {
        val startNanos = System.nanoTime()
        val currFrame = newFrame

        Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)

        if (prevGray.empty()) {
            this.updatePoints(prevGray, currGray, prevPts)
            return buildOutput(
                frame = null,
                position = null,
                startNanos = startNanos,
                featureCount = prevPts.toArray().size,
                activeVectorCount = 0,
                avgDx = 0.0,
                avgDy = 0.0,
                avgMagnitude = 0.0,
                confidence = 0.0
            )
        }

        try {
            semaphore.acquire()
            val limit = maxCorners / 5
            if (flowPts < limit || this.updateFeatures) {
                this.updatePoints(prevGray, currGray, prevPts)
                this.updateFeatures = false
            }
            semaphore.release()
        } catch (e: Exception) {
            Log.e("SENSITIVITY", "Failed to acquire semaphore")
        }

        if (prevPts.empty()) {
            return buildOutput(
                frame = null,
                position = null,
                startNanos = startNanos,
                featureCount = 0,
                activeVectorCount = 0,
                avgDx = 0.0,
                avgDy = 0.0,
                avgMagnitude = 0.0,
                confidence = 0.0
            )
        }

        // use pyramidal LK with tuned parameters
        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, status, err, lkWinSize, lkMaxLevel, lkCriteria, 0, 0.001)

        flowPts = 0
        val statusArray = status.toArray()
        val prevPtsArray = prevPts.toArray()
        val currPtsArray = currPts.toArray()

        // Track all reliable points first so feature refresh is not driven by display filtering.
        val trackedMotions = ArrayList<TrackMotion>()
        val allDxList = ArrayList<Double>()
        val allDyList = ArrayList<Double>()
        val errList = ArrayList<Double>()
        val errArray = err.toArray()
        for (i in statusArray.indices) {
            if (statusArray[i].toInt() == 1) {
                val e = if (i < errArray.size) errArray[i].toDouble() else Double.MAX_VALUE
                if (e.isFinite() && e < 50.0) { // filter large errors
                    val pt1 = prevPtsArray[i]
                    val pt2 = currPtsArray[i]
                    val dx = pt2.x - pt1.x
                    val dy = pt2.y - pt1.y
                    flowPts++
                    trackedMotions.add(TrackMotion(pt1, dx, dy))
                    allDxList.add(dx)
                    allDyList.add(dy)
                    errList.add(e)
                }
            }
        }

        val dominantDx = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDxList) else 0.0
        val dominantDy = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDyList) else 0.0

        // Only draw and aggregate motion above the jitter floor.
        val dxList = ArrayList<Double>()
        val dyList = ArrayList<Double>()
        var motionPts = 0
        var metricDx = 0.0
        var metricDy = 0.0
        var metricMagnitude = 0.0
        for (motion in trackedMotions) {
            val dx = motion.dx - dominantDx
            val dy = motion.dy - dominantDy
            val rawMagnitude = sqrt((dx * dx) + (dy * dy))
            if (rawMagnitude < minTrackedMotionMagnitude) {
                continue
            }

            dxList.add(dx)
            dyList.add(dy)
            var displayDx = dx * vectorDirectionSign * displayVectorLengthMultiplier
            var displayDy = dy * vectorDirectionSign * displayVectorLengthMultiplier
            val displayMagnitude = sqrt((displayDx * displayDx) + (displayDy * displayDy))
            if (displayMagnitude < minDisplayVectorLength && displayMagnitude > 0.0) {
                val scaleUp = minDisplayVectorLength / displayMagnitude
                displayDx *= scaleUp
                displayDy *= scaleUp
            }
            val displayEnd = Point(motion.start.x + displayDx, motion.start.y + displayDy)
            Imgproc.line(currFrame, motion.start, displayEnd, color, vectorThickness)
            motionPts++
        }

        if (motionPts > 0) {
            val medDx = median(dxList)
            val medDy = median(dyList)
            val medianMagnitude = sqrt((medDx * medDx) + (medDy * medDy))
            metricDx = medDx * vectorDirectionSign
            metricDy = medDy * vectorDirectionSign
            metricMagnitude = medianMagnitude

            if (medianMagnitude >= minTrackedMotionMagnitude) {
                // smooth motion vector with previous estimate
                val newMv = Point(
                    medDx * vectorDirectionSign / 5.0,
                    medDy * vectorDirectionSign / 5.0
                )
                if (prevMv == null) {
                    currMv = newMv
                } else {
                    // exponential smoothing
                    currMv = Point(prevMv!!.x * 0.85 + newMv.x * 0.15, prevMv!!.y * 0.85 + newMv.y * 0.15)
                }
                prevMv = currMv
            } else {
                currMv = null
                prevMv = null
            }
        } else {
            currMv = null
            prevMv = null
        }

        currGray.copyTo(prevGray)
        if (!currPts.empty()) {
            prevPts.fromArray(*currPts.toArray())
        }

        return buildOutput(
            frame = currFrame,
            position = currMv,
            startNanos = startNanos,
            featureCount = flowPts,
            activeVectorCount = motionPts,
            avgDx = metricDx,
            avgDy = metricDy,
            avgMagnitude = metricMagnitude,
            confidence = computeConfidence(
                trackedCount = flowPts,
                totalCount = prevPtsArray.size,
                avgErr = errList.averageOrZero(),
                activeVectorCount = motionPts
            )
        )
    }

    private fun buildOutput(
        frame: Mat?,
        position: Point?,
        startNanos: Long,
        featureCount: Int,
        activeVectorCount: Int,
        avgDx: Double,
        avgDy: Double,
        avgMagnitude: Double,
        confidence: Double
    ): OFOutput {
        val processTimeMs = ((System.nanoTime() - startNanos) / 1_000_000.0).coerceAtLeast(0.001)
        ofOutput.ofFrame = frame
        ofOutput.position = position
        ofOutput.metrics = OpticalFlowMetrics(
            algorithm = "KLT",
            frameIndex = frameIndex++,
            processTimeMs = processTimeMs,
            instantFps = 1000.0 / processTimeMs,
            featureCount = featureCount,
            activeVectorCount = activeVectorCount,
            avgDx = avgDx,
            avgDy = avgDy,
            avgMagnitude = avgMagnitude,
            confidence = confidence.coerceIn(0.0, 100.0),
            threshold = minTrackedMotionMagnitude,
            sensitivity = currentSensitivity
        )
        return ofOutput
    }

    private fun computeConfidence(
        trackedCount: Int,
        totalCount: Int,
        avgErr: Double,
        activeVectorCount: Int
    ): Double {
        if (totalCount <= 0 || trackedCount <= 0) return 0.0

        val trackRatio = trackedCount.toDouble() / totalCount.toDouble()
        val densityRatio = trackedCount.toDouble() / maxCorners.coerceAtLeast(1).toDouble()
        val errorScore = 1.0 - (avgErr / 50.0).coerceIn(0.0, 1.0)
        val activityRatio = activeVectorCount.toDouble() / trackedCount.toDouble()
        return ((trackRatio * 0.40) + (errorScore * 0.35) + (densityRatio * 0.20) + (activityRatio * 0.05)) * 100.0
    }

    private fun List<Double>.averageOrZero(): Double {
        if (isEmpty()) return 0.0
        return average().takeIf { it.isFinite() } ?: 0.0
    }
}
