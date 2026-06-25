package com.example.gnssandopticalflowapp.function.optical_flow.classes

import android.util.Log
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.Semaphore
import kotlin.math.abs
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
    private val lkWinSize: Size = Size(21.0, 21.0)
    private val lkMaxLevel: Int = 3
    private val lkCriteria: TermCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
    private val semaphore: Semaphore = Semaphore(1)
    private val ofOutput: OFOutput = OFOutput()

    // Persistent per-track smoothing to remove arrow flicker (appear/disappear each frame).
    private val emaAlpha = 0.35
    private val visRampUp = 0.30
    private val visRampDown = 0.12
    private val drawVisMin = 0.12
    private var tracks = ArrayList<Track>()

    private class Track {
        var dx = 0.0
        var dy = 0.0
        var vis = 0.0
        var initialized = false
    }

    private data class TrackMotion(val index: Int, val start: Point, val dx: Double, val dy: Double)

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
        resetTracks(prevPts.toArray().size)
    }

    private fun resetTracks(size: Int) {
        tracks = ArrayList(size)
        repeat(size) { tracks.add(Track()) }
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

        if (prevGray.cols() != currGray.cols() || prevGray.rows() != currGray.rows()) {
            resetMotionVector()
            flowPts = 0
            this.updatePoints(prevGray, currGray, prevPts)
            return buildOutput(
                frame = currFrame,
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

        // use pyramidal LK with tuned parameters (Forward)
        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, status, err, lkWinSize, lkMaxLevel, lkCriteria, 0, 0.001)

        // Backward flow for FBE
        val backwardPts = MatOfPoint2f()
        val statusBack = MatOfByte()
        val errBack = MatOfFloat()
        Video.calcOpticalFlowPyrLK(currGray, prevGray, currPts, backwardPts, statusBack, errBack, lkWinSize, lkMaxLevel, lkCriteria, 0, 0.001)

        flowPts = 0
        val statusArray = status.toArray()
        val statusBackArray = statusBack.toArray()
        val prevPtsArray = prevPts.toArray()
        val currPtsArray = currPts.toArray()
        val backwardPtsArray = backwardPts.toArray()

        if (tracks.size != prevPtsArray.size) {
            resetTracks(prevPtsArray.size)
        }

        // Keep EVERY tracked vector (whole frame); FBE is measured for confidence only, drops nothing.
        val trackedMotions = ArrayList<TrackMotion>()
        val allDxList = ArrayList<Double>()
        val allDyList = ArrayList<Double>()
        var fbeInliers = 0
        var fbeTotalTracked = 0

        for (i in statusArray.indices) {
            if (statusArray[i].toInt() == 1) {
                val pt1 = prevPtsArray[i]

                var fbeValid = false
                if (statusBackArray.size > i && statusBackArray[i].toInt() == 1) {
                    val ptBack = backwardPtsArray[i]
                    val errX = pt1.x - ptBack.x
                    val errY = pt1.y - ptBack.y
                    if (errX * errX + errY * errY <= 2.25) fbeValid = true
                }
                fbeTotalTracked++
                if (fbeValid) fbeInliers++

                val pt2 = currPtsArray[i]
                val dx = pt2.x - pt1.x
                val dy = pt2.y - pt1.y
                flowPts++
                trackedMotions.add(TrackMotion(i, pt1, dx, dy))
                allDxList.add(dx)
                allDyList.add(dy)
            }
        }

        val dominantDx = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDxList) else 0.0
        val dominantDy = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDyList) else 0.0

        // Per-track EMA + visibility ramp: smooth each arrow over time and fade it
        // in/out (via length) instead of popping on/off when motion crosses the threshold.
        val motions = ArrayList<TrackMotion>()
        var coherenceSumDx = 0.0
        var coherenceSumAbsDx = 0.0
        for (motion in trackedMotions) {
            val rawDx = motion.dx - dominantDx
            val rawDy = motion.dy - dominantDy
            val track = tracks[motion.index]
            if (!track.initialized) {
                track.dx = rawDx
                track.dy = rawDy
                track.initialized = true
            } else {
                track.dx += emaAlpha * (rawDx - track.dx)
                track.dy += emaAlpha * (rawDy - track.dy)
            }
            val sdx = track.dx
            val sdy = track.dy
            val mag = sqrt((sdx * sdx) + (sdy * sdy))
            val active = mag >= minTrackedMotionMagnitude
            track.vis = if (active) {
                (track.vis + visRampUp).coerceAtMost(1.0)
            } else {
                (track.vis - visRampDown).coerceAtLeast(0.0)
            }
            if (track.vis <= drawVisMin) continue

            val displayDx = sdx * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
            val displayDy = sdy * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
            val displayEnd = Point(motion.start.x + displayDx, motion.start.y + displayDy)
            Imgproc.line(currFrame, motion.start, displayEnd, color, vectorThickness)

            if (active) {
                motions.add(TrackMotion(motion.index, motion.start, sdx, sdy))
                coherenceSumDx += sdx
                coherenceSumAbsDx += abs(sdx)
            }
        }
        val lateralCoherence = if (coherenceSumAbsDx > 1e-3) coherenceSumDx / coherenceSumAbsDx else 0.0

        val motionPts = motions.size
        var metricDx = 0.0
        var metricDy = 0.0
        var metricMagnitude = 0.0
        if (motionPts > 0) {
            val medDx = median(motions.map { it.dx })
            val medDy = median(motions.map { it.dy })
            metricMagnitude = sqrt((medDx * medDx) + (medDy * medDy))
            metricDx = medDx // TRUE flow direction (display sign is applied to the drawn arrow only)
            metricDy = medDy

            if (metricMagnitude >= minTrackedMotionMagnitude) {
                // smooth motion vector with previous estimate (display-direction, for position out)
                val newMv = Point(
                    medDx * vectorDirectionSign / 5.0,
                    medDy * vectorDirectionSign / 5.0
                )
                currMv = if (prevMv == null) {
                    newMv
                } else {
                    Point(prevMv!!.x * 0.85 + newMv.x * 0.15, prevMv!!.y * 0.85 + newMv.y * 0.15)
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
                inliers = fbeInliers,
                totalTracked = fbeTotalTracked
            ),
            lateralCoherence = lateralCoherence
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
        confidence: Double,
        lateralCoherence: Double = 0.0
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
            sensitivity = currentSensitivity,
            lateralCoherence = lateralCoherence.coerceIn(-1.0, 1.0)
        )
        return ofOutput
    }

    private fun computeConfidence(
        inliers: Int,
        totalTracked: Int
    ): Double {
        if (totalTracked <= 0) return 0.0
        return (inliers.toDouble() / totalTracked.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

}
