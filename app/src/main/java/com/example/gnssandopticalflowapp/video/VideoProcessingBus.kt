package com.example.gnssandopticalflowapp.video

import androidx.lifecycle.MutableLiveData

object VideoProcessingBus {
    val processingMessage = MutableLiveData<String?>()
    val processedVideoPathToOpen = MutableLiveData<String?>()
    val videoLibraryUpdated = MutableLiveData<Long>()

    @Volatile
    var isProcessing: Boolean = false
        private set

    @Volatile
    private var currentProcessingPercent = VideoProcessingProgressText.DEFAULT_PERCENT

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

    fun postFinished(path: String) {
        isProcessing = false
        currentProcessingPercent = VideoProcessingProgressText.COMPLETE_PERCENT
        videoLibraryUpdated.postValue(System.currentTimeMillis())
        processedVideoPathToOpen.postValue(path)
    }

    fun postIdle() {
        isProcessing = false
        currentProcessingPercent = VideoProcessingProgressText.DEFAULT_PERCENT
        processingMessage.postValue(null)
    }
}
