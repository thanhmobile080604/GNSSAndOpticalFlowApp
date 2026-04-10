package com.example.gnssandopticalflowapp.model

import java.io.Serializable

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
    var worldZ: Float = 0f,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var speed: Double = 0.0
) : Serializable
