package com.example.gnssandopticalflowapp.velocity_estimator.classes

import android.util.Log
import android.widget.TextView
import com.example.gnssandopticalflowapp.velocity_estimator.inter.OpticalFlow
import com.example.gnssandopticalflowapp.model.OFOutput
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.Semaphore

class KLT(private val vel_label: TextView?) : OpticalFlow {
    private val prevGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val prevPts: MatOfPoint2f = MatOfPoint2f()
    private val currPts: MatOfPoint2f = MatOfPoint2f()
    private val status: MatOfByte = status_init()
    private val err: MatOfFloat = MatOfFloat()
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)
    
    private var flow_pts: Int = 0
    private var max_corners: Int = 50
    private var update_features: Boolean = false
    private var prevMv: Point? = null
    private var currMv: Point? = null
    private val semaphore: Semaphore = Semaphore(1)
    private val of_output: OFOutput = OFOutput()

    private fun status_init() = MatOfByte()

    override fun set_sensitivity(value: Int) {
        try {
            semaphore.acquire()
            max_corners = value
            semaphore.release()
        } catch (e: Exception) {
            Log.e("SENSITIVITY", "Failed to acquire semaphore")
        }
    }

    override fun reset_motion_vector() {
        prevMv = null
        currMv = null
    }

    override fun UpdateFeatures() {
        this.update_features = true
    }

    private fun update_points(prevGray: Mat, currGray: Mat, prevPts: MatOfPoint2f) {
        currGray.copyTo(prevGray)
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(prevGray, corners, max_corners, 0.1, 5.0)
        if (!corners.empty()) {
            prevPts.fromArray(*corners.toArray())
        }
    }

    override fun run(new_frame: Mat): OFOutput {
        Log.d("RUN-OF", "started")
        val currFrame = new_frame.clone()

        Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)

        if (prevGray.empty()) {
            this.update_points(prevGray, currGray, prevPts)
            of_output.of_frame = null
            of_output.position = null
            return of_output
        }

        try {
            semaphore.acquire()
            val limit = max_corners / 5
            if (flow_pts < limit || this.update_features) {
                this.update_points(prevGray, currGray, prevPts)
                this.update_features = false
            }
            semaphore.release()
        } catch (e: Exception) {
            Log.e("SENSITIVITY", "Failed to acquire semaphore")
        }

        if (prevPts.empty()) {
            of_output.of_frame = null
            of_output.position = null
            return of_output
        }

        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, status, err)

        flow_pts = 0
        var x_avg1 = 0.0
        var x_avg2 = 0.0
        var y_avg1 = 0.0
        var y_avg2 = 0.0
        
        val statusArray = status.toArray()
        val prevPtsArray = prevPts.toArray()
        val currPtsArray = currPts.toArray()

        for (i in statusArray.indices) {
            if (statusArray[i].toInt() == 1) {
                val pt1 = prevPtsArray[i]
                val pt2 = currPtsArray[i]
                x_avg1 += pt1.x
                x_avg2 += pt2.x
                y_avg1 += pt1.y
                y_avg2 += pt2.y
                Imgproc.line(currFrame, pt1, pt2, color, 10)
                flow_pts++
            }
        }

        if (flow_pts > 0) {
            x_avg1 /= flow_pts.toDouble()
            y_avg1 /= flow_pts.toDouble()
            x_avg2 /= flow_pts.toDouble()
            y_avg2 /= flow_pts.toDouble()

            currMv = Point((x_avg1 - x_avg2) / 10, (y_avg1 - y_avg2) / 10)
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

        of_output.of_frame = currFrame
        of_output.position = currMv
        return of_output
    }
}
