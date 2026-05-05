package com.example.gnssandopticalflowapp.gnss.gnss_source.celes_trak

import android.location.GnssStatus
import com.example.gnssandopticalflowapp.model.CacheSnapshot
import com.example.gnssandopticalflowapp.model.GroupRequest
import com.example.gnssandopticalflowapp.model.OrbitRecord
import com.example.gnssandopticalflowapp.model.SatelliteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.collections.forEach

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

    suspend fun getSnapshot(forceRefresh: Boolean = false): CacheSnapshot? =
        withContext(Dispatchers.IO) {
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
        val url = URL("$BASE_URL?GROUP=${group.groupName}&FORMAT=TLE")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/plain")
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
            val records = parseTleRecords(body, group.constellationType)
            if (records.isEmpty()) {
                throw IllegalStateException("Unexpected response for group ${group.groupName}")
            }

            records
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTleRecords(
        tleText: String,
        constellationType: Int
    ): List<OrbitRecord> {
        val lines = tleText
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()

        return buildList {
            var index = 0
            while (index < lines.size) {
                val name = lines[index].takeIf { !it.startsWith("1 ") && !it.startsWith("2 ") }
                val line1Index = if (name != null) index + 1 else index
                val line2Index = line1Index + 1
                val line1 = lines.getOrNull(line1Index)
                val line2 = lines.getOrNull(line2Index)

                if (line1?.startsWith("1 ") == true && line2?.startsWith("2 ") == true) {
                    parseOrbitRecord(
                        objectName = name.orEmpty(),
                        line1 = line1,
                        line2 = line2,
                        constellationType = constellationType
                    )?.let(::add)
                    index = line2Index + 1
                } else {
                    index++
                }
            }
        }
    }

    private fun parseOrbitRecord(
        objectName: String,
        line1: String,
        line2: String,
        constellationType: Int
    ): OrbitRecord? {
        val svid = parseSvid(constellationType, objectName) ?: return null
        val epochUtcMillis = parseTleEpochUtcMillis(line1) ?: return null
        val inclinationDeg = line2.substringOrNull(8, 16)?.trim()?.toDoubleOrNull() ?: return null
        val raanDeg = line2.substringOrNull(17, 25)?.trim()?.toDoubleOrNull() ?: return null
        val eccentricity = line2.substringOrNull(26, 33)?.trim()?.let { "0.$it".toDoubleOrNull() } ?: return null
        val argOfPerigeeDeg = line2.substringOrNull(34, 42)?.trim()?.toDoubleOrNull() ?: return null
        val meanAnomalyDeg = line2.substringOrNull(43, 51)?.trim()?.toDoubleOrNull() ?: return null
        val meanMotionRevPerDay = line2.substringOrNull(52, 63)?.trim()?.toDoubleOrNull() ?: return null

        return OrbitRecord(
            key = SatelliteKey(
                constellationType = constellationType,
                svid = svid
            ),
            objectName = objectName,
            noradCatalogId = line1.substringOrNull(2, 7)?.trim()?.toIntOrNull(),
            epochUtcMillis = epochUtcMillis,
            inclinationDeg = inclinationDeg,
            raanDeg = raanDeg,
            eccentricity = eccentricity,
            argOfPerigeeDeg = argOfPerigeeDeg,
            meanAnomalyDeg = meanAnomalyDeg,
            meanMotionRevPerDay = meanMotionRevPerDay,
            tleLine1 = line1,
            tleLine2 = line2
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

    private fun parseTleEpochUtcMillis(line1: String): Long? {
        val epochYear = line1.substringOrNull(18, 20)?.trim()?.toIntOrNull() ?: return null
        val dayOfYear = line1.substringOrNull(20, 32)?.trim()?.toDoubleOrNull() ?: return null
        val fullYear = if (epochYear < 57) 2000 + epochYear else 1900 + epochYear
        val wholeDay = dayOfYear.toInt()
        val fractionalDay = dayOfYear - wholeDay

        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).run {
            clear()
            set(Calendar.YEAR, fullYear)
            set(Calendar.DAY_OF_YEAR, wholeDay)
            timeInMillis + (fractionalDay * 86_400_000.0).toLong()
        }
    }

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? {
        if (length < endIndex || startIndex < 0 || startIndex >= endIndex) return null
        return substring(startIndex, endIndex)
    }
}