package com.example.gnssandopticalflowapp.gnss.gnss_source.igs

import android.location.GnssStatus
import android.util.Log
import com.example.gnssandopticalflowapp.model.BroadcastEphemerisRecord
import com.example.gnssandopticalflowapp.model.BroadcastEphemerisSnapshot
import com.example.gnssandopticalflowapp.model.SatelliteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orekit.data.DataSource
import org.orekit.files.rinex.navigation.RinexNavigation
import org.orekit.files.rinex.navigation.RinexNavigationParser
import org.orekit.propagation.analytical.gnss.data.GLONASSNavigationMessage
import org.orekit.propagation.analytical.gnss.data.GNSSOrbitalElements
import org.orekit.time.DateComponents
import org.orekit.time.GNSSDate
import org.orekit.time.TimeScalesFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.abs

object IgsBroadcastEphemerisRepository {
    private const val TAG = "GNSS_IGS"
    private const val BASE_URL = "https://igs.bkg.bund.de/root_ftp/IGS/BRDC"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val DOWNLOAD_ATTEMPTS = 2
    private const val QZSS_ANDROID_SVID_OFFSET = 192
    private const val MAX_RECORD_AGE_MS = 36 * 60 * 60 * 1000L

    private val systemFileSuffixes = listOf("GN", "EN", "CN", "RN", "JN", "IN", "SN")

    @Volatile
    private var cachedSnapshot: BroadcastEphemerisSnapshot? = null

    suspend fun getSnapshot(forceRefresh: Boolean = false): BroadcastEphemerisSnapshot? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedSnapshot
            if (!forceRefresh && cached != null && (now - cached.fetchedAtUtcMillis) < CACHE_TTL_MS) {
                Log.d(TAG, "using cached records=${cached.records.values.sumOf { it.size }}")
                return@withContext cached
            }

            for (batch in buildCandidateBatches(now)) {
                val snapshot = fetchBatch(batch, now)
                if (snapshot.records.isNotEmpty()) {
                    cachedSnapshot = snapshot
                    Log.d(
                        TAG,
                        "refresh ok source=${snapshot.sourceUrl} records=${snapshot.records.values.sumOf { it.size }}"
                    )
                    return@withContext snapshot
                }
            }

            cached
        }

    private fun fetchBatch(
        requests: List<IgsFileRequest>,
        nowUtcMillis: Long
    ): BroadcastEphemerisSnapshot {
        val mergedRecords = linkedMapOf<SatelliteKey, MutableList<BroadcastEphemerisRecord>>()
        val successfulSources = mutableListOf<String>()

        requests.forEach { request ->
            val snapshot = runCatching {
                fetchAndParse(request, nowUtcMillis)
            }.onFailure {
                logFetchFailure(request, it)
            }.getOrNull()

            if (snapshot != null && snapshot.records.isNotEmpty()) {
                successfulSources += request.fileName
                snapshot.records.forEach { (key, records) ->
                    mergedRecords.getOrPut(key) { mutableListOf() }.addAll(records)
                }
            }
        }

        return BroadcastEphemerisSnapshot(
            records = mergedRecords,
            fetchedAtUtcMillis = nowUtcMillis,
            sourceUrl = successfulSources.joinToString(separator = ", ").ifBlank {
                requests.joinToString(separator = ", ") { it.fileName }
            }
        )
    }

    private fun fetchAndParse(
        request: IgsFileRequest,
        nowUtcMillis: Long
    ): BroadcastEphemerisSnapshot {
        Log.d(TAG, "fetch ${request.url}")
        val compressedBytes = downloadBytesWithRetry(request)
        val rinexText = GZIPInputStream(ByteArrayInputStream(compressedBytes))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        configureGnssRolloverReference(nowUtcMillis)
        val navigation = RinexNavigationParser().parse(
            DataSource(request.fileName, DataSource.ReaderOpener { StringReader(rinexText) })
        )
        val records = extractRecords(
            navigation = navigation,
            sourceName = request.fileName.substringBefore(".rnx"),
            sourceUrl = request.url,
            nowUtcMillis = nowUtcMillis
        )

        return BroadcastEphemerisSnapshot(
            records = records,
            fetchedAtUtcMillis = nowUtcMillis,
            sourceUrl = request.url
        )
    }

    private fun downloadBytesWithRetry(request: IgsFileRequest): ByteArray {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return downloadBytes(request)
            } catch (error: Throwable) {
                lastError = error
                if (error is HttpStatusException && error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw error
                }
                if (attempt < DOWNLOAD_ATTEMPTS - 1) {
                    Log.d(TAG, "retry download ${request.fileName} attempt=${attempt + 2}/$DOWNLOAD_ATTEMPTS")
                }
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private fun downloadBytes(request: IgsFileRequest): ByteArray {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/gzip, application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", "GNSSAndOpticalFlowApp/1.0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(responseCode)
            }

            val output = ByteArrayOutputStream(connection.contentLength.coerceAtLeast(32 * 1024))
            connection.inputStream.use { input ->
                input.copyTo(output)
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRecords(
        navigation: RinexNavigation,
        sourceName: String,
        sourceUrl: String,
        nowUtcMillis: Long
    ): Map<SatelliteKey, List<BroadcastEphemerisRecord>> {
        val records = linkedMapOf<SatelliteKey, MutableList<BroadcastEphemerisRecord>>()

        addRecords(
            records = records,
            messagesBySatellite = navigation.gpsLegacyNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_GPS,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addRecords(
            records = records,
            messagesBySatellite = navigation.gpsCivilianNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_GPS,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addRecords(
            records = records,
            messagesBySatellite = navigation.galileoNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_GALILEO,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addRecords(
            records = records,
            messagesBySatellite = navigation.beidouLegacyNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_BEIDOU,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addRecords(
            records = records,
            messagesBySatellite = navigation.beidouCivilianNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_BEIDOU,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addRecords(
            records = records,
            messagesBySatellite = navigation.qzssLegacyNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_QZSS,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { prn -> prn + QZSS_ANDROID_SVID_OFFSET }
        addRecords(
            records = records,
            messagesBySatellite = navigation.qzssCivilianNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_QZSS,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { prn -> prn + QZSS_ANDROID_SVID_OFFSET }
        addRecords(
            records = records,
            messagesBySatellite = navigation.irnssNavigationMessages,
            constellation = GnssStatus.CONSTELLATION_IRNSS,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        ) { it }
        addGlonassRecords(
            records = records,
            messagesBySatellite = navigation.glonassNavigationMessages,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            nowUtcMillis = nowUtcMillis
        )

        return records
    }

    private fun <T> addRecords(
        records: MutableMap<SatelliteKey, MutableList<BroadcastEphemerisRecord>>,
        messagesBySatellite: Map<String, List<T>>,
        constellation: Int,
        sourceName: String,
        sourceUrl: String,
        nowUtcMillis: Long,
        androidSvid: (Int) -> Int
    ) where T : GNSSOrbitalElements {
        val utc = TimeScalesFactory.getUTC()
        messagesBySatellite.forEach { (satelliteId, messages) ->
            messages.forEach { message ->
                val epochUtcMillis = message.date.toDate(utc).time
                if (abs(nowUtcMillis - epochUtcMillis) > MAX_RECORD_AGE_MS) return@forEach

                val key = SatelliteKey(
                    constellationType = constellation,
                    svid = androidSvid(message.prn)
                )
                records.getOrPut(key) { mutableListOf() } += BroadcastEphemerisRecord(
                    key = key,
                    satelliteId = satelliteId,
                    sourceName = sourceName,
                    sourceUrl = sourceUrl,
                    epochUtcMillis = epochUtcMillis,
                    message = message
                )
            }
        }
    }

    private fun addGlonassRecords(
        records: MutableMap<SatelliteKey, MutableList<BroadcastEphemerisRecord>>,
        messagesBySatellite: Map<String, List<GLONASSNavigationMessage>>,
        sourceName: String,
        sourceUrl: String,
        nowUtcMillis: Long
    ) {
        val utc = TimeScalesFactory.getUTC()
        messagesBySatellite.forEach { (satelliteId, messages) ->
            messages.forEach { message ->
                val epochUtcMillis = message.date.toDate(utc).time
                if (abs(nowUtcMillis - epochUtcMillis) > MAX_RECORD_AGE_MS) return@forEach

                val prn = message.prn
                if (prn !in 1..25) return@forEach

                val key = SatelliteKey(
                    constellationType = GnssStatus.CONSTELLATION_GLONASS,
                    svid = prn
                )
                records.getOrPut(key) { mutableListOf() } += BroadcastEphemerisRecord(
                    key = key,
                    satelliteId = satelliteId,
                    sourceName = sourceName,
                    sourceUrl = sourceUrl,
                    epochUtcMillis = epochUtcMillis,
                    message = message
                )
            }
        }
    }

    private fun buildCandidateBatches(nowUtcMillis: Long): List<List<IgsFileRequest>> {
        val batches = mutableListOf<List<IgsFileRequest>>()
        for (dayOffset in listOf(0, -1, -2)) {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
                timeInMillis = nowUtcMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val year = calendar.get(Calendar.YEAR)
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            val dateToken = String.format(Locale.US, "%04d%03d0000", year, dayOfYear)

            batches += listOf(buildRequest(year, dayOfYear, "BRDC00WRD_R_${dateToken}_01D_MN.rnx.gz"))
            batches += listOf(buildRequest(year, dayOfYear, "BRDC00WRD_S_${dateToken}_01D_MN.rnx.gz"))
            batches += listOf(buildRequest(year, dayOfYear, "BRD400DLR_S_${dateToken}_01D_MN.rnx.gz"))
            batches += listOf(buildRequest(year, dayOfYear, "BRDM00DLR_S_${dateToken}_01D_MN.rnx.gz"))
            batches += listOf(buildRequest(year, dayOfYear, "BRDC00IGS_R_${dateToken}_01D_MN.rnx.gz"))
            batches += systemFileSuffixes.map { suffix ->
                buildRequest(year, dayOfYear, "BRDC00WRD_R_${dateToken}_01D_${suffix}.rnx.gz")
            }
            batches += systemFileSuffixes.map { suffix ->
                buildRequest(year, dayOfYear, "BRDC00WRD_S_${dateToken}_01D_${suffix}.rnx.gz")
            }
            batches += systemFileSuffixes.map { suffix ->
                buildRequest(year, dayOfYear, "BRD400DLR_S_${dateToken}_01D_${suffix}.rnx.gz")
            }
            batches += systemFileSuffixes.map { suffix ->
                buildRequest(year, dayOfYear, "BRDM00DLR_S_${dateToken}_01D_${suffix}.rnx.gz")
            }
        }
        return batches
    }

    private fun configureGnssRolloverReference(nowUtcMillis: Long) {
        if (GNSSDate.getRolloverReference() != null) return

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            timeInMillis = nowUtcMillis
        }
        GNSSDate.setRolloverReference(DateComponents(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        ))
    }

    private fun buildRequest(
        year: Int,
        dayOfYear: Int,
        fileName: String
    ): IgsFileRequest {
        return IgsFileRequest(
            fileName = fileName,
            url = "$BASE_URL/$year/${String.format(Locale.US, "%03d", dayOfYear)}/$fileName"
        )
    }

    private fun logFetchFailure(request: IgsFileRequest, error: Throwable) {
        if (error is HttpStatusException && error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            Log.d(TAG, "fetch missing ${request.fileName}: HTTP ${error.statusCode}")
            return
        }

        Log.w(
            TAG,
            "fetch failed ${request.fileName}: ${error.javaClass.simpleName}: ${error.message}",
            error
        )
    }

    private class HttpStatusException(
        val statusCode: Int
    ) : IllegalStateException("HTTP $statusCode")

    private data class IgsFileRequest(
        val fileName: String,
        val url: String
    )
}
