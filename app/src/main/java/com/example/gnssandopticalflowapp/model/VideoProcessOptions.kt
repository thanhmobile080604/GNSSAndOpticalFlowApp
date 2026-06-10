package com.example.gnssandopticalflowapp.model

import java.io.Serializable

data class VideoProcessOptions(
    val isMoving: Boolean,
    val useFarneback: Boolean,
    val sensitivity: Int,
    val useFarnebackHeatmap: Boolean = false,
    val useAi: Boolean = false,
    val roi: NormalizedRoi? = null,
    val processingMode: ProcessingMode = ProcessingMode.OFFLINE
) : Serializable {
    enum class ProcessingMode : Serializable {
        OFFLINE,
        ONLINE,
        OUTER_SERVER
    }

    data class NormalizedRoi(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val viewAspectRatio: Float,
        val pathPoints: List<NormalizedPoint> = emptyList()
    ) : Serializable

    data class NormalizedPoint(
        val x: Float,
        val y: Float
    ) : Serializable
}
