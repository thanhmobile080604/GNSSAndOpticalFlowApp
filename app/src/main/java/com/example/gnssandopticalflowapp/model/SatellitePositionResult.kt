package com.example.gnssandopticalflowapp.model

data class SatellitePositionResult(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val ecefX: Double,
    val ecefY: Double,
    val ecefZ: Double
)