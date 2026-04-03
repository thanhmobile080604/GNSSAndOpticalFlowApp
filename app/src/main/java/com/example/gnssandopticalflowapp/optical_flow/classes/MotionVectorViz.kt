package com.example.gnssandopticalflowapp.optical_flow.classes

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class MotionVectorViz(rows: Int, cols: Int) {
    private var motionVector: Mat
    private var prevMV: Point? = null
    private var currMV: Point? = null
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)

    init {
        // initialize new Motion vector matrix
        motionVector = Mat.zeros(rows, cols, CvType.CV_8UC1)
    }

    fun resetMotionVector() {
        motionVector = Mat.zeros(400, 400, CvType.CV_8UC1)
        prevMV = null
        currMV = null
    }

    fun getMotionVector(newPos: Point?): Mat {
        currMV = newPos
        // first iteration
        if (prevMV == null) {
            prevMV = currMV
        }

        if (prevMV != null && currMV != null) {
            Imgproc.line(motionVector, prevMV, currMV, color, 4)
        }

        prevMV = currMV

        return motionVector
    }
}
