package com.example.gnssandopticalflowapp.model

data class CacheSnapshot(
    val records: Map<SatelliteKey, OrbitRecord>,
    val fetchedAtUtcMillis: Long
)
