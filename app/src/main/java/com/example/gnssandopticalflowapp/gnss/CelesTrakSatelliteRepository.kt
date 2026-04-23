package com.example.gnssandopticalflowapp.gnss

import android.location.GnssStatus
import com.example.gnssandopticalflowapp.model.CacheSnapshot
import com.example.gnssandopticalflowapp.model.GroupRequest
import com.example.gnssandopticalflowapp.model.OrbitRecord
import com.example.gnssandopticalflowapp.model.SatelliteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object CelesTrakSatelliteRepository {
    private const val BASE_URL = "https://celestrak.org/NORAD/elements/gp.php"
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private val supportedGroups = listOf(
        GroupRequest("GPS-OPS", GnssStatus.CONSTELLATION_GPS),
        GroupRequest("GALILEO", GnssStatus.CONSTELLATION_GALILEO),
        GroupRequest("BEIDOU", GnssStatus.CONSTELLATION_BEIDOU)
    )

    @Volatile
    private var cachedSnapshot: CacheSnapshot? = null

    suspend fun getSnapshot(forceRefresh: Boolean = false): CacheSnapshot? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedSnapshot
        if (!forceRefresh && cached != null && (now - cached.fetchedAtUtcMillis) < CACHE_TTL_MS) {
            return@withContext cached
        }

        runCatching {
            val records = LinkedHashMap<SatelliteKey, OrbitRecord>()
            supportedGroups.forEach { group ->
                fetchGroup(group).forEach { orbit ->
                    val existing = records[orbit.key]
                    if (existing == null || orbit.epochUtcMillis > existing.epochUtcMillis) {
                        records[orbit.key] = orbit
                    }
                }
            }

            CacheSnapshot(
                records = records,
                fetchedAtUtcMillis = now
            ).also { snapshot ->
                cachedSnapshot = snapshot
            }
        }.getOrElse {
            cached
        }
    }

    private fun fetchGroup(group: GroupRequest): List<OrbitRecord> {
        val url = URL("$BASE_URL?GROUP=${group.groupName}&FORMAT=JSON")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GNSSAndOpticalFlowApp/1.0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $responseCode for group ${group.groupName}")
            }

            val body = connection.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }.trim()
            if (!body.startsWith("[")) {
                throw IllegalStateException("Unexpected response for group ${group.groupName}")
            }

            val array = JSONArray(body)
            buildList {
                for (index in 0 until array.length()) {
                    parseOrbitRecord(
                        json = array.getJSONObject(index),
                        constellationType = group.constellationType
                    )?.let(::add)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOrbitRecord(
        json: JSONObject,
        constellationType: Int
    ): OrbitRecord? {
        val objectName = json.optString("OBJECT_NAME").orEmpty()
        val svid = parseSvid(constellationType, objectName) ?: return null
        val epochUtcMillis = parseEpochUtcMillis(json.optString("EPOCH")) ?: return null
        val meanMotionRevPerDay = json.optDouble("MEAN_MOTION", Double.NaN)
        val eccentricity = json.optDouble("ECCENTRICITY", Double.NaN)
        val inclinationDeg = json.optDouble("INCLINATION", Double.NaN)
        val raanDeg = json.optDouble("RA_OF_ASC_NODE", Double.NaN)
        val argOfPerigeeDeg = json.optDouble("ARG_OF_PERICENTER", Double.NaN)
        val meanAnomalyDeg = json.optDouble("MEAN_ANOMALY", Double.NaN)

        if (
            meanMotionRevPerDay.isNaN() ||
            eccentricity.isNaN() ||
            inclinationDeg.isNaN() ||
            raanDeg.isNaN() ||
            argOfPerigeeDeg.isNaN() ||
            meanAnomalyDeg.isNaN()
        ) {
            return null
        }

        return OrbitRecord(
            key = SatelliteKey(
                constellationType = constellationType,
                svid = svid
            ),
            objectName = objectName,
            noradCatalogId = json.optInt("NORAD_CAT_ID").takeIf { it > 0 },
            epochUtcMillis = epochUtcMillis,
            inclinationDeg = inclinationDeg,
            raanDeg = raanDeg,
            eccentricity = eccentricity,
            argOfPerigeeDeg = argOfPerigeeDeg,
            meanAnomalyDeg = meanAnomalyDeg,
            meanMotionRevPerDay = meanMotionRevPerDay
        )
    }

    private fun parseSvid(constellationType: Int, objectName: String): Int? {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> {
                Regex("""PRN\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
                    .find(objectName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

            GnssStatus.CONSTELLATION_GALILEO -> {
                Regex("""GALILEO\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
                    .find(objectName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

            GnssStatus.CONSTELLATION_BEIDOU -> {
                Regex("""\(([Cc])(\d{1,2})\)""")
                    .find(objectName)
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()
            }

            else -> null
        }
    }

    private fun parseEpochUtcMillis(epochText: String?): Long? {
        if (epochText.isNullOrBlank()) return null

        val normalized = epochText.trim().removeSuffix("Z")
        val parts = normalized.split('.')
        val base = parts.firstOrNull() ?: return null
        val millis = parts.getOrNull(1)
            ?.take(3)
            ?.padEnd(3, '0')
            ?.toIntOrNull()
            ?: 0

        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

        val baseMillis = formatter.parse(base)?.time ?: return null
        return baseMillis + millis
    }
}
