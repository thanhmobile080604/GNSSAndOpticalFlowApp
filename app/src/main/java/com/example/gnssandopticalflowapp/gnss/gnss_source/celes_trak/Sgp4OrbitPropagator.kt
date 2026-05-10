package com.example.gnssandopticalflowapp.gnss.gnss_source.celes_trak

import com.example.gnssandopticalflowapp.gnss.gnss_source.approximation.SatelliteCalculator
import com.example.gnssandopticalflowapp.model.OrbitRecord
import com.example.gnssandopticalflowapp.model.OrbitStateResult
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScale
import org.orekit.time.TimeScalesFactory
import java.util.Date

object Sgp4OrbitPropagator {
    private const val MAX_PROPAGATOR_CACHE_SIZE = 256
    private val propagatorCache = LinkedHashMap<String, TLEPropagator>()

    fun propagate(
        orbit: OrbitRecord,
        observationUtcMillis: Long = System.currentTimeMillis()
    ): OrbitStateResult? {
        val line1 = orbit.tleLine1 ?: return null
        val line2 = orbit.tleLine2 ?: return null
        if (!TLE.isFormatOK(line1, line2)) return null

        return runCatching {
            val utc = TimeScalesFactory.getUTC()
            val date = AbsoluteDate(Date(observationUtcMillis), utc)
            val pv = getPropagator(line1, line2, utc).getPVCoordinates(date)

            OrbitStateResult(
                position = SatelliteCalculator.calculateSatellitePositionFromTeme(
                    temeX = pv.position.x,
                    temeY = pv.position.y,
                    temeZ = pv.position.z,
                    observationUtcMillis = observationUtcMillis
                ),
                speedMetersPerSecond = SatelliteCalculator.calculateSpeedFromEcefVelocity(
                    velocityX = pv.velocity.x,
                    velocityY = pv.velocity.y,
                    velocityZ = pv.velocity.z
                ) ?: 0.0
            )
        }.onFailure { e ->
            android.util.Log.e("SGP4", "Orekit propagation failed", e)
        }.getOrNull()
    }

    @Synchronized
    private fun getPropagator(
        line1: String,
        line2: String,
        utc: TimeScale
    ): TLEPropagator {
        val key = "$line1\n$line2"
        propagatorCache[key]?.let { return it }

        if (propagatorCache.size >= MAX_PROPAGATOR_CACHE_SIZE) {
            val oldestKey = propagatorCache.keys.firstOrNull()
            if (oldestKey != null) propagatorCache.remove(oldestKey)
        }

        return TLEPropagator.selectExtrapolator(TLE(line1, line2, utc)).also {
            propagatorCache[key] = it
        }
    }
}