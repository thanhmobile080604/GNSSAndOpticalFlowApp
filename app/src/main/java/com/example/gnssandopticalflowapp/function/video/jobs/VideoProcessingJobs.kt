package com.example.gnssandopticalflowapp.function.video.jobs

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.gnssandopticalflowapp.function.video.contract.VideoProcessingJobContract
import com.example.gnssandopticalflowapp.function.video.notification.VideoProcessingNotifier
import com.example.gnssandopticalflowapp.function.video.options.VideoProcessOptionsCodec
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingBus
import com.example.gnssandopticalflowapp.function.video.worker.VideoProcessingWorker
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object VideoProcessingJobs {
    private const val WORK_TAG = "video_processing_work"
    private const val JOB_TAG_PREFIX = "video_processing_job_"
    private const val UNIQUE_WORK_PREFIX = "video_processing_unique_job_"

    private val activeWorkIds = ConcurrentHashMap<String, UUID>()

    fun newJobId(): String = VideoProcessingBus.createJobId()

    fun enqueue(
        context: Context,
        sourcePath: String,
        options: VideoProcessOptions,
        jobId: String = newJobId()
    ): String {
        val sourceFile = File(sourcePath)
        val optionsFile = File(
            sourceFile.parentFile ?: context.applicationContext.cacheDir,
            "${sourceFile.nameWithoutExtension}_${jobId}_options.json"
        )
        optionsFile.writeText(VideoProcessOptionsCodec.encode(options))

        val request = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
            .setInputData(
                workDataOf(
                    VideoProcessingJobContract.EXTRA_JOB_ID to jobId,
                    VideoProcessingJobContract.EXTRA_JOB_CREATED_AT_MS to System.currentTimeMillis(),
                    VideoProcessingJobContract.EXTRA_SOURCE_PATH to sourcePath,
                    VideoProcessingJobContract.EXTRA_OPTIONS_PATH to optionsFile.absolutePath
                )
            )
            .addTag(WORK_TAG)
            .addTag(jobTag(jobId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        activeWorkIds[jobId] = request.id
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName(jobId),
            ExistingWorkPolicy.KEEP,
            request
        )
        return jobId
    }

    fun cancel(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        val workManager = WorkManager.getInstance(context.applicationContext)
        val workId = activeWorkIds.remove(jobId)
        if (workId != null) {
            workManager.cancelWorkById(workId)
        } else {
            workManager.cancelUniqueWork(uniqueWorkName(jobId))
        }
        clearJob(context, jobId)
    }

    fun dismiss(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        clearJob(context, jobId)
    }

    fun clearJob(context: Context, jobId: String) {
        activeWorkIds.remove(jobId)
        VideoProcessingNotifier.clear(context, jobId)
        VideoProcessingBus.clearJob(jobId)
        VideoProcessingBus.processedVideoPathToOpen.postValue(null)
    }

    fun clearNotifications(context: Context, jobId: String) {
        VideoProcessingNotifier.clear(context, jobId)
    }

    fun untrack(jobId: String) {
        activeWorkIds.remove(jobId)
    }

    private fun jobTag(jobId: String): String {
        return JOB_TAG_PREFIX + jobId
    }

    private fun uniqueWorkName(jobId: String): String {
        return UNIQUE_WORK_PREFIX + jobId
    }
}
