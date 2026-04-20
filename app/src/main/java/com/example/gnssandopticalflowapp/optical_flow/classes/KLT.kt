package com.example.gnssandopticalflowapp.optical_flow.classes

import android.util.Log
import android.widget.TextView
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import com.example.gnssandopticalflowapp.model.OFOutput
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.Semaphore

class KLT(private val velLabel: TextView?) : OpticalFlow {
    private val prevGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val prevPts: MatOfPoint2f = MatOfPoint2f()
    private val currPts: MatOfPoint2f = MatOfPoint2f()
    private val status: MatOfByte = statusInit()
    private val err: MatOfFloat = MatOfFloat()
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)
    private var flowPts: Int = 0
    private var maxCorners: Int = 50
    private var updateFeatures: Boolean = false
    private var prevMv: Point? = null
    private var currMv: Point? = null
    // LK parameters for improved tracking
    private val lkWinSize: Size = Size(21.0, 21.0)
    private val lkMaxLevel: Int = 3
    private val lkCriteria: TermCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
    private val semaphore: Semaphore = Semaphore(1)
    private val ofOutput: OFOutput = OFOutput()

    private fun statusInit() = MatOfByte()

    override fun setSensitivity(value: Int) {
        try {
            semaphore.acquire()
            maxCorners = value
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

    private fun updatePoints(prevGray: Mat, currGray: Mat, prevPts: MatOfPoint2f) {
        currGray.copyTo(prevGray)
        val corners = MatOfPoint()
        // detect more, but with reasonable min distance and quality
        Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, 0.01, 3.0)
        if (!corners.empty()) {
            prevPts.fromArray(*corners.toArray())
        }
    }

    override fun run(newFrame: Mat): OFOutput {
        Log.d("RUN-OF", "started")
        val currFrame = newFrame.clone()

        Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)

        if (prevGray.empty()) {
            this.updatePoints(prevGray, currGray, prevPts)
            ofOutput.ofFrame = null
            ofOutput.position = null
            return ofOutput
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
            ofOutput.ofFrame = null
            ofOutput.position = null
            return ofOutput
        }

        // use pyramidal LK with tuned parameters
        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, status, err, lkWinSize, lkMaxLevel, lkCriteria, 0, 0.001)

        flowPts = 0
        val statusArray = status.toArray()
        val prevPtsArray = prevPts.toArray()
        val currPtsArray = currPts.toArray()

        // collect per-point motions and filter by status and error
        val dxList = ArrayList<Double>()
        val dyList = ArrayList<Double>()
        val errArray = err.toArray()
        for (i in statusArray.indices) {
            if (statusArray[i].toInt() == 1) {
                val e = if (i < errArray.size) errArray[i].toDouble() else Double.MAX_VALUE
                if (e.isFinite() && e < 50.0) { // filter large errors
                    val pt1 = prevPtsArray[i]
                    val pt2 = currPtsArray[i]
                    val dx = pt2.x - pt1.x
                    val dy = pt2.y - pt1.y
                    dxList.add(dx)
                    dyList.add(dy)
                    Imgproc.line(currFrame, pt1, pt2, color, 6)
                    flowPts++
                }
            }
        }

        fun median(list: List<Double>): Double {
            if (list.isEmpty()) return 0.0
            val sorted = list.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        if (flowPts > 0) {
            val medDx = median(dxList)
            val medDy = median(dyList)

            // smooth motion vector with previous estimate
            val newMv = Point(medDx / 5.0, medDy / 5.0) // scale factor to normalize
            if (prevMv == null) {
                currMv = newMv
            } else {
                // exponential smoothing
                currMv = Point(prevMv!!.x * 0.85 + newMv.x * 0.15, prevMv!!.y * 0.85 + newMv.y * 0.15)
            }
            prevMv = currMv
        }

        currGray.copyTo(prevGray)
        if (!currPts.empty()) {
            prevPts.fromArray(*currPts.toArray())
        }

        ofOutput.ofFrame = currFrame
        ofOutput.position = currMv
        return ofOutput
    }
}
