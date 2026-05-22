package com.example.gnssandopticalflowapp.screen.viewmodel

import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun currentState(): UiState {
        return _uiState.value
    }

    fun selectAlgorithm(algorithm: Algorithm) {
        _uiState.update { current ->
            current.copy(
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
    }

    fun selectProcessingMode(processingMode: VideoProcessOptions.ProcessingMode) {
        _uiState.update { current ->
            if (current.algorithm != Algorithm.AI) {
                current
            } else {
                current.copy(processingMode = processingMode)
            }
        }
    }

    fun selectDisplayMode(useHeatmap: Boolean) {
        _uiState.update { current ->
            if (!current.showDisplay) {
                current
            } else {
                current.copy(useFarnebackHeatmap = useHeatmap)
            }
        }
    }

    fun selectMotionMode(motionMode: MotionMode) {
        _uiState.update { current ->
            current.copy(motionMode = motionMode)
        }
    }

    fun enableRoiSelection() {
        _uiState.update { current ->
            current.copy(roiSelectEnabled = true)
        }
    }

    fun clearRoiSelection() {
        _uiState.update { current ->
            current.copy(
                roiSelectEnabled = false,
                selectedRoi = null
            )
        }
    }

    fun updateSelectedRoi(roi: VideoProcessOptions.NormalizedRoi?) {
        _uiState.update { current ->
            current.copy(selectedRoi = roi)
        }
    }

    fun updateSensitivity(progress: Int) {
        _uiState.update { current ->
            current.copy(
                sensitivity = progress.coerceIn(0, 100)
            )
        }
    }

    companion object {
        private const val DEFAULT_SENSITIVITY = 50
    }
}