package com.example.gnssandopticalflowapp.model

data class VideoProcessOptions(
    val isMoving: Boolean,
    val useFarneback: Boolean,
    val sensitivity: Int,
    val useFarnebackHeatmap: Boolean = false,
    val roi: NormalizedRoi? = null
) {
    data class NormalizedRoi(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val viewAspectRatio: Float
    )
}
