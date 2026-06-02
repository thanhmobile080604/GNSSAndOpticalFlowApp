package com.example.gnssandopticalflowapp.function.optical_flow.classes

import android.util.Log
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import org.opencv.core.Mat

class FailoverOpticalFlow(
    private val primary: OpticalFlow,
    private val fallback: OpticalFlow,
    private val tag: String = "OpticalFlow",
    private val onFallback: (Throwable) -> Unit = {}
) : OpticalFlow, FrameProgressAwareOpticalFlow {
    private var usingFallback = false

    override fun run(newFrame: Mat): OFOutput? {
        if (usingFallback) return fallback.run(newFrame)

        return try {
            primary.run(newFrame)
        } catch (e: Exception) {
            Log.e(tag, "Primary optical flow failed, switching to fallback: ${e.message}", e)
            onFallback(e)
            usingFallback = true
            fallback.run(newFrame)
        }
    }

    override fun resetMotionVector() {
        primary.resetMotionVector()
        fallback.resetMotionVector()
    }

    override fun updateFeatures() {
        primary.updateFeatures()
        fallback.updateFeatures()
    }

    override fun setSensitivity(value: Int) {
        primary.setSensitivity(value)
        fallback.setSensitivity(value)
    }

    override fun setMovingMode(isMoving: Boolean) {
        primary.setMovingMode(isMoving)
        fallback.setMovingMode(isMoving)
    }

    override fun updateFrameProgress(frameNumber: Long, totalFrames: Long) {
        (primary as? FrameProgressAwareOpticalFlow)?.updateFrameProgress(frameNumber, totalFrames)
        (fallback as? FrameProgressAwareOpticalFlow)?.updateFrameProgress(frameNumber, totalFrames)
    }
}
