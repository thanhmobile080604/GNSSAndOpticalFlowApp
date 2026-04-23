package com.example.gnssandopticalflowapp.model

data class ProbeResult(
    val snapshot: SatellitePvtSnapshot?,
    val reason: String
)
