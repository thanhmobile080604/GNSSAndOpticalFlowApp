package com.example.gnssandopticalflowapp.util

import android.content.Context
import com.example.gnssandopticalflowapp.model.RouteSession
import com.example.gnssandopticalflowapp.model.RouteSessionSummary
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RouteStorageUtil {
    private const val ROUTE_DIR = "route_sessions"
    private val gson = Gson()

    fun createSessionId(startedAtMs: Long = System.currentTimeMillis()): String {
        return "route_${fileDateFormat().format(Date(startedAtMs))}"
    }

    @Synchronized
    fun saveSession(context: Context, session: RouteSession): File {
        val outputFile = File(ensureRouteDir(context), "${session.id}.json")
        outputFile.writeText(gson.toJson(session))
        return outputFile
    }

    fun getSession(context: Context, id: String): RouteSession? {
        val file = File(ensureRouteDir(context), "$id.json")
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), RouteSession::class.java)
        }.getOrNull()
    }

    fun getSessionSummaries(context: Context): List<RouteSessionSummary> {
        val dir = ensureRouteDir(context)
        return dir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    gson.fromJson(file.readText(), RouteSession::class.java).toSummary()
                }.getOrNull()
            }
            ?.sortedByDescending { it.startedAtMs }
            .orEmpty()
    }

    fun deleteSessions(context: Context, sessions: List<RouteSessionSummary>): Int {
        val dir = ensureRouteDir(context)
        var deletedCount = 0
        for (session in sessions) {
            val file = File(dir, "${session.id}.json")
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun ensureRouteDir(context: Context): File {
        return File(context.filesDir, ROUTE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun fileDateFormat() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
