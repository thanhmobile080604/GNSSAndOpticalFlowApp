package com.example.gnssandopticalflowapp.function.optical_flow.classes

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Tracker
import org.opencv.video.TrackerMIL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ObjectRoiTracker {
    private var nativeTracker: Tracker? = null
    private var templateGray: Mat? = null
    private var previousRect: Rect? = null
    private var initialMask: Mat? = null
    private var frameIndex = 0
    private var lostFrames = 0

    val isInitialized: Boolean
        get() = previousRect != null

    fun reset() {
        nativeTracker = null
        templateGray?.release()
        templateGray = null
        initialMask?.release()
        initialMask = null
        previousRect = null
        frameIndex = 0
        lostFrames = 0
    }

    fun initialize(frame: Mat, rect: Rect, mask: Mat?): TrackedRoi? {
        reset()
        val safeRect = rect.clampedTo(frame.cols(), frame.rows()) ?: return null
        previousRect = safeRect
        initialMask = mask?.clone()
        templateGray = createGrayTemplate(frame, safeRect)
        nativeTracker = createMilTracker(frame, safeRect)
        lostFrames = 0
        return trackedRoiFor(safeRect)
    }

    fun update(frame: Mat, fallbackRect: Rect, fallbackMask: Mat?): TrackedRoi? {
        if (!isInitialized) return initialize(frame, fallbackRect, fallbackMask)

        frameIndex++
        val previous = previousRect ?: return initialize(frame, fallbackRect, fallbackMask)
        val trackedByMil = updateMil(frame)
        val candidate = trackedByMil?.let { Candidate(it, MIL_CONFIDENCE) } ?: updateByTemplate(frame)
        val safeRect = candidate
            ?.rect
            ?.clampedTo(frame.cols(), frame.rows())
            ?.takeIf { validateCandidate(previous, candidate, it, frame.cols(), frame.rows()) }
            ?: run {
                lostFrames++
                return null
            }

        lostFrames = 0
        previousRect = safeRect
        if (frameIndex % TEMPLATE_REFRESH_INTERVAL == 0) {
            refreshTemplate(frame, safeRect)
        }
        return trackedRoiFor(safeRect)
    }

    private fun createMilTracker(frame: Mat, rect: Rect): Tracker? {
        return try {
            TrackerMIL.create().also { it.init(frame, rect) }
        } catch (e: Throwable) {
            Log.w(TAG, "TrackerMIL unavailable, using template matching: ${e.message}")
            null
        }
    }

    private fun updateMil(frame: Mat): Rect? {
        val tracker = nativeTracker ?: return null
        val rect = previousRect ?: return null
        return try {
            val next = Rect(rect.x, rect.y, rect.width, rect.height)
            if (tracker.update(frame, next) && tracker.getTrackingScore() >= MIN_MIL_SCORE) {
                next
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TrackerMIL update failed, using template matching: ${e.message}")
            nativeTracker = null
            null
        }
    }

    private fun updateByTemplate(frame: Mat): Candidate? {
        val rect = previousRect ?: return null
        val template = templateGray ?: return null
        if (template.empty()) return null

        val searchRect = expandedSearchRect(rect, frame.cols(), frame.rows()) ?: return null
        if (searchRect.width < template.cols() || searchRect.height < template.rows()) return null

        val searchRgba = frame.submat(searchRect)
        val searchGray = Mat()
        val result = Mat()
        return try {
            toGray(searchRgba, searchGray)
            val resultCols = searchGray.cols() - template.cols() + 1
            val resultRows = searchGray.rows() - template.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) return null

            result.create(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(searchGray, template, result, Imgproc.TM_CCOEFF_NORMED)
            val match = Core.minMaxLoc(result)
            if (match.maxVal < MIN_TEMPLATE_CONFIDENCE) {
                return null
            }

            Candidate(
                rect = Rect(
                    searchRect.x + match.maxLoc.x.roundToInt(),
                    searchRect.y + match.maxLoc.y.roundToInt(),
                    template.cols(),
                    template.rows()
                ),
                confidence = match.maxVal
            )
        } finally {
            searchRgba.release()
            searchGray.release()
            result.release()
        }
    }

    private fun validateCandidate(
        previous: Rect,
        candidate: Candidate,
        safeRect: Rect,
        frameCols: Int,
        frameRows: Int
    ): Boolean {
        if (candidate.confidence < MIN_TEMPLATE_CONFIDENCE) return false

        val previousCenterX = previous.x + previous.width / 2.0
        val previousCenterY = previous.y + previous.height / 2.0
        val nextCenterX = safeRect.x + safeRect.width / 2.0
        val nextCenterY = safeRect.y + safeRect.height / 2.0
        val maxStep = max(previous.width, previous.height) * MAX_CENTER_STEP_RATIO
        if (abs(nextCenterX - previousCenterX) > maxStep || abs(nextCenterY - previousCenterY) > maxStep) {
            return false
        }

        val widthRatio = safeRect.width.toDouble() / previous.width.toDouble().coerceAtLeast(1.0)
        val heightRatio = safeRect.height.toDouble() / previous.height.toDouble().coerceAtLeast(1.0)
        if (widthRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO) return false
        if (heightRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO) return false

        val originalArea = (candidate.rect.width * candidate.rect.height).toDouble().coerceAtLeast(1.0)
        val visibleArea = (safeRect.width * safeRect.height).toDouble()
        if (visibleArea / originalArea < MIN_VISIBLE_AREA_RATIO) return false

        val touchesFrameEdge = safeRect.x <= 0 ||
            safeRect.y <= 0 ||
            safeRect.x + safeRect.width >= frameCols ||
            safeRect.y + safeRect.height >= frameRows
        if (touchesFrameEdge && lostFrames > 0) return false

        return true
    }

    private fun refreshTemplate(frame: Mat, rect: Rect) {
        val currentTemplate = templateGray ?: return
        val nextTemplate = createGrayTemplate(frame, rect)
        try {
            if (
                nextTemplate.cols() == currentTemplate.cols() &&
                nextTemplate.rows() == currentTemplate.rows()
            ) {
                Core.addWeighted(
                    currentTemplate,
                    1.0 - TEMPLATE_REFRESH_ALPHA,
                    nextTemplate,
                    TEMPLATE_REFRESH_ALPHA,
                    0.0,
                    currentTemplate
                )
            }
        } finally {
            nextTemplate.release()
        }
    }

    private fun createGrayTemplate(frame: Mat, rect: Rect): Mat {
        val roi = frame.submat(rect)
        val gray = Mat()
        return try {
            toGray(roi, gray)
            gray.clone()
        } finally {
            roi.release()
            gray.release()
        }
    }

    private fun toGray(input: Mat, output: Mat) {
        when (input.channels()) {
            4 -> Imgproc.cvtColor(input, output, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(input, output, Imgproc.COLOR_RGB2GRAY)
            1 -> input.copyTo(output)
            else -> Imgproc.cvtColor(input, output, Imgproc.COLOR_RGBA2GRAY)
        }
    }

    private fun trackedRoiFor(rect: Rect): TrackedRoi {
        val sourceMask = initialMask
        val mask = when {
            sourceMask == null || sourceMask.empty() -> null
            sourceMask.cols() == rect.width && sourceMask.rows() == rect.height -> sourceMask.clone()
            else -> Mat().also {
                Imgproc.resize(
                    sourceMask,
                    it,
                    Size(rect.width.toDouble(), rect.height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_NEAREST
                )
            }
        }
        return TrackedRoi(rect = rect, mask = mask)
    }

    private fun expandedSearchRect(rect: Rect, frameCols: Int, frameRows: Int): Rect? {
        val extraX = max(MIN_SEARCH_PADDING_PX, (rect.width * SEARCH_PADDING_RATIO).roundToInt())
        val extraY = max(MIN_SEARCH_PADDING_PX, (rect.height * SEARCH_PADDING_RATIO).roundToInt())
        return Rect(
            rect.x - extraX,
            rect.y - extraY,
            rect.width + (extraX * 2),
            rect.height + (extraY * 2)
        ).clampedTo(frameCols, frameRows)
    }

    private fun Rect.clampedTo(frameCols: Int, frameRows: Int): Rect? {
        if (frameCols <= 1 || frameRows <= 1 || width <= 1 || height <= 1) return null
        val left = x.coerceIn(0, frameCols - 1)
        val top = y.coerceIn(0, frameRows - 1)
        val right = (x + width).coerceIn(left + 1, frameCols)
        val bottom = (y + height).coerceIn(top + 1, frameRows)
        val safeWidth = right - left
        val safeHeight = bottom - top
        if (safeWidth <= 1 || safeHeight <= 1) return null
        return Rect(left, top, safeWidth, safeHeight)
    }

    data class TrackedRoi(
        val rect: Rect,
        val mask: Mat?
    )

    private data class Candidate(
        val rect: Rect,
        val confidence: Double
    )

    private companion object {
        private const val TAG = "OBJECT-ROI-TRACKER"
        private const val MIN_TEMPLATE_CONFIDENCE = 0.52
        private const val MIL_CONFIDENCE = 0.7
        private const val MIN_MIL_SCORE = 0.2f
        private const val SEARCH_PADDING_RATIO = 0.45
        private const val MIN_SEARCH_PADDING_PX = 32
        private const val TEMPLATE_REFRESH_INTERVAL = 6
        private const val TEMPLATE_REFRESH_ALPHA = 0.04
        private const val MAX_CENTER_STEP_RATIO = 1.15
        private const val MIN_SIZE_RATIO = 0.55
        private const val MAX_SIZE_RATIO = 1.85
        private const val MIN_VISIBLE_AREA_RATIO = 0.65
    }
}
