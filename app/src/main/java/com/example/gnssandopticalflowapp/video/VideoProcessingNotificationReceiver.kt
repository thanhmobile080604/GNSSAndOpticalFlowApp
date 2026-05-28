package com.example.gnssandopticalflowapp.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class VideoProcessingNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(VideoProcessingWorker.EXTRA_JOB_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return

        when (intent.action) {
            VideoProcessingWorker.ACTION_CANCEL_JOB -> VideoProcessingWorker.cancel(context, jobId)
            VideoProcessingWorker.ACTION_DISMISS_JOB -> VideoProcessingWorker.dismiss(context, jobId)
        }
    }
}
