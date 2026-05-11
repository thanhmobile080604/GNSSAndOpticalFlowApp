package com.example.gnssandopticalflowapp.model

data class BroadcastEphemerisSnapshot(
    val records: Map<SatelliteKey, List<BroadcastEphemerisRecord>>,
    val fetchedAtUtcMillis: Long,
    val sourceUrl: String
)
