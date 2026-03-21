package com.example.opticalflowapp.velocity_estimator.classes

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class MotionVectorViz(rows: Int, cols: Int) {
    private var motion_vector: Mat
    private var prevMV: Point? = null
    private var currMV: Point? = null
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)

    init {
        // initialize new Motion vector matrix
        motion_vector = Mat.zeros(rows, cols, CvType.CV_8UC1)
    }

    fun reset_motion_vector() {
        motion_vector = Mat.zeros(400, 400, CvType.CV_8UC1)
        prevMV = null
        currMV = null
    }

    fun getMotionVector(new_pos: Point?): Mat {
        currMV = new_pos
        // first iteration
        if (prevMV == null) {
            prevMV = currMV
        }

        if (prevMV != null && currMV != null) {
            Imgproc.line(motion_vector, prevMV, currMV, color, 4)
        }

        prevMV = currMV

        return motion_vector
    }
}
