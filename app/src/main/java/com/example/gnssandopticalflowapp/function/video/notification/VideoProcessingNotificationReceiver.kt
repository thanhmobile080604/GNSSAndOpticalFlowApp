package com.example.gnssandopticalflowapp.function.video.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.gnssandopticalflowapp.function.video.contract.VideoProcessingJobContract
import com.example.gnssandopticalflowapp.function.video.jobs.VideoProcessingJobs

class VideoProcessingNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(VideoProcessingJobContract.EXTRA_JOB_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return

        when (intent.action) {
            VideoProcessingJobContract.ACTION_CANCEL_JOB -> VideoProcessingJobs.cancel(context, jobId)
            VideoProcessingJobContract.ACTION_DISMISS_JOB -> VideoProcessingJobs.dismiss(context, jobId)
        }
    }
}
