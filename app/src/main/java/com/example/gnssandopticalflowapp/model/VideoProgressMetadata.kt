package com.example.gnssandopticalflowapp.model

data class VideoProgressMetadata(
    val durationMs: Long,
    val frameCount: Long,
    val fps: Double
)
