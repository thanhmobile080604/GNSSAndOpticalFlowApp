package com.example.gnssandopticalflowapp.model

data class BroadcastEphemerisRecord(
    val key: SatelliteKey,
    val satelliteId: String,
    val sourceName: String,
    val sourceUrl: String,
    val epochUtcMillis: Long,
    val message: Any
)
