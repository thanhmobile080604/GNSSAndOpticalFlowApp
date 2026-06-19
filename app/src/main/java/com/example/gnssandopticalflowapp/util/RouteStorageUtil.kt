package com.example.gnssandopticalflowapp.util

import android.content.Context
import com.example.gnssandopticalflowapp.data.AppDatabase
import com.example.gnssandopticalflowapp.model.RouteLatLng
import com.example.gnssandopticalflowapp.model.RouteSession
import com.example.gnssandopticalflowapp.data.RouteSessionEntity
import com.example.gnssandopticalflowapp.model.RouteSessionSummary
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lưu trữ phiên chỉ đường trực tiếp (tuyến đường đã đi) bằng Room.
 * Hình học tuyến (danh sách điểm và các đoạn) được tuần tự hóa JSON và lưu trong
 * các cột của bảng route_sessions.
 */
object RouteStorageUtil {
    private val gson = Gson()
    private val pointListType = object : TypeToken<List<RouteLatLng>>() {}.type
    private val segmentListType = object : TypeToken<List<List<RouteLatLng>>>() {}.type

    fun createSessionId(startedAtMs: Long = System.currentTimeMillis()): String {
        return "route_${fileDateFormat().format(Date(startedAtMs))}"
    }

    @Synchronized
    fun saveSession(context: Context, session: RouteSession): File {
        val entity = RouteSessionEntity(
            id = session.id,
            startedAtMs = session.startedAtMs,
            durationMs = session.durationMs,
            destinationName = session.destinationName,
            startLat = session.start.lat,
            startLon = session.start.lon,
            destLat = session.destination.lat,
            destLon = session.destination.lon,
            routePointsJson = gson.toJson(session.routePoints),
            gnssSegmentsJson = gson.toJson(session.gnssTravelSegments),
            opticalSegmentsJson = gson.toJson(session.opticalAssistSegments),
            weakPointsJson = gson.toJson(session.weakPoints),
            strongPointsJson = gson.toJson(session.strongPoints),
            outagePointCount = session.opticalAssistSegments.sumOf { it.size },
            gnssPointCount = session.gnssTravelSegments.sumOf { it.size }
        )
        AppDatabase.get(context).routeDao().insertSession(entity)
        return context.getDatabasePath("gnss_optical_flow.db")
    }

    fun getSession(context: Context, id: String): RouteSession? {
        val entity = AppDatabase.get(context).routeDao().getSession(id) ?: return null
        return runCatching {
            RouteSession(
                id = entity.id,
                startedAtMs = entity.startedAtMs,
                durationMs = entity.durationMs,
                destinationName = entity.destinationName,
                start = RouteLatLng(entity.startLat, entity.startLon),
                destination = RouteLatLng(entity.destLat, entity.destLon),
                routePoints = parsePoints(entity.routePointsJson),
                gnssTravelSegments = parseSegments(entity.gnssSegmentsJson),
                opticalAssistSegments = parseSegments(entity.opticalSegmentsJson),
                weakPoints = parsePoints(entity.weakPointsJson),
                strongPoints = parsePoints(entity.strongPointsJson)
            )
        }.getOrNull()
    }

    fun getSessionSummaries(context: Context): List<RouteSessionSummary> {
        return AppDatabase.get(context).routeDao().getSummaries()
    }

    @Synchronized
    fun deleteSessions(context: Context, sessions: List<RouteSessionSummary>): Int {
        val dao = AppDatabase.get(context).routeDao()
        var deletedCount = 0
        for (session in sessions) {
            if (dao.getSession(session.id) != null) {
                dao.deleteSession(session.id)
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun parsePoints(json: String): List<RouteLatLng> {
        return runCatching { gson.fromJson<List<RouteLatLng>>(json, pointListType) }.getOrNull().orEmpty()
    }

    private fun parseSegments(json: String): List<List<RouteLatLng>> {
        return runCatching { gson.fromJson<List<List<RouteLatLng>>>(json, segmentListType) }.getOrNull().orEmpty()
    }

    private fun fileDateFormat() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
