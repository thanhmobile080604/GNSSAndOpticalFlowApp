package com.example.gnssandopticalflowapp.model

import java.lang.reflect.Method

data class ReflectionAccess(
    val hasSatellitePvt: Method,
    val getSatellitePvt: Method,
    val getPositionEcef: Method,
    val getVelocityEcef: Method,
    val getEphemerisSource: Method?,
    val getPositionX: Method,
    val getPositionY: Method,
    val getPositionZ: Method,
    val getVelocityX: Method,
    val getVelocityY: Method,
    val getVelocityZ: Method
)