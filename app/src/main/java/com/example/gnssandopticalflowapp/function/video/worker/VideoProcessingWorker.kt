package com.example.gnssandopticalflowapp.function.video.worker

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.gnssandopticalflowapp.function.video.contract.VideoProcessingJobContract
import com.example.gnssandopticalflowapp.function.video.jobs.VideoProcessingJobs
import com.example.gnssandopticalflowapp.function.video.local.LocalVideoProcessor
import com.example.gnssandopticalflowapp.function.video.notification.VideoProcessingNotifier
import com.example.gnssandopticalflowapp.function.video.options.VideoProcessOptionsCodec
import com.example.gnssandopticalflowapp.function.video.server.GnssBackendVideoProcessor
import com.example.gnssandopticalflowapp.function.video.server.ServerVideoProcessor
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingBus
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingProgressText
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import kotlinx.coroutines.CancellationException
import java.io.File

class VideoProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private var currentProgressPercent = VideoProcessingProgressText.DEFAULT_PERCENT
    private var currentProcessingMode: VideoProcessOptions.ProcessingMode? = null
    private var acceptsJobUpdates = true
    private var activeServerProcessor: ServerVideoProcessor? = null
    private var activeGnssBackendProcessor: GnssBackendVideoProcessor? = null

    private val localJobId: String = workerParams.inputData
        .getString(VideoProcessingJobContract.EXTRA_JOB_ID)
        ?.takeIf { it.isNotBlank() }
        ?: VideoProcessingJobContract.fallbackJobId(workerParams.id.toString())

    private val localJobCreatedAtMs: Long = workerParams.inputData.getLong(
        VideoProcessingJobContract.EXTRA_JOB_CREATED_AT_MS,
        System.currentTimeMillis()
    )

    private val notifier = VideoProcessingNotifier(
        context = appContext.applicationContext,
        jobId = localJobId,
        jobCreatedAtMs = localJobCreatedAtMs
    )

    private val localProcessor by lazy {
        LocalVideoProcessor(
            context = applicationContext,
            callbacks = object : LocalVideoProcessor.Callbacks {
                override fun isActive() = isProcessingActive()
                override fun currentPercent() = currentProgressPercent
                override fun postStatus(status: String) = postCurrentProgress(status)
                override fun postPercent(percent: Int) = postProgressPercent(percent)
            }
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        notifier.createChannels()
        return notifier.foregroundInfo(currentProgressMessage(), ongoing = true)
    }

    override suspend fun doWork(): Result {
        notifier.createChannels()

        val sourcePath = inputData.getString(VideoProcessingJobContract.EXTRA_SOURCE_PATH)
        val optionsFile = inputData.getString(VideoProcessingJobContract.EXTRA_OPTIONS_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val options = optionsFile
            ?.takeIf { it.isFile }
            ?.readText()
            ?.let(VideoProcessOptionsCodec::decode)

        if (sourcePath.isNullOrBlank() || options == null) {
            Log.e(TAG, "Missing source path or process options")
            sourcePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            optionsFile?.delete()
            VideoProcessingBus.postFailed(localJobId, "Processing failed")
            return Result.failure()
        }

        currentProcessingMode = options.processingMode
        if (VideoProcessingBus.isDismissed(localJobId)) {
            File(sourcePath).delete()
            optionsFile.delete()
            VideoProcessingJobs.clearNotifications(applicationContext, localJobId)
            return Result.failure()
        }

        currentProgressPercent = VideoProcessingProgressText.DEFAULT_PERCENT
        setForeground(notifier.foregroundInfo(currentProgressMessage(), ongoing = true))
        VideoProcessingBus.postProcessing(localJobId, options.processingMode, currentProgressMessage())

        val sourceFile = File(sourcePath)
        return try {
            val outputFile = processVideo(sourceFile, options)
            acceptsJobUpdates = false
            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(outputFile.absolutePath),
                null
            ) { _, _ -> }
            MediaStorageUtil.addVideo(applicationContext, outputFile.absolutePath)
            VideoProcessingBus.postFinished(localJobId, outputFile.absolutePath)
            notifier.showCompleted("Processing done", outputFile.absolutePath)
            Result.success(
                workDataOf(VideoProcessingJobContract.OUTPUT_VIDEO_PATH to outputFile.absolutePath)
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "Processing cancelled")
            acceptsJobUpdates = false
            activeServerProcessor?.cancelCurrentWork()
            activeGnssBackendProcessor?.cancelCurrentWork()
            VideoProcessingBus.postCancelled(localJobId)
            VideoProcessingJobs.clearNotifications(applicationContext, localJobId)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed: ${e.message}", e)
            acceptsJobUpdates = false
            VideoProcessingBus.postFailed(localJobId, "Processing failed")
            notifier.showCompleted("Processing failed", null)
            Result.failure()
        } finally {
            acceptsJobUpdates = false
            activeServerProcessor = null
            activeGnssBackendProcessor = null
            VideoProcessingJobs.untrack(localJobId)
            sourceFile.delete()
            optionsFile.delete()
        }
    }

    private suspend fun processVideo(
        sourceFile: File,
        options: VideoProcessOptions
    ): File {
        postProgressPercent(VideoProcessingProgressText.DEFAULT_PERCENT)
        val outputDir = sourceFile.parentFile ?: applicationContext.cacheDir
        val outputFile = File(outputDir, "processed_${System.currentTimeMillis()}.mp4")

        val processed = try {
            when (options.processingMode) {
                VideoProcessOptions.ProcessingMode.OFFLINE -> {
                    localProcessor.process(sourceFile, outputFile, options)
                }
                VideoProcessOptions.ProcessingMode.ONLINE -> {
                    serverProcessor().process(sourceFile, outputFile, options)
                }
                VideoProcessOptions.ProcessingMode.OUTER_SERVER -> {
                    gnssBackendProcessor().process(sourceFile, outputFile, options)
                }
            }
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        }

        if (!isProcessingActive()) {
            outputFile.delete()
            throw CancellationException("Video processing did not complete")
        }
        if (!processed) {
            outputFile.delete()
            throw IllegalStateException("Video processing did not complete")
        }

        postProgressPercent(VideoProcessingProgressText.COMPLETE_PERCENT)
        return outputFile
    }

    private fun serverProcessor(): ServerVideoProcessor {
        activeServerProcessor?.let { return it }
        return ServerVideoProcessor(
            context = applicationContext,
            callbacks = object : ServerVideoProcessor.Callbacks {
                override fun isActive() = isProcessingActive()
                override fun postStatus(status: String) = postCurrentProgress(status)
                override fun postPercent(percent: Int) = postProgressPercent(percent)
                override fun postServerJobId(serverJobId: String) {
                    VideoProcessingBus.postServerJobId(localJobId, serverJobId)
                }
            }
        ).also { activeServerProcessor = it }
    }

    private fun gnssBackendProcessor(): GnssBackendVideoProcessor {
        activeGnssBackendProcessor?.let { return it }
        return GnssBackendVideoProcessor(
            context = applicationContext,
            callbacks = object : GnssBackendVideoProcessor.Callbacks {
                override fun isActive() = isProcessingActive()
                override fun postStatus(status: String) = postCurrentProgress(status)
                override fun postPercent(percent: Int) = postProgressPercent(percent)
                override fun postServerJobId(serverJobId: String) {
                    VideoProcessingBus.postServerJobId(localJobId, serverJobId)
                }
            }
        ).also { activeGnssBackendProcessor = it }
    }

    private fun postCurrentProgress(status: String) {
        if (!canPostJobUpdates()) return
        Log.d(TAG, status)
        postProgress(VideoProcessingProgressText.normalize(status, currentProgressPercent))
    }

    private fun postProgressPercent(percent: Int) {
        currentProgressPercent = percent.coerceIn(
            VideoProcessingProgressText.DEFAULT_PERCENT,
            VideoProcessingProgressText.COMPLETE_PERCENT
        )
        postProgress(currentProgressMessage())
    }

    private fun postProgress(message: String) {
        if (!canPostJobUpdates()) return
        Log.d(TAG, message)
        VideoProcessingBus.postProcessing(localJobId, currentProcessingMode, message)
        if (VideoProcessingBus.isDismissed(localJobId)) return
        setProgressAsync(
            workDataOf(VideoProcessingJobContract.PROGRESS_PERCENT to currentProgressPercent)
        )
        notifier.update(message, ongoing = true)
    }

    private fun currentProgressMessage(): String {
        return VideoProcessingProgressText.format(currentProgressPercent)
    }

    private fun isProcessingActive(): Boolean {
        return !isStopped
    }

    private fun canPostJobUpdates(): Boolean {
        return acceptsJobUpdates && isProcessingActive() && !VideoProcessingBus.isDismissed(localJobId)
    }

    companion object {
        const val ACTION_CANCEL_JOB = "com.example.gnssandopticalflowapp.video.CANCEL_JOB"
        const val ACTION_DISMISS_JOB = "com.example.gnssandopticalflowapp.video.DISMISS_JOB"
        const val EXTRA_JOB_ID = "job_id"

        fun newJobId(): String = VideoProcessingJobs.newJobId()

        fun enqueue(
            context: Context,
            sourcePath: String,
            options: VideoProcessOptions,
            jobId: String = newJobId()
        ): String {
            return VideoProcessingJobs.enqueue(context, sourcePath, options, jobId)
        }

        fun cancel(context: Context, jobId: String) {
            VideoProcessingJobs.cancel(context, jobId)
        }

        fun dismiss(context: Context, jobId: String) {
            VideoProcessingJobs.dismiss(context, jobId)
        }

        fun clearJob(context: Context, jobId: String) {
            VideoProcessingJobs.clearJob(context, jobId)
        }

        fun clearNotifications(context: Context, jobId: String) {
            VideoProcessingJobs.clearNotifications(context, jobId)
        }

        private const val TAG = "VIDEO-WORKER"
    }
}
