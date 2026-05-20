package com.example.gnssandopticalflowapp.video

import androidx.lifecycle.MutableLiveData

object VideoProcessingBus {
    val processingMessage = MutableLiveData<String?>()
    val processedVideoPathToOpen = MutableLiveData<String?>()
    val videoLibraryUpdated = MutableLiveData<Long>()

    @Volatile
    var isProcessing: Boolean = false
        private set

    fun postProcessing(message: String) {
        isProcessing = true
        processingMessage.postValue(message)
    }

    fun postFinished(path: String) {
        isProcessing = false
        processingMessage.postValue(null)
        videoLibraryUpdated.postValue(System.currentTimeMillis())
        processedVideoPathToOpen.postValue(path)
    }

    fun postIdle() {
        isProcessing = false
        processingMessage.postValue(null)
    }
}
