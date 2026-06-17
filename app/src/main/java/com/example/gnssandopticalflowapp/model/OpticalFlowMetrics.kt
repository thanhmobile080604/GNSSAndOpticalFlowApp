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
    val sensitivity: Int,
    /**
     * Signed horizontal directional consensus of the inlier flow vectors, `sum(dx) / sum(|dx|)`,
     * in [-1, 1]. Near 0 when the field diverges symmetrically from the vanishing point (driving
     * straight: left vectors go left, right vectors go right, so they cancel); approaches ±1 when
     * the whole field pans one way (turning). Used as the primary, speed-independent turn cue.
     */
    val lateralCoherence: Double = 0.0
)
