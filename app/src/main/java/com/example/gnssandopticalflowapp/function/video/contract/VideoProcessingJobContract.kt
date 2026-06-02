package com.example.gnssandopticalflowapp.function.video.contract

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import java.util.Locale

internal object VideoProcessingJobContract {
    const val ACTION_CANCEL_JOB = "com.example.gnssandopticalflowapp.video.CANCEL_JOB"
    const val ACTION_DISMISS_JOB = "com.example.gnssandopticalflowapp.video.DISMISS_JOB"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_JOB_CREATED_AT_MS = "job_created_at_ms"
    const val EXTRA_SOURCE_PATH = "source_path"
    const val EXTRA_OPTIONS_PATH = "options_path"
    const val OUTPUT_VIDEO_PATH = "output_video_path"
    const val PROGRESS_PERCENT = "progress_percent"

    private const val NOTIFICATION_ID_BASE = 3001
    private const val COMPLETED_NOTIFICATION_ID_BASE = 13001
    private const val NOTIFICATION_ID_RANGE = 9000

    fun fallbackJobId(workerId: String): String {
        return workerId.replace("-", "").take(8).uppercase(Locale.US)
    }

    fun notificationId(jobId: String): Int {
        val rawId = jobId.toLongOrNull(radix = 16)?.toInt() ?: jobId.hashCode()
        val positiveId = rawId and Int.MAX_VALUE
        return positiveId.takeIf { it != 0 } ?: NOTIFICATION_ID_BASE
    }

    fun completedNotificationId(jobId: String): Int {
        return notificationId(jobId) xor 0x40000000
    }

    fun legacyNotificationId(jobId: String): Int {
        return NOTIFICATION_ID_BASE + ((jobId.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE)
    }

    fun legacyCompletedNotificationId(jobId: String): Int {
        return COMPLETED_NOTIFICATION_ID_BASE + ((jobId.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE)
    }

    fun actionRequestCode(jobId: String, action: String): Int {
        return (31 * notificationId(jobId)) + action.hashCode()
    }
}

internal fun foregroundInfoCompat(
    jobId: String,
    notification: Notification
): ForegroundInfo {
    val notificationId = VideoProcessingJobContract.notificationId(jobId)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    } else {
        ForegroundInfo(notificationId, notification)
    }
}
