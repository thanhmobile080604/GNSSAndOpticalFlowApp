package com.example.gnssandopticalflowapp.model

data class VideoProgressMetadata(
    val durationMs: Long,
    val frameCount: Long,
    val fps: Double,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0
)
