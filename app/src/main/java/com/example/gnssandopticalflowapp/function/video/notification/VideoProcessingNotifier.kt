package com.example.gnssandopticalflowapp.function.video.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.example.gnssandopticalflowapp.MainActivity
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.function.video.contract.VideoProcessingJobContract
import com.example.gnssandopticalflowapp.function.video.contract.foregroundInfoCompat
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingBus

internal class VideoProcessingNotifier(
    private val context: Context,
    private val jobId: String,
    private val jobCreatedAtMs: Long
) {
    private val appContext = context.applicationContext

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video processing",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETED_CHANNEL_ID,
                "Video processing completed",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun foregroundInfo(message: String, ongoing: Boolean): ForegroundInfo {
        return foregroundInfoCompat(
            jobId = jobId,
            notification = buildActiveNotification(message, ongoing)
        )
    }

    fun update(message: String, ongoing: Boolean) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(
            VideoProcessingJobContract.notificationId(jobId),
            buildActiveNotification(message, ongoing)
        )
    }

    fun showCompleted(message: String, videoPath: String?) {
        if (VideoProcessingBus.isDismissed(jobId)) return

        val manager = appContext.getSystemService(NotificationManager::class.java)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (videoPath != null) {
                putExtra("processed_video_path", videoPath)
            }
        }
        val openIntent = PendingIntent.getActivity(
            appContext,
            VideoProcessingJobContract.notificationId(jobId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = jobActionPendingIntent(VideoProcessingJobContract.ACTION_DISMISS_JOB, jobId)
        val notification = NotificationCompat.Builder(appContext, COMPLETED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_process)
            .setContentTitle("Video processing #$jobId")
            .setContentText(message)
            .setWhen(jobCreatedAtMs)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(openIntent)
            .setDeleteIntent(dismissIntent)
            .addAction(R.drawable.ic_close, "Dismiss", dismissIntent)
            .build()

        manager.cancel(VideoProcessingJobContract.completedNotificationId(jobId))
        manager.notify(VideoProcessingJobContract.notificationId(jobId), notification)
    }

    private fun buildActiveNotification(message: String, ongoing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            appContext,
            VideoProcessingJobContract.notificationId(jobId),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = jobActionPendingIntent(VideoProcessingJobContract.ACTION_CANCEL_JOB, jobId)

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_process)
            .setContentTitle("Video processing #$jobId")
            .setContentText(message)
            .setWhen(jobCreatedAtMs)
            .setShowWhen(false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setDeleteIntent(cancelIntent)
            .addAction(R.drawable.ic_close, "Cancel", cancelIntent)
            .build()
    }

    private fun jobActionPendingIntent(action: String, jobId: String): PendingIntent {
        val intent = Intent(appContext, VideoProcessingNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(VideoProcessingJobContract.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            VideoProcessingJobContract.actionRequestCode(jobId, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "video_processing"
        private const val COMPLETED_CHANNEL_ID = "video_processing_completed"

        fun clear(context: Context, jobId: String) {
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
            manager.cancel(VideoProcessingJobContract.notificationId(jobId))
            manager.cancel(VideoProcessingJobContract.completedNotificationId(jobId))
            manager.cancel(VideoProcessingJobContract.legacyNotificationId(jobId))
            manager.cancel(VideoProcessingJobContract.legacyCompletedNotificationId(jobId))
        }
    }
}
