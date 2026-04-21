package com.example.gnssandopticalflowapp.gnss

import android.location.GnssMeasurement
import android.os.SystemClock
import java.lang.reflect.Method

object GnssSatellitePvtResolver {
    data class ProbeResult(
        val snapshot: SatellitePvtSnapshot?,
        val reason: String
    )

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

    private data class ReflectionAccess(
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

    @Volatile
    private var cachedAccess: ReflectionAccess? = null

    @Volatile
    private var lookupAttempted = false

    @Volatile
    private var lookupFailed = false

    fun extractPvt(measurement: GnssMeasurement): SatellitePvtSnapshot? {
        return probeMeasurement(measurement).snapshot
    }

    fun probeMeasurement(measurement: GnssMeasurement): ProbeResult {
        val access = getReflectionAccess(measurement)
            ?: return ProbeResult(
                snapshot = null,
                reason = "SatellitePvt API unavailable on runtime or blocked by reflection"
            )

        return try {
            val hasPvt = access.hasSatellitePvt.invoke(measurement) as? Boolean ?: false
            if (!hasPvt) {
                return ProbeResult(
                    snapshot = null,
                    reason = "Measurement.hasSatellitePvt() == false"
                )
            }

            val pvt = access.getSatellitePvt.invoke(measurement)
                ?: return ProbeResult(
                    snapshot = null,
                    reason = "Measurement.getSatellitePvt() returned null"
                )
            val positionEcef = access.getPositionEcef.invoke(pvt)
                ?: return ProbeResult(
                    snapshot = null,
                    reason = "SatellitePvt returned null PositionEcef"
                )
            val velocityEcef = access.getVelocityEcef.invoke(pvt)

            ProbeResult(
                snapshot = SatellitePvtSnapshot(
                    ecefX = (access.getPositionX.invoke(positionEcef) as Number).toDouble(),
                    ecefY = (access.getPositionY.invoke(positionEcef) as Number).toDouble(),
                    ecefZ = (access.getPositionZ.invoke(positionEcef) as Number).toDouble(),
                    velocityXMetersPerSecond = velocityEcef?.let {
                        (access.getVelocityX.invoke(it) as Number).toDouble()
                    },
                    velocityYMetersPerSecond = velocityEcef?.let {
                        (access.getVelocityY.invoke(it) as Number).toDouble()
                    },
                    velocityZMetersPerSecond = velocityEcef?.let {
                        (access.getVelocityZ.invoke(it) as Number).toDouble()
                    },
                    ephemerisSource = access.getEphemerisSource?.invoke(pvt)?.let {
                        (it as Number).toInt()
                    }
                ),
                reason = "SatellitePvt extracted successfully"
            )
        } catch (error: Throwable) {
            lookupFailed = true
            ProbeResult(
                snapshot = null,
                reason = "Exception while reading SatellitePvt: ${error.javaClass.simpleName}"
            )
        }
    }

    fun getEphemerisSourceLabel(ephemerisSource: Int?): String? {
        return when (ephemerisSource) {
            0 -> "Broadcast"
            1 -> "Server normal"
            2 -> "Server long-term"
            3 -> "Other"
            else -> null
        }
    }

    private fun getReflectionAccess(measurement: GnssMeasurement): ReflectionAccess? {
        cachedAccess?.let { return it }
        if (lookupAttempted && lookupFailed) return null

        synchronized(this) {
            cachedAccess?.let { return it }
            if (lookupAttempted && lookupFailed) return null

            lookupAttempted = true
            return try {
                val measurementClass = measurement.javaClass
                val hasSatellitePvt = measurementClass.getMethod("hasSatellitePvt")
                val getSatellitePvt = measurementClass.getMethod("getSatellitePvt")

                val pvtClass = getSatellitePvt.returnType
                val getPositionEcef = pvtClass.getMethod("getPositionEcef")
                val getVelocityEcef = pvtClass.getMethod("getVelocityEcef")
                val getEphemerisSource = pvtClass.methods.firstOrNull { it.name == "getEphemerisSource" }

                val positionClass = getPositionEcef.returnType
                val velocityClass = getVelocityEcef.returnType

                ReflectionAccess(
                    hasSatellitePvt = hasSatellitePvt,
                    getSatellitePvt = getSatellitePvt,
                    getPositionEcef = getPositionEcef,
                    getVelocityEcef = getVelocityEcef,
                    getEphemerisSource = getEphemerisSource,
                    getPositionX = positionClass.getMethod("getXMeters"),
                    getPositionY = positionClass.getMethod("getYMeters"),
                    getPositionZ = positionClass.getMethod("getZMeters"),
                    getVelocityX = velocityClass.getMethod("getXMetersPerSecond"),
                    getVelocityY = velocityClass.getMethod("getYMetersPerSecond"),
                    getVelocityZ = velocityClass.getMethod("getZMetersPerSecond")
                ).also {
                    cachedAccess = it
                }
            } catch (error: Throwable) {
                lookupFailed = true
                null
            }
        }
    }
}
