package com.example.gnssandopticalflowapp.optical_flow.classes

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

class MotionVectorViz(rows: Int, cols: Int) {
    private var motionVector: Mat
    private val height: Int = rows
    private val width: Int = cols
    private val center: Point = Point(width / 2.0, height / 2.0)
    private val color: Scalar = Scalar(240.0, 230.0, 140.0)

    init {
        // initialize new Motion vector matrix
        motionVector = Mat.zeros(rows, cols, CvType.CV_8UC1)
    }

    fun resetMotionVector() {
        motionVector = Mat.zeros(height, width, CvType.CV_8UC1)
    }

    fun getMotionVector(newPos: Point?): Mat {
        if (newPos == null) return motionVector

        val dx = newPos.x
        val dy = newPos.y

        // scaling to make small motions visible, and larger motions proportionally longer
        val displayScale = 16.0 // multiplier for visual amplification
        var dispDx = dx * displayScale
        var dispDy = dy * displayScale

        // enforce a minimum display length for small motions
        val minLen = 30.0
        val dispMag = sqrt(dispDx * dispDx + dispDy * dispDy)
        if (dispMag < minLen && dispMag > 0.0) {
            val factor = minLen / dispMag
            dispDx *= factor
            dispDy *= factor
        }

        // clear previous drawing each frame
        motionVector.setTo(Scalar(0.0))

        val end = Point(center.x - dispDx, center.y - dispDy)
        Imgproc.arrowedLine(motionVector, center, end, color, 8, Imgproc.LINE_AA, 0, 0.25)

        return motionVector
    }
}
