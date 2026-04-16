package com.example.gnssandopticalflowapp.optical_flow.classes

import android.util.Log
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.roundToInt

class FraneBack : OpticalFlow {
    private val prevFrame: Mat = Mat()
    private var currFrame: Mat = Mat()
    private val flowGray: Mat = Mat()
    private val prevGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val flowRgb: Mat = Mat()
    private val motionVector: Mat = Mat.zeros(400, 400, CvType.CV_8UC1)
    private val pyrScale = 0.5
    private val levels = 3
    private val winSize = 15
    private val iterations = 3
    private val polyN = 5
    private val polySigma = 1.2
    private val flags = 0
    private val ofOutput: OFOutput = OFOutput()

    override fun run(newFrame: Mat): OFOutput {
        Log.d("RUN-OF", "started")
        currFrame = newFrame

        Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)

        if (prevGray.empty()) {
            currGray.copyTo(prevGray)
        }

        Video.calcOpticalFlowFarneback(
            prevGray,
            currGray,
            flowGray,
            pyrScale,
            levels,
            winSize,
            iterations,
            polyN,
            polySigma,
            flags
        )

        currFrame.copyTo(flowRgb)
        drawOptFlowMap(flowGray, flowRgb, 32, Scalar(0.0, 255.0, 0.0))

        currGray.copyTo(prevGray)

        ofOutput.of_frame = flowRgb
        ofOutput.position = Point(0.0, 0.0)
        return ofOutput
    }

    override fun resetMotionVector() {
        // TBD
    }

    override fun updateFeatures() {
        // Do nothing
    }

    override fun setSensitivity(value: Int) {
        // TBD
    }

    private fun drawOptFlowMap(flow: Mat, flowmap: Mat, step: Int, color: Scalar?) {
        val lineThickness = 4
        val circleRadius = 5

        var y = 0
        while (y < flowmap.rows()) {
            var x = 0
            while (x < flowmap.cols()) {
                val f: DoubleArray = flow.get(y, x)
                val fx = f[0]
                val fy = f[1]

                val start = Point(x.toDouble(), y.toDouble())
                val end = Point(
                    (x + fx).roundToInt().toDouble(),
                    (y + fy).roundToInt().toDouble()
                )

                Imgproc.line(flowmap, start, end, color, lineThickness)
                Imgproc.circle(flowmap, start, circleRadius, color, -1)

                x += step
            }
            y += step
        }
    }
}