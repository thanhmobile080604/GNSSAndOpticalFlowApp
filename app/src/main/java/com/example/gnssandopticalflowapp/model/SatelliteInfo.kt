package com.example.gnssandopticalflowapp.model

data class SatelliteInfo(
    val svid: Int,
    val constellationType: Int,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float,
    var worldX: Float = 0f, // 3D mapped positions
    var worldY: Float = 0f,
    var worldZ: Float = 0f
)
