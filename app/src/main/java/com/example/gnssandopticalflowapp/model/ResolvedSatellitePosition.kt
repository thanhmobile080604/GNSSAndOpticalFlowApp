package com.example.gnssandopticalflowapp.model

data class ResolvedSatellitePosition(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val positionSource: String = "Approximate",
    val ephemerisSource: String? = null
)
