package com.example.gnssandopticalflowapp.screen.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.OpticalFlowMetrics

class CameraOpticalFlowViewModel : ViewModel() {
    enum class MotionControlMode {
        AUTO,
        STILL,
        MOVING
    }

    data class NormalizedRoi(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val viewAspectRatio: Float,
        val pathPoints: List<NormalizedPoint> = emptyList()
    )

    data class NormalizedPoint(
        val x: Float,
        val y: Float
    )

    @Volatile
    var currentFrameWidth = 0
    @Volatile
    var currentFrameHeight = 0
    @Volatile
    var frameCount = 0

    @Volatile
    var isRecording = false
    @Volatile
    var recordedFilePath = ""
    var useFarneback = false
    var useFarnebackHeatmap = false
    var roiEnabled = false
    @Volatile
    var normalizedRoi: NormalizedRoi? = null
    var motionControlMode = MotionControlMode.AUTO

    var timerStartTime = 0L
    var elapsedBeforePause = 0L

    @Volatile
    var isMovingMode = false
    @Volatile
    var isMovingModeManualOverride = false
    private var phoneMovingHoldFrames = 0

    @Volatile
    var isAnalysisActive = false
        private set
    var restoreSensitivity = 50

    private var analysisStartedAtWallMs = 0L
    private var analysisStartedAtElapsedMs = 0L
    private var analysisFrameIndex = 0L
    private var lastAnalysisSampleElapsedMs = -ANALYSIS_SAMPLE_INTERVAL_MS
    private val analysisLock = Any()
    private val analysisSamples = mutableListOf<AnalyticsSample>()

    fun startTimer(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        timerStartTime = nowElapsedMs
    }

    fun stopTimer(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (timerStartTime > 0L) {
            elapsedBeforePause += nowElapsedMs - timerStartTime
        }
        timerStartTime = 0L
        return elapsedBeforePause
    }

    fun resetTimer() {
        timerStartTime = 0L
        elapsedBeforePause = 0L
    }

    fun currentTimerElapsed(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        return elapsedBeforePause + (nowElapsedMs - timerStartTime).coerceAtLeast(0L)
    }

    fun setMovingMode(isMoving: Boolean, manualOverride: Boolean) {
        isMovingMode = isMoving
        isMovingModeManualOverride = manualOverride
    }

    fun resetPhoneMotionHold() {
        phoneMovingHoldFrames = 0
    }

    fun detectPhoneMoving(accelerationMagnitude: Double): Boolean? {
        if (isMovingModeManualOverride) return null

        if (accelerationMagnitude > PHONE_MOVING_ACCELERATION_THRESHOLD) {
            phoneMovingHoldFrames = PHONE_MOVING_HOLD_FRAME_COUNT
        } else if (phoneMovingHoldFrames > 0) {
            phoneMovingHoldFrames--
        }

        val detectedMoving = phoneMovingHoldFrames > 0
        return detectedMoving.takeIf { it != isMovingMode }
    }

    fun startAnalysis(sensitivity: Int) {
        synchronized(analysisLock) {
            analysisSamples.clear()
            analysisFrameIndex = 0L
            lastAnalysisSampleElapsedMs = -ANALYSIS_SAMPLE_INTERVAL_MS
        }
        analysisStartedAtWallMs = System.currentTimeMillis()
        analysisStartedAtElapsedMs = SystemClock.elapsedRealtime()
        restoreSensitivity = sensitivity
        isAnalysisActive = true
    }

    fun finishAnalysis(saveSession: Boolean): CompletedAnalysisSession? {
        if (!isAnalysisActive) return null

        val endedAtMs = System.currentTimeMillis()
        val durationMs = (SystemClock.elapsedRealtime() - analysisStartedAtElapsedMs).coerceAtLeast(0L)
        val samples = synchronized(analysisLock) { analysisSamples.toList() }
        val startedAtMs = analysisStartedAtWallMs
        val movingMode = isMovingMode

        isAnalysisActive = false

        if (!saveSession || samples.isEmpty()) return null
        return CompletedAnalysisSession(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            durationMs = durationMs,
            movingMode = movingMode,
            samples = samples
        )
    }

    fun recordAnalysisSample(
        kltMetrics: OpticalFlowMetrics?,
        farnebackMetrics: OpticalFlowMetrics?
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - analysisStartedAtElapsedMs
        synchronized(analysisLock) {
            if (elapsedMs - lastAnalysisSampleElapsedMs < ANALYSIS_SAMPLE_INTERVAL_MS &&
                analysisSamples.isNotEmpty()
            ) {
                return
            }
            lastAnalysisSampleElapsedMs = elapsedMs
            analysisSamples.add(
                AnalyticsSample(
                    elapsedMs = elapsedMs,
                    frameIndex = analysisFrameIndex++,
                    kltFps = kltMetrics?.instantFps ?: 0.0,
                    farnebackFps = farnebackMetrics?.instantFps ?: 0.0,
                    kltProcessMs = kltMetrics?.processTimeMs ?: 0.0,
                    farnebackProcessMs = farnebackMetrics?.processTimeMs ?: 0.0,
                    kltFeatureCount = kltMetrics?.featureCount ?: 0,
                    farnebackSampleCount = farnebackMetrics?.featureCount ?: 0,
                    kltActiveVectorCount = kltMetrics?.activeVectorCount ?: 0,
                    farnebackActiveVectorCount = farnebackMetrics?.activeVectorCount ?: 0,
                    kltAvgDx = kltMetrics?.avgDx ?: 0.0,
                    kltAvgDy = kltMetrics?.avgDy ?: 0.0,
                    farnebackAvgDx = farnebackMetrics?.avgDx ?: 0.0,
                    farnebackAvgDy = farnebackMetrics?.avgDy ?: 0.0,
                    kltAvgMagnitude = kltMetrics?.avgMagnitude ?: 0.0,
                    farnebackAvgMagnitude = farnebackMetrics?.avgMagnitude ?: 0.0,
                    kltConfidence = kltMetrics?.confidence ?: 0.0,
                    farnebackConfidence = farnebackMetrics?.confidence ?: 0.0,
                    kltThreshold = kltMetrics?.threshold ?: 0.0,
                    farnebackThreshold = farnebackMetrics?.threshold ?: 0.0
                )
            )
        }
    }

    data class CompletedAnalysisSession(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val movingMode: Boolean,
        val samples: List<AnalyticsSample>
    )

    private companion object {
        const val ANALYSIS_SAMPLE_INTERVAL_MS = 250L
        const val PHONE_MOVING_ACCELERATION_THRESHOLD = 0.25
        const val PHONE_MOVING_HOLD_FRAME_COUNT = 12
    }
}
