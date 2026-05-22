package com.example.gnssandopticalflowapp.screen.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.VideoProcessOptions

class VideoProcessOptionsViewModel : ViewModel() {

    enum class Algorithm {
        KLT,
        FARNEBACK,
        AI
    }

    data class UiState(
        val algorithm: Algorithm = Algorithm.AI,
        val processingMode: VideoProcessOptions.ProcessingMode = VideoProcessOptions.ProcessingMode.ONLINE,
        val useFarnebackHeatmap: Boolean = false
    ) {
        val showProcessing: Boolean
            get() = algorithm == Algorithm.AI

        val showDisplay: Boolean
            get() = algorithm == Algorithm.FARNEBACK || algorithm == Algorithm.AI

        val useFarneback: Boolean
            get() = algorithm == Algorithm.FARNEBACK

        val useAi: Boolean
            get() = algorithm == Algorithm.AI
    }

    private val _uiState = MutableLiveData(UiState())
    val uiState: LiveData<UiState> = _uiState

    fun currentState(): UiState {
        return _uiState.value ?: UiState()
    }

    fun selectAlgorithm(algorithm: Algorithm) {
        val current = currentState()

        _uiState.value = current.copy(
            algorithm = algorithm,
            processingMode = when (algorithm) {
                Algorithm.KLT,
                Algorithm.FARNEBACK -> VideoProcessOptions.ProcessingMode.OFFLINE

                Algorithm.AI -> current.processingMode
            },
            useFarnebackHeatmap = when (algorithm) {
                Algorithm.KLT -> false
                Algorithm.FARNEBACK,
                Algorithm.AI -> current.useFarnebackHeatmap
            }
        )
    }

    fun selectProcessingMode(processingMode: VideoProcessOptions.ProcessingMode) {
        val current = currentState()

        if (current.algorithm != Algorithm.AI) return

        _uiState.value = current.copy(
            processingMode = processingMode
        )
    }

    fun selectDisplayMode(useHeatmap: Boolean) {
        val current = currentState()

        if (!current.showDisplay) return

        _uiState.value = current.copy(
            useFarnebackHeatmap = useHeatmap
        )
    }
}