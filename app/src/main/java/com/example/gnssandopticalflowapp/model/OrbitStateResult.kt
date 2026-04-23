package com.example.gnssandopticalflowapp.model

data class OrbitStateResult(
    val position: SatellitePositionResult,
    val speedMetersPerSecond: Double
)
