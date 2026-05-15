package com.example.gnssandopticalflowapp.model

data class AnalyticsSample(
    val elapsedMs: Long,
    val frameIndex: Long,
    val kltFps: Double,
    val farnebackFps: Double,
    val kltProcessMs: Double,
    val farnebackProcessMs: Double,
    val kltFeatureCount: Int,
    val farnebackSampleCount: Int,
    val kltActiveVectorCount: Int,
    val farnebackActiveVectorCount: Int,
    val kltAvgDx: Double,
    val kltAvgDy: Double,
    val farnebackAvgDx: Double,
    val farnebackAvgDy: Double,
    val kltAvgMagnitude: Double,
    val farnebackAvgMagnitude: Double,
    val kltConfidence: Double,
    val farnebackConfidence: Double,
    val kltThreshold: Double,
    val farnebackThreshold: Double
)

data class AnalyticsSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val kltSensitivity: Int,
    val farnebackSensitivity: Int,
    val movingMode: Boolean,
    val samples: List<AnalyticsSample>
)

data class AnalyticsSessionSummary(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val avgKltFps: Double,
    val avgFarnebackFps: Double,
    val avgKltConfidence: Double,
    val avgFarnebackConfidence: Double
)
