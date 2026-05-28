package com.example.gnssandopticalflowapp.video

import androidx.lifecycle.MutableLiveData
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

data class VideoProcessingJobState(
    val jobId: String,
    val mode: VideoProcessOptions.ProcessingMode?,
    val message: String,
    val percent: Int,
    val status: Status,
    val outputPath: String? = null,
    val serverJobId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    enum class Status {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    val isActive: Boolean
        get() = status == Status.QUEUED || status == Status.PROCESSING

    val isReady: Boolean
        get() = status == Status.COMPLETED && !outputPath.isNullOrBlank()

    val isTerminal: Boolean
        get() = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
}

object VideoProcessingBus {
    val processingJobs = MutableLiveData<List<VideoProcessingJobState>>(emptyList())
    val processingMessage = MutableLiveData<String?>()
    val processedVideoPathToOpen = MutableLiveData<String?>()
    val videoLibraryUpdated = MutableLiveData<Long>()

    @Volatile
    var isProcessing: Boolean = false
        private set

    @Volatile
    private var currentProcessingPercent = VideoProcessingProgressText.DEFAULT_PERCENT

    private val lock = Any()
    private val jobsById = LinkedHashMap<String, VideoProcessingJobState>()
    private val dismissedJobIds = mutableSetOf<String>()

    fun createJobId(): String {
        return UUID.randomUUID().toString()
            .replace("-", "")
            .take(8)
            .uppercase(Locale.US)
    }

    fun activeJobCount(mode: VideoProcessOptions.ProcessingMode? = null): Int {
        return synchronized(lock) {
            jobsById.values.count { job ->
                job.isActive && (mode == null || job.mode == mode)
            }
        }
    }

    fun jobsSnapshot(): List<VideoProcessingJobState> {
        return synchronized(lock) { jobsById.values.sortedBy { it.createdAtMs } }
    }

    fun isDismissed(jobId: String): Boolean {
        return synchronized(lock) { jobId in dismissedJobIds }
    }

    fun postQueued(
        jobId: String,
        mode: VideoProcessOptions.ProcessingMode?,
        message: String = "Preparing video..."
    ) {
        synchronized(lock) {
            dismissedJobIds.remove(jobId)
        }
        val normalizedMessage = VideoProcessingProgressText.normalize(
            message,
            VideoProcessingProgressText.DEFAULT_PERCENT
        )
        upsertJob(
            jobId = jobId,
            mode = mode,
            message = normalizedMessage,
            status = VideoProcessingJobState.Status.QUEUED
        )
    }

    fun postProcessing(
        jobId: String,
        mode: VideoProcessOptions.ProcessingMode?,
        message: String
    ) {
        val fallbackPercent = synchronized(lock) {
            jobsById[jobId]?.percent
        } ?: if (isProcessing) {
            currentProcessingPercent
        } else {
            VideoProcessingProgressText.DEFAULT_PERCENT
        }
        val normalizedMessage = VideoProcessingProgressText.normalize(message, fallbackPercent)
        upsertJob(
            jobId = jobId,
            mode = mode,
            message = normalizedMessage,
            status = VideoProcessingJobState.Status.PROCESSING
        )
    }

    fun postProcessing(message: String) {
        val fallbackPercent = if (isProcessing) {
            currentProcessingPercent
        } else {
            VideoProcessingProgressText.DEFAULT_PERCENT
        }
        val normalizedMessage = VideoProcessingProgressText.normalize(message, fallbackPercent)
        currentProcessingPercent = VideoProcessingProgressText.extractPercent(normalizedMessage)
            ?: fallbackPercent
        isProcessing = true
        processingMessage.postValue(normalizedMessage)
    }

    fun postServerJobId(jobId: String, serverJobId: String) {
        updateJob(jobId) { job ->
            job.copy(serverJobId = serverJobId, updatedAtMs = System.currentTimeMillis())
        }
    }

    fun postFinished(jobId: String, path: String) {
        if (isDismissed(jobId)) return
        upsertJob(
            jobId = jobId,
            mode = synchronized(lock) { jobsById[jobId]?.mode },
            message = "Done!",
            status = VideoProcessingJobState.Status.COMPLETED,
            outputPath = path,
            forcedPercent = VideoProcessingProgressText.COMPLETE_PERCENT
        )
        videoLibraryUpdated.postValue(System.currentTimeMillis())
        processedVideoPathToOpen.postValue(path)
    }

    fun postFinished(path: String) {
        isProcessing = false
        currentProcessingPercent = VideoProcessingProgressText.COMPLETE_PERCENT
        processingMessage.postValue(null)
        videoLibraryUpdated.postValue(System.currentTimeMillis())
        processedVideoPathToOpen.postValue(path)
    }

    fun postFailed(jobId: String, message: String = "Processing failed") {
        if (isDismissed(jobId)) return
        upsertJob(
            jobId = jobId,
            mode = synchronized(lock) { jobsById[jobId]?.mode },
            message = message,
            status = VideoProcessingJobState.Status.FAILED
        )
    }

    fun postCancelled(jobId: String) {
        clearJob(jobId)
    }

    fun postIdle(jobId: String) {
        synchronized(lock) {
            jobsById.remove(jobId)
        }
        publishJobs()
    }

    fun postIdle() {
        isProcessing = false
        currentProcessingPercent = VideoProcessingProgressText.DEFAULT_PERCENT
        processingMessage.postValue(null)
    }

    fun clearJob(jobId: String) {
        synchronized(lock) {
            jobsById.remove(jobId)
            dismissedJobIds.add(jobId)
        }
        publishJobs()
    }

    fun clearAllJobs() {
        synchronized(lock) {
            dismissedJobIds.addAll(jobsById.keys)
            jobsById.clear()
        }
        publishJobs()
    }

    private fun upsertJob(
        jobId: String,
        mode: VideoProcessOptions.ProcessingMode?,
        message: String,
        status: VideoProcessingJobState.Status,
        outputPath: String? = null,
        forcedPercent: Int? = null
    ) {
        val fallbackPercent = forcedPercent
            ?: synchronized(lock) { jobsById[jobId]?.percent }
            ?: VideoProcessingProgressText.DEFAULT_PERCENT
        val normalizedMessage = VideoProcessingProgressText.normalize(
            message,
            fallbackPercent
        )
        val percent = forcedPercent
            ?: VideoProcessingProgressText.extractPercent(normalizedMessage)
            ?: VideoProcessingProgressText.DEFAULT_PERCENT
        val didUpdate = synchronized(lock) {
            if (jobId in dismissedJobIds) {
                return@synchronized false
            }
            val previous = jobsById[jobId]
            jobsById[jobId] = VideoProcessingJobState(
                jobId = jobId,
                mode = mode ?: previous?.mode,
                message = normalizedMessage,
                percent = percent,
                status = status,
                outputPath = outputPath ?: previous?.outputPath,
                serverJobId = previous?.serverJobId,
                createdAtMs = previous?.createdAtMs ?: System.currentTimeMillis()
            )
            true
        }
        if (didUpdate) {
            publishJobs()
        }
    }

    private fun updateJob(jobId: String, transform: (VideoProcessingJobState) -> VideoProcessingJobState) {
        synchronized(lock) {
            val job = jobsById[jobId] ?: return
            jobsById[jobId] = transform(job)
        }
        publishJobs()
    }

    private fun publishJobs() {
        val snapshot = synchronized(lock) { jobsById.values.sortedBy { it.createdAtMs } }
        isProcessing = snapshot.any { it.isActive }
        currentProcessingPercent = snapshot.lastOrNull { it.isActive }?.percent
            ?: VideoProcessingProgressText.DEFAULT_PERCENT
        processingJobs.postValue(snapshot)
        processingMessage.postValue(snapshot.lastOrNull { it.isActive }?.message)
    }
}
