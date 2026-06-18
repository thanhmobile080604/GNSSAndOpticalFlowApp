package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug logger for live-routing accuracy analysis. Writes a single text file with two interleaved,
 * timestamped tracks so the dead-reckoning drift can be measured against ground truth:
 *
 *  - `GNSS` — the raw, continuous true GPS fixes. Logged for EVERY real fix, even while the
 *             GNSS-dropout test is suppressing them for navigation, so the true path is never broken.
 *  - `DR`   — the dead-reckoned (red) path produced while GNSS assist is active (the outage path).
 *
 * Align the two by `timeMs` (all from `System.currentTimeMillis()`) to see, at any instant during an
 * outage, the true position vs the dead-reckoned one. File: `<externalFilesDir>/live_routing_*.txt`.
 *
 * All file I/O runs on a dedicated single thread so the navigation tick / UI thread never blocks.
 */
class RouteDebugLogger {

    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    @Volatile private var filePath: String? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        submit {
            try {
                val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "live_routing_$stamp.txt")
                val w = BufferedWriter(FileWriter(file))
                w.write("timeMs,type,lat,lon,headingDeg,speedMps,routeLocked\n")
                w.flush()
                writer = w
                filePath = file.absolutePath
                Log.i(TAG, "Route debug log started: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}", e)
            }
        }
    }

    /** A raw, true GPS fix (logged regardless of the dropout test). */
    fun logGnss(timeMs: Long, lat: Double, lon: Double, headingDeg: Double, speedMps: Double) {
        write("$timeMs,GNSS,$lat,$lon,${fmt(headingDeg)},${fmt(speedMps)},\n")
    }

    /** A dead-reckoned (red) path point produced during a GNSS outage. */
    fun logDeadReckon(
        timeMs: Long,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        speedMps: Double,
        routeLocked: Boolean
    ) {
        write("$timeMs,DR,$lat,$lon,${fmt(headingDeg)},${fmt(speedMps)},$routeLocked\n")
    }

    fun stop() {
        submit {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
        runCatching { io.shutdown() }
    }

    /** Absolute path of the current log file, once [start] has run (null until then). */
    fun currentFilePath(): String? = filePath

    private fun write(line: String) {
        submit {
            try {
                writer?.let {
                    it.write(line)
                    it.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "write failed: ${e.message}")
            }
        }
    }

    private fun fmt(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "NaN"

    /** Submits work to the IO thread, swallowing rejections that occur after [stop]. */
    private fun submit(task: () -> Unit) {
        runCatching { io.execute(task) }
    }

    private companion object {
        const val TAG = "RouteDebugLogger"
    }
}
