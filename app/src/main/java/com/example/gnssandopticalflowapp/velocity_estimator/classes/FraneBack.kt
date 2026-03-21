package com.example.opticalflowapp.velocity_estimator.classes

import android.util.Log
import com.example.opticalflowapp.model.OFOutput
import com.example.opticalflowapp.velocity_estimator.inter.OpticalFlow
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
    private val flow_gray: Mat = Mat()
    private val prevGray: Mat = Mat()
    private val currGray: Mat = Mat()
    private val flow_rgb: Mat = Mat()
    private val motion_vector: Mat = Mat.zeros(400, 400, CvType.CV_8UC1)
    private val pyr_scale = 0.5
    private val levels = 3
    private val winSize = 15
    private val iterations = 3
    private val poly_n = 5
    private val poly_sigma = 1.2
    private val flags = 0
    private val of_output: OFOutput = OFOutput()

    override fun run(new_frame: Mat): OFOutput {
        Log.d("RUN-OF", "started")
        currFrame = new_frame

        // convert current frame to gray
        Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)

        // if this is the first run
        if (prevGray.empty()) {
            currGray.copyTo(prevGray)
        }

        // calculate optical flow from prevFrame to currFrame
        Video.calcOpticalFlowFarneback(
            prevGray,
            currGray,
            flow_gray,
            pyr_scale,
            levels,
            winSize,
            iterations,
            poly_n,
            poly_sigma,
            flags
        )

        // draw the optical flow
        currFrame.copyTo(flow_rgb)
        drawOptFlowMap(flow_gray, flow_rgb, 64, Scalar(0.0, 255.0, 0.0))

        // update the variables for the next loop
        currGray.copyTo(prevGray)

        // create the output array
        of_output.of_frame = flow_rgb
        of_output.position = Point(0.0, 0.0)
        return of_output
    }

    override fun reset_motion_vector() {
        // TBD
    }

    override fun UpdateFeatures() {
        // Do nothing
    }

    override fun set_sensitivity(value: Int) {
        // TBD
    }

    private fun drawOptFlowMap(flow: Mat, flowmap: Mat, step: Int, color: Scalar?) {
        var y = 0
        while (y < flowmap.rows()) {
            var x = 0
            while (x < flowmap.cols()) {
                val f: DoubleArray = flow.get(y, x)
                val fx = f[0]
                val fy = f[1]
                val start: Point = Point(x.toDouble(), y.toDouble())
                val end: Point = Point((x + fx).roundToInt().toDouble(), (y + fy).roundToInt().toDouble())
                Imgproc.line(flowmap, start, end, color)
                Imgproc.circle(flowmap, start, 2, color, -1)
                x += step
            }
            y += step
        }
    }
}
