package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AnalyticsStorageUtil {
    private const val ANALYTICS_DIR = "analytics"
    private const val EXPORT_DIR = "analytics_exports"

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

    fun exportPdf(context: Context, session: AnalyticsSession, chartBitmap: Bitmap?): File {
        val file = File(ensureExportDir(context), "${session.id}.pdf")
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val summary = session.toSummary()

        canvas.drawColor(Color.rgb(14, 9, 28))
        paint.color = Color.WHITE
        paint.textSize = 22f
        paint.isFakeBoldText = true
        canvas.drawText("KLT vs Farneback Analysis", 32f, 44f, paint)

        paint.isFakeBoldText = false
        paint.textSize = 11f
        paint.color = Color.rgb(215, 206, 245)
        canvas.drawText("Session: ${session.id}", 32f, 68f, paint)
        canvas.drawText("Started: ${displayDateFormat().format(Date(session.startedAtMs))}", 32f, 86f, paint)
        canvas.drawText("Duration: ${formatDuration(session.durationMs)}", 32f, 104f, paint)

        val cardsTop = 128f
        drawPdfStatCard(canvas, paint, 32f, cardsTop, "KLT FPS", summary.avgKltFps.formatOne())
        drawPdfStatCard(canvas, paint, 166f, cardsTop, "Farneback FPS", summary.avgFarnebackFps.formatOne())
        drawPdfStatCard(canvas, paint, 332f, cardsTop, "KLT Confidence", "${summary.avgKltConfidence.formatOne()}%")
        drawPdfStatCard(canvas, paint, 466f, cardsTop, "Farneback", "${summary.avgFarnebackConfidence.formatOne()}%")

        chartBitmap?.let { bitmap ->
            val maxWidth = 531f
            val maxHeight = 470f
            val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = 32f + ((maxWidth - width) / 2f)
            val top = 230f
            val dst = RectF(left, top, left + width, top + height)
            canvas.drawBitmap(bitmap, null, dst, null)
        }

        paint.color = Color.rgb(230, 224, 250)
        paint.textSize = 10f
        val footerY = 802f
        canvas.drawText("Samples: ${session.samples.size}", 32f, footerY, paint)
        canvas.drawText("KLT threshold: ${session.samples.lastOrNull()?.kltThreshold?.formatTwo() ?: "-"}", 150f, footerY, paint)
        canvas.drawText("Farneback threshold: ${session.samples.lastOrNull()?.farnebackThreshold?.formatTwo() ?: "-"}", 292f, footerY, paint)

        document.finishPage(page)
        FileOutputStream(file).use { output -> document.writeTo(output) }
        document.close()
        return file
    }

    private fun drawPdfStatCard(
        canvas: android.graphics.Canvas,
        paint: Paint,
        left: Float,
        top: Float,
        label: String,
        value: String
    ) {
        val rect = RectF(left, top, left + 96f, top + 72f)
        paint.color = Color.rgb(42, 30, 72)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, 14f, 14f, paint)
        paint.color = Color.rgb(120, 95, 190)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rect, 14f, 14f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(190, 178, 224)
        paint.textSize = 8.5f
        paint.isFakeBoldText = false
        canvas.drawText(label, left + 10f, top + 23f, paint)
        paint.color = Color.WHITE
        paint.textSize = 17f
        paint.isFakeBoldText = true
        canvas.drawText(value, left + 10f, top + 50f, paint)
        paint.isFakeBoldText = false
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

    private fun ensureExportDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
        return File(root, EXPORT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun List<AnalyticsSample>.averageOf(selector: (AnalyticsSample) -> Double): Double {
        if (isEmpty()) return 0.0
        return map(selector).filter { it.isFinite() }.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun Double.formatOne(): String = String.format(Locale.US, "%.1f", this)
    private fun Double.formatTwo(): String = String.format(Locale.US, "%.2f", this)

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun fileDateFormat() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private fun displayDateFormat() = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
}
