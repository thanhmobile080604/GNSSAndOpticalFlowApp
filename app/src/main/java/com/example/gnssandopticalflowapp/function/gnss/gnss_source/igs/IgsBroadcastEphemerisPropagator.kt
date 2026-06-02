package com.example.gnssandopticalflowapp.function.gnss.gnss_source.igs

import android.util.Log
import com.example.gnssandopticalflowapp.function.gnss.gnss_source.approximation.SatelliteCalculator
import com.example.gnssandopticalflowapp.model.BroadcastEphemerisRecord
import com.example.gnssandopticalflowapp.model.OrbitStateResult
import org.orekit.data.DataContext
import org.orekit.propagation.analytical.gnss.GNSSPropagator
import org.orekit.propagation.analytical.gnss.data.GLONASSNavigationMessage
import org.orekit.propagation.analytical.gnss.data.GNSSOrbitalElements
import org.orekit.propagation.numerical.GLONASSNumericalPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.IERSConventions
import org.orekit.utils.PVCoordinates
import java.util.Date

object IgsBroadcastEphemerisPropagator {
    private const val TAG = "GNSS_IGS"
    private const val MAX_PROPAGATOR_CACHE_SIZE = 256
    private val gnssPropagatorCache = LinkedHashMap<String, GNSSPropagator>()
    private val glonassPropagatorCache = LinkedHashMap<String, GLONASSNumericalPropagator>()

    fun propagate(
        record: BroadcastEphemerisRecord,
        observationUtcMillis: Long = System.currentTimeMillis()
    ): OrbitStateResult? {
        return runCatching {
            val utc = TimeScalesFactory.getUTC()
            val date = AbsoluteDate(Date(observationUtcMillis), utc)
            val pv = propagateToEcef(record, date)

            OrbitStateResult(
                position = SatelliteCalculator.calculateSatellitePositionFromEcef(
                    ecefX = pv.position.x,
                    ecefY = pv.position.y,
                    ecefZ = pv.position.z
                ),
                speedMetersPerSecond = SatelliteCalculator.calculateInertialSpeedFromEcefState(
                    ecefX = pv.position.x,
                    ecefY = pv.position.y,
                    velocityX = pv.velocity.x,
                    velocityY = pv.velocity.y,
                    velocityZ = pv.velocity.z
                ) ?: 0.0
            )
        }.onFailure {
            Log.w(TAG, "propagation failed ${record.satelliteId}: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private fun propagateToEcef(
        record: BroadcastEphemerisRecord,
        date: AbsoluteDate
    ): PVCoordinates {
        return when (val message = record.message) {
            is GNSSOrbitalElements -> getGnssPropagator(record, message).propagateInEcef(date)
            is GLONASSNavigationMessage -> {
                val context = DataContext.getDefault()
                val pz90 = context.frames.getPZ9011(IERSConventions.IERS_2010, true)
                getGlonassPropagator(record, message).propagate(date).getPVCoordinates(pz90)
            }
            else -> throw IllegalArgumentException("Unsupported broadcast message ${message.javaClass.name}")
        }
    }

    @Synchronized
    private fun getGnssPropagator(
        record: BroadcastEphemerisRecord,
        message: GNSSOrbitalElements
    ): GNSSPropagator {
        val cacheKey = "${record.sourceName}:${record.satelliteId}:${record.epochUtcMillis}"
        gnssPropagatorCache[cacheKey]?.let { return it }

        if (gnssPropagatorCache.size >= MAX_PROPAGATOR_CACHE_SIZE) {
            gnssPropagatorCache.remove(gnssPropagatorCache.keys.first())
        }

        return message.propagator.also {
            gnssPropagatorCache[cacheKey] = it
        }
    }

    @Synchronized
    private fun getGlonassPropagator(
        record: BroadcastEphemerisRecord,
        message: GLONASSNavigationMessage
    ): GLONASSNumericalPropagator {
        val cacheKey = "${record.sourceName}:${record.satelliteId}:${record.epochUtcMillis}"
        glonassPropagatorCache[cacheKey]?.let { return it }

        if (glonassPropagatorCache.size >= MAX_PROPAGATOR_CACHE_SIZE) {
            glonassPropagatorCache.remove(glonassPropagatorCache.keys.first())
        }

        return message.getPropagator(60.0).also {
            glonassPropagatorCache[cacheKey] = it
        }
    }
}
