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
        Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, 0.1, 5.0)
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
            ofOutput.of_frame = null
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
            ofOutput.of_frame = null
            ofOutput.position = null
            return ofOutput
        }

        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, status, err)

        flowPts = 0
        var xAvg1 = 0.0
        var xAvg2 = 0.0
        var yAvg1 = 0.0
        var yAvg2 = 0.0
        
        val statusArray = status.toArray()
        val prevPtsArray = prevPts.toArray()
        val currPtsArray = currPts.toArray()

        for (i in statusArray.indices) {
            if (statusArray[i].toInt() == 1) {
                val pt1 = prevPtsArray[i]
                val pt2 = currPtsArray[i]
                xAvg1 += pt1.x
                xAvg2 += pt2.x
                yAvg1 += pt1.y
                yAvg2 += pt2.y
                Imgproc.line(currFrame, pt1, pt2, color, 10)
                flowPts++
            }
        }

        if (flowPts > 0) {
            xAvg1 /= flowPts.toDouble()
            yAvg1 /= flowPts.toDouble()
            xAvg2 /= flowPts.toDouble()
            yAvg2 /= flowPts.toDouble()

            currMv = Point((xAvg1 - xAvg2) / 10, (yAvg1 - yAvg2) / 10)
            if (prevMv == null) {
                currMv!!.x += 200.0
                currMv!!.y += 200.0
            } else {
                currMv!!.x += prevMv!!.x
                currMv!!.y += prevMv!!.y
            }
            prevMv = currMv
        }

        currGray.copyTo(prevGray)
        if (!currPts.empty()) {
            prevPts.fromArray(*currPts.toArray())
        }

        ofOutput.of_frame = currFrame
        ofOutput.position = currMv
        return ofOutput
    }
}
