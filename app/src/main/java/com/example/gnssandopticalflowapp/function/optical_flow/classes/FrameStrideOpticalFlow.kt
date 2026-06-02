package com.example.gnssandopticalflowapp.function.optical_flow.classes

import android.util.Log
import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.FrameProgressAwareOpticalFlow
import com.example.gnssandopticalflowapp.function.optical_flow.interfaces.OpticalFlow
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class FrameStrideOpticalFlow(
    private val delegate: OpticalFlow,
    private val stride: Int,
    private val tag: String = "OpticalFlow"
) : OpticalFlow, FrameProgressAwareOpticalFlow {
    private var frameNumber = 0L
    private var totalFrames = 0L
    private var lastOutput: OFOutput? = null
    private val cachedOverlayFrame = Mat()
    private val cachedOverlayMask = Mat()
    private var cachedCols = 0
    private var cachedRows = 0

    override fun run(newFrame: Mat): OFOutput? {
        if (hasFrameSizeChanged(newFrame)) {
            clearCachedOverlay()
        }

        val safeStride = stride.coerceAtLeast(1)
        val shouldRunDelegate = frameNumber <= 1L || ((frameNumber - 1L) % safeStride == 0L)
        return if (shouldRunDelegate) {
            Log.d(tag, "FrameStride: running AI frame=$frameNumber / ${totalFramesLabel()} stride=$safeStride")
            runDelegateAndCacheOverlay(newFrame)
        } else {
            Log.d(tag, "FrameStride: reusing last AI overlay for frame=$frameNumber / ${totalFramesLabel()} stride=$safeStride")
            applyCachedOverlay(newFrame)
            lastOutput?.let { cached ->
                cached.ofFrame = newFrame
                cached
            } ?: runDelegateAndCacheOverlay(newFrame)
        }
    }

    override fun resetMotionVector() {
        lastOutput = null
        clearCachedOverlay()
        delegate.resetMotionVector()
    }

    override fun updateFeatures() {
        delegate.updateFeatures()
    }

    override fun setSensitivity(value: Int) {
        delegate.setSensitivity(value)
    }

    override fun setMovingMode(isMoving: Boolean) {
        delegate.setMovingMode(isMoving)
    }

    override fun updateFrameProgress(frameNumber: Long, totalFrames: Long) {
        this.frameNumber = frameNumber.coerceAtLeast(0L)
        this.totalFrames = totalFrames.coerceAtLeast(0L)
        (delegate as? FrameProgressAwareOpticalFlow)?.updateFrameProgress(frameNumber, totalFrames)
    }

    private fun totalFramesLabel(): String {
        return totalFrames.takeIf { it > 0L }?.toString() ?: "?"
    }

    private fun hasFrameSizeChanged(frame: Mat): Boolean {
        if (cachedOverlayFrame.empty()) return false
        return frame.cols() != cachedCols || frame.rows() != cachedRows
    }

    private fun cacheOverlay(originalFrame: Mat, processedFrame: Mat) {
        if (originalFrame.empty() || processedFrame.empty()) return
        if (originalFrame.cols() != processedFrame.cols() || originalFrame.rows() != processedFrame.rows()) return

        val diff = Mat()
        val grayDiff = Mat()
        val mask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val overlayOnly = Mat.zeros(processedFrame.rows(), processedFrame.cols(), processedFrame.type())

        try {
            Core.absdiff(originalFrame, processedFrame, diff)
            if (diff.channels() == 4) {
                Imgproc.cvtColor(diff, grayDiff, Imgproc.COLOR_RGBA2GRAY)
            } else if (diff.channels() == 3) {
                Imgproc.cvtColor(diff, grayDiff, Imgproc.COLOR_RGB2GRAY)
            } else {
                diff.copyTo(grayDiff)
            }
            Imgproc.threshold(grayDiff, mask, OVERLAY_DIFF_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(
                mask,
                mask,
                kernel
            )

            processedFrame.copyTo(overlayOnly, mask)
            overlayOnly.copyTo(cachedOverlayFrame)
            mask.copyTo(cachedOverlayMask)
            cachedCols = processedFrame.cols()
            cachedRows = processedFrame.rows()
            Log.d(tag, "FrameStride: cached overlay for ${cachedCols}x${cachedRows}")
        } finally {
            diff.release()
            grayDiff.release()
            mask.release()
            kernel.release()
            overlayOnly.release()
        }
    }

    private fun runDelegateAndCacheOverlay(frame: Mat): OFOutput? {
        val originalFrame = frame.clone()
        return try {
            delegate.run(frame)?.also { output ->
                cacheOverlay(originalFrame, output.ofFrame ?: frame)
                lastOutput = output
            }
        } finally {
            originalFrame.release()
        }
    }

    private fun applyCachedOverlay(frame: Mat) {
        if (cachedOverlayFrame.empty() || cachedOverlayMask.empty()) return
        if (frame.cols() != cachedCols || frame.rows() != cachedRows) return

        cachedOverlayFrame.copyTo(frame, cachedOverlayMask)
    }

    private fun clearCachedOverlay() {
        cachedOverlayFrame.release()
        cachedOverlayMask.release()
        cachedCols = 0
        cachedRows = 0
    }

    private companion object {
        const val OVERLAY_DIFF_THRESHOLD = 2.0
    }
}
