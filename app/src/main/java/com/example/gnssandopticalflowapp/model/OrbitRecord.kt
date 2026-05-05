package com.example.gnssandopticalflowapp.model

data class OrbitRecord(
    val key: SatelliteKey,
    val objectName: String,
    val noradCatalogId: Int?,
    val epochUtcMillis: Long,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val eccentricity: Double,
    val argOfPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    val meanMotionRevPerDay: Double,
    val tleLine1: String? = null,
    val tleLine2: String? = null
)
