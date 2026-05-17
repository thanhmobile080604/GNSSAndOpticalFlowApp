package com.example.gnssandopticalflowapp.util

import android.content.Context
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AnalyticsStorageUtil {
    private const val ANALYTICS_DIR = "analytics"

    fun createSessionId(startedAtMs: Long = System.currentTimeMillis()): String {
        return "analysis_${fileDateFormat().format(Date(startedAtMs))}"
    }

    @Synchronized
    fun saveSession(context: Context, session: AnalyticsSession): File {
        val outputFile = File(ensureAnalyticsDir(context), "${session.id}.json")
        outputFile.writeText(sessionToJson(session).toString())
        return outputFile
    }

    fun getSession(context: Context, id: String): AnalyticsSession? {
        val file = File(ensureAnalyticsDir(context), "$id.json")
        if (!file.exists()) return null

        return runCatching {
            jsonToSession(JSONObject(file.readText()))
        }.getOrNull()
    }

    fun getSessionSummaries(context: Context): List<AnalyticsSessionSummary> {
        val dir = ensureAnalyticsDir(context)
        return dir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    val session = jsonToSession(JSONObject(file.readText()))
                    session.toSummary()
                }.getOrNull()
            }
            ?.sortedByDescending { it.startedAtMs }
            .orEmpty()
    }

    fun deleteSessions(context: Context, sessions: List<AnalyticsSessionSummary>): Int {
        val dir = ensureAnalyticsDir(context)
        var deletedCount = 0
        for (session in sessions) {
            val file = File(dir, "${session.id}.json")
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun AnalyticsSession.toSummary(): AnalyticsSessionSummary {
        return AnalyticsSessionSummary(
            id = id,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            sampleCount = samples.size,
            avgKltFps = samples.averageOf { it.kltFps },
            avgFarnebackFps = samples.averageOf { it.farnebackFps },
            avgKltConfidence = samples.averageOf { it.kltConfidence },
            avgFarnebackConfidence = samples.averageOf { it.farnebackConfidence }
        )
    }

    private fun sessionToJson(session: AnalyticsSession): JSONObject {
        return JSONObject().apply {
            put("id", session.id)
            put("startedAtMs", session.startedAtMs)
            put("endedAtMs", session.endedAtMs)
            put("durationMs", session.durationMs)
            put("kltSensitivity", session.kltSensitivity)
            put("farnebackSensitivity", session.farnebackSensitivity)
            put("movingMode", session.movingMode)
            put("samples", JSONArray().apply {
                session.samples.forEach { sample -> put(sampleToJson(sample)) }
            })
        }
    }

    private fun jsonToSession(json: JSONObject): AnalyticsSession {
        val samplesArray = json.optJSONArray("samples") ?: JSONArray()
        val samples = mutableListOf<AnalyticsSample>()
        for (i in 0 until samplesArray.length()) {
            samples.add(jsonToSample(samplesArray.getJSONObject(i)))
        }

        return AnalyticsSession(
            id = json.optString("id"),
            startedAtMs = json.optLong("startedAtMs"),
            endedAtMs = json.optLong("endedAtMs"),
            durationMs = json.optLong("durationMs"),
            kltSensitivity = json.optInt("kltSensitivity", 50),
            farnebackSensitivity = json.optInt("farnebackSensitivity", 50),
            movingMode = json.optBoolean("movingMode", false),
            samples = samples
        )
    }

    private fun sampleToJson(sample: AnalyticsSample): JSONObject {
        return JSONObject().apply {
            put("elapsedMs", sample.elapsedMs)
            put("frameIndex", sample.frameIndex)
            put("kltFps", sample.kltFps)
            put("farnebackFps", sample.farnebackFps)
            put("kltProcessMs", sample.kltProcessMs)
            put("farnebackProcessMs", sample.farnebackProcessMs)
            put("kltFeatureCount", sample.kltFeatureCount)
            put("farnebackSampleCount", sample.farnebackSampleCount)
            put("kltActiveVectorCount", sample.kltActiveVectorCount)
            put("farnebackActiveVectorCount", sample.farnebackActiveVectorCount)
            put("kltAvgDx", sample.kltAvgDx)
            put("kltAvgDy", sample.kltAvgDy)
            put("farnebackAvgDx", sample.farnebackAvgDx)
            put("farnebackAvgDy", sample.farnebackAvgDy)
            put("kltAvgMagnitude", sample.kltAvgMagnitude)
            put("farnebackAvgMagnitude", sample.farnebackAvgMagnitude)
            put("kltConfidence", sample.kltConfidence)
            put("farnebackConfidence", sample.farnebackConfidence)
            put("kltThreshold", sample.kltThreshold)
            put("farnebackThreshold", sample.farnebackThreshold)
        }
    }

    private fun jsonToSample(json: JSONObject): AnalyticsSample {
        return AnalyticsSample(
            elapsedMs = json.optLong("elapsedMs"),
            frameIndex = json.optLong("frameIndex"),
            kltFps = json.optDouble("kltFps"),
            farnebackFps = json.optDouble("farnebackFps"),
            kltProcessMs = json.optDouble("kltProcessMs"),
            farnebackProcessMs = json.optDouble("farnebackProcessMs"),
            kltFeatureCount = json.optInt("kltFeatureCount"),
            farnebackSampleCount = json.optInt("farnebackSampleCount"),
            kltActiveVectorCount = json.optInt("kltActiveVectorCount"),
            farnebackActiveVectorCount = json.optInt("farnebackActiveVectorCount"),
            kltAvgDx = json.optDouble("kltAvgDx"),
            kltAvgDy = json.optDouble("kltAvgDy"),
            farnebackAvgDx = json.optDouble("farnebackAvgDx"),
            farnebackAvgDy = json.optDouble("farnebackAvgDy"),
            kltAvgMagnitude = json.optDouble("kltAvgMagnitude"),
            farnebackAvgMagnitude = json.optDouble("farnebackAvgMagnitude"),
            kltConfidence = json.optDouble("kltConfidence"),
            farnebackConfidence = json.optDouble("farnebackConfidence"),
            kltThreshold = json.optDouble("kltThreshold"),
            farnebackThreshold = json.optDouble("farnebackThreshold")
        )
    }

    private fun ensureAnalyticsDir(context: Context): File {
        return File(context.filesDir, ANALYTICS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun List<AnalyticsSample>.averageOf(selector: (AnalyticsSample) -> Double): Double {
        if (isEmpty()) return 0.0
        return map(selector).filter { it.isFinite() }.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun fileDateFormat() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
