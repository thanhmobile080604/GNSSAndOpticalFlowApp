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

    enum class MotionMode {
        STILL,
        MOVING
    }

    data class UiState(
        val algorithm: Algorithm = Algorithm.AI,
        val processingMode: VideoProcessOptions.ProcessingMode = VideoProcessOptions.ProcessingMode.OFFLINE,
        val useFarnebackHeatmap: Boolean = false,
        val motionMode: MotionMode = MotionMode.STILL,
        val roiSelectEnabled: Boolean = false,
        val selectedRoi: VideoProcessOptions.NormalizedRoi? = null,
        val sensitivity: Int = DEFAULT_SENSITIVITY
    ) {
        val showProcessing: Boolean
            get() = algorithm == Algorithm.AI

        val showDisplay: Boolean
            get() = algorithm == Algorithm.FARNEBACK || algorithm == Algorithm.AI

        val useFarneback: Boolean
            get() = algorithm == Algorithm.FARNEBACK

        val useAi: Boolean
            get() = algorithm == Algorithm.AI

        val isMoving: Boolean
            get() = motionMode == MotionMode.MOVING

        val hasRoi: Boolean
            get() = selectedRoi != null

        val shouldRequireRoiBeforeApply: Boolean
            get() = roiSelectEnabled && selectedRoi == null

        val roiForApply: VideoProcessOptions.NormalizedRoi?
            get() = selectedRoi.takeIf { roiSelectEnabled }
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

    fun selectMotionMode(motionMode: MotionMode) {
        val current = currentState()

        _uiState.value = current.copy(
            motionMode = motionMode
        )
    }

    fun enableRoiSelection() {
        val current = currentState()

        _uiState.value = current.copy(
            roiSelectEnabled = true
        )
    }

    fun clearRoiSelection() {
        val current = currentState()

        _uiState.value = current.copy(
            roiSelectEnabled = false,
            selectedRoi = null
        )
    }

    fun updateSelectedRoi(roi: VideoProcessOptions.NormalizedRoi?) {
        val current = currentState()

        _uiState.value = current.copy(
            selectedRoi = roi
        )
    }

    fun updateSensitivity(progress: Int) {
        val current = currentState()

        _uiState.value = current.copy(
            sensitivity = progress.coerceIn(0, 100)
        )
    }
    companion object {
        private const val DEFAULT_SENSITIVITY = 50
    }
}