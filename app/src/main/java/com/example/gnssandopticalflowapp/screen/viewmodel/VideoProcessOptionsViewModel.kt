package com.example.gnssandopticalflowapp.screen.viewmodel

import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VideoProcessOptionsViewModel : ViewModel() {

    enum class MotionMode {
        STILL,
        MOVING
    }

    data class UiState(
        val processingMode: VideoProcessOptions.ProcessingMode = VideoProcessOptions.ProcessingMode.ONLINE,
        val useFarnebackHeatmap: Boolean = false,
        val motionMode: MotionMode = MotionMode.STILL,
        val roiSelectEnabled: Boolean = false,
        val selectedRoi: VideoProcessOptions.NormalizedRoi? = null,
        val sensitivity: Int = DEFAULT_SENSITIVITY
    ) {
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

    fun selectProcessingMode(processingMode: VideoProcessOptions.ProcessingMode) {
        _uiState.update { current ->
            current.copy(processingMode = processingMode)
        }
    }

    fun selectDisplayMode(useHeatmap: Boolean) {
        _uiState.update { current ->
            current.copy(useFarnebackHeatmap = useHeatmap)
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
