package com.example.gnssandopticalflowapp.model

data class OpticalFlowMetrics(
    val algorithm: String,
    val frameIndex: Long,
    val processTimeMs: Double,
    val instantFps: Double,
    val featureCount: Int,
    val activeVectorCount: Int,
    val avgDx: Double,
    val avgDy: Double,
    val avgMagnitude: Double,
    val confidence: Double,
    val threshold: Double,
    val sensitivity: Int
)
