package com.example.gnssandopticalflowapp.util

import android.content.Context
import com.example.gnssandopticalflowapp.data.AppDatabase
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.data.AnalyticsSampleEntity
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.data.AnalyticsSessionEntity
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lưu trữ phiên phân tích KLT/Farneback bằng Room.
 * Mỗi phiên là một bản ghi trong bảng analytics_sessions, các mẫu đo nằm trong
 * bảng analytics_samples liên kết qua khóa ngoại sessionId.
 */
object AnalyticsStorageUtil {

    fun createSessionId(startedAtMs: Long = System.currentTimeMillis()): String {
        return "analysis_${fileDateFormat().format(Date(startedAtMs))}"
    }

    @Synchronized
    fun saveSession(context: Context, session: AnalyticsSession): File {
        val dao = AppDatabase.get(context).analyticsDao()
        val sessionEntity = AnalyticsSessionEntity(
            id = session.id,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            durationMs = session.durationMs,
            kltSensitivity = session.kltSensitivity,
            farnebackSensitivity = session.farnebackSensitivity,
            movingMode = session.movingMode
        )
        val sampleEntities = session.samples.mapIndexed { index, sample ->
            sample.toEntity(session.id, index)
        }
        dao.saveSession(sessionEntity, sampleEntities)
        return context.getDatabasePath("gnss_optical_flow.db")
    }

    fun getSession(context: Context, id: String): AnalyticsSession? {
        val dao = AppDatabase.get(context).analyticsDao()
        val sessionEntity = dao.getSession(id) ?: return null
        val samples = dao.getSamples(id).map { it.toModel() }
        return AnalyticsSession(
            id = sessionEntity.id,
            startedAtMs = sessionEntity.startedAtMs,
            endedAtMs = sessionEntity.endedAtMs,
            durationMs = sessionEntity.durationMs,
            kltSensitivity = sessionEntity.kltSensitivity,
            farnebackSensitivity = sessionEntity.farnebackSensitivity,
            movingMode = sessionEntity.movingMode,
            samples = samples
        )
    }

    fun getSessionSummaries(context: Context): List<AnalyticsSessionSummary> {
        return AppDatabase.get(context).analyticsDao().getSummaries()
    }

    @Synchronized
    fun deleteSessions(context: Context, sessions: List<AnalyticsSessionSummary>): Int {
        val dao = AppDatabase.get(context).analyticsDao()
        var deletedCount = 0
        for (session in sessions) {
            if (dao.getSession(session.id) != null) {
                dao.deleteSession(session.id)
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun AnalyticsSample.toEntity(sessionId: String, orderIndex: Int): AnalyticsSampleEntity {
        return AnalyticsSampleEntity(
            sessionId = sessionId,
            orderIndex = orderIndex,
            elapsedMs = elapsedMs,
            frameIndex = frameIndex,
            kltFps = kltFps,
            farnebackFps = farnebackFps,
            kltProcessMs = kltProcessMs,
            farnebackProcessMs = farnebackProcessMs,
            kltFeatureCount = kltFeatureCount,
            farnebackSampleCount = farnebackSampleCount,
            kltActiveVectorCount = kltActiveVectorCount,
            farnebackActiveVectorCount = farnebackActiveVectorCount,
            kltAvgDx = kltAvgDx,
            kltAvgDy = kltAvgDy,
            farnebackAvgDx = farnebackAvgDx,
            farnebackAvgDy = farnebackAvgDy,
            kltAvgMagnitude = kltAvgMagnitude,
            farnebackAvgMagnitude = farnebackAvgMagnitude,
            kltConfidence = kltConfidence,
            farnebackConfidence = farnebackConfidence,
            kltThreshold = kltThreshold,
            farnebackThreshold = farnebackThreshold
        )
    }

    private fun AnalyticsSampleEntity.toModel(): AnalyticsSample {
        return AnalyticsSample(
            elapsedMs = elapsedMs,
            frameIndex = frameIndex,
            kltFps = kltFps,
            farnebackFps = farnebackFps,
            kltProcessMs = kltProcessMs,
            farnebackProcessMs = farnebackProcessMs,
            kltFeatureCount = kltFeatureCount,
            farnebackSampleCount = farnebackSampleCount,
            kltActiveVectorCount = kltActiveVectorCount,
            farnebackActiveVectorCount = farnebackActiveVectorCount,
            kltAvgDx = kltAvgDx,
            kltAvgDy = kltAvgDy,
            farnebackAvgDx = farnebackAvgDx,
            farnebackAvgDy = farnebackAvgDy,
            kltAvgMagnitude = kltAvgMagnitude,
            farnebackAvgMagnitude = farnebackAvgMagnitude,
            kltConfidence = kltConfidence,
            farnebackConfidence = farnebackConfidence,
            kltThreshold = kltThreshold,
            farnebackThreshold = farnebackThreshold
        )
    }

    private fun fileDateFormat() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
