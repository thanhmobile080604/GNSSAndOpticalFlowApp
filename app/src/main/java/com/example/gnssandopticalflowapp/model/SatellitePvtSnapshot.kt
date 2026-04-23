package com.example.gnssandopticalflowapp.model

import android.os.SystemClock

data class SatellitePvtSnapshot(
    val ecefX: Double,
    val ecefY: Double,
    val ecefZ: Double,
    val velocityXMetersPerSecond: Double?,
    val velocityYMetersPerSecond: Double?,
    val velocityZMetersPerSecond: Double?,
    val ephemerisSource: Int?,
    val capturedAtElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
)
