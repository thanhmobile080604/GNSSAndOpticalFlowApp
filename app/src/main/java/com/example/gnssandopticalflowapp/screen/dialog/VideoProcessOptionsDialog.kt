package com.example.gnssandopticalflowapp.screen.dialog

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogVideoProcessOptionsBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.screen.viewmodel.VideoProcessOptionsViewModel
import com.example.gnssandopticalflowapp.util.VideoPlaybackSupport
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VideoProcessOptionsDialog :
    BaseDialogFragment<DialogVideoProcessOptionsBinding>(DialogVideoProcessOptionsBinding::inflate) {

    var onApplyOptions: ((VideoProcessOptions) -> Unit)? = null

    private val optionsViewModel: VideoProcessOptionsViewModel by viewModels()

    private var player: ExoPlayer? = null
    private var previewSurface: Surface? = null
    private var previewPrepared = false
    private var previewUnsupportedShown = false
    private var videoUri: Uri? = null
    private var videoAspectRatio = 0f

    override fun DialogVideoProcessOptionsBinding.initView() {
        isCancelable = true

        videoUri = arguments?.getString(ARG_VIDEO_URI)?.toUri()

        setupPreviewPlayer()
        setupPreviewSurface()
        setupRoiOverlay()

        renderOptionsState(optionsViewModel.currentState())
    }

    override fun DialogVideoProcessOptionsBinding.initListener() {
        btnAlgorithmKlt.setSingleClick {
            optionsViewModel.selectAlgorithm(VideoProcessOptionsViewModel.Algorithm.KLT)
        }

        btnAlgorithmFarneback.setSingleClick {
            optionsViewModel.selectAlgorithm(VideoProcessOptionsViewModel.Algorithm.FARNEBACK)
        }

        btnAlgorithmAi.setSingleClick {
            optionsViewModel.selectAlgorithm(VideoProcessOptionsViewModel.Algorithm.AI)
        }

        btnProcessingOffline.setSingleClick {
            optionsViewModel.selectProcessingMode(VideoProcessOptions.ProcessingMode.OFFLINE)
        }

        btnProcessingMyServer.setSingleClick {
            optionsViewModel.selectProcessingMode(VideoProcessOptions.ProcessingMode.ONLINE)
        }

        btnFarnebackVectors.setSingleClick {
            optionsViewModel.selectDisplayMode(useHeatmap = false)
        }

        btnFarnebackHeatmap.setSingleClick {
            optionsViewModel.selectDisplayMode(useHeatmap = true)
        }

        btnMotionStill.setSingleClick {
            optionsViewModel.selectMotionMode(VideoProcessOptionsViewModel.MotionMode.STILL)
        }

        btnMotionMoving.setSingleClick {
            optionsViewModel.selectMotionMode(VideoProcessOptionsViewModel.MotionMode.MOVING)
        }

        btnRoiSelect.setSingleClick {
            roiOverlay.setSelectionEnabled(true)
            optionsViewModel.enableRoiSelection()
        }

        btnRoiFull.setSingleClick {
            roiOverlay.setSelectionEnabled(false)
            roiOverlay.clearSelection()
            optionsViewModel.clearRoiSelection()
        }

        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (!fromUser) return
                optionsViewModel.updateSensitivity(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        tvCancel.setSingleClick {
            dismissAllowingStateLoss()
        }

        tvApply.setSingleClick {
            applyOptions()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                optionsViewModel.uiState.collect { state ->
                    binding.renderOptionsState(state)
                }
            }
        }
    }

    override fun onBackPressed() {
        dismissAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()

        if (previewPrepared && !previewUnsupportedShown) {
            player?.play()
        }
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        player?.clearVideoSurface()

        previewSurface?.release()
        previewSurface = null

        player?.release()
        player = null

        onApplyOptions = null

        super.onDestroyView()
    }

    private fun applyOptions() = with(binding) {
        val state = optionsViewModel.currentState()

        if (state.shouldRequireRoiBeforeApply) {
            Toast.makeText(requireContext(), CLOSED_AREA_MESSAGE, Toast.LENGTH_SHORT).show()
            return@with
        }

        val options = VideoProcessOptions(
            isMoving = state.isMoving,
            useFarneback = state.useFarneback,
            sensitivity = state.sensitivity,
            useFarnebackHeatmap = state.useFarnebackHeatmap,
            useAi = state.useAi,
            roi = state.roiForApply,
            processingMode = state.processingMode
        )

        onApplyOptions?.invoke(options)
        dismissAllowingStateLoss()
    }

    private fun DialogVideoProcessOptionsBinding.renderOptionsState(
        state: VideoProcessOptionsViewModel.UiState
    ) {
        setSegmentSelected(
            btnAlgorithmKlt,
            state.algorithm == VideoProcessOptionsViewModel.Algorithm.KLT
        )

        setSegmentSelected(
            btnAlgorithmFarneback,
            state.algorithm == VideoProcessOptionsViewModel.Algorithm.FARNEBACK
        )

        setSegmentSelected(
            btnAlgorithmAi,
            state.algorithm == VideoProcessOptionsViewModel.Algorithm.AI
        )

        processingModeCard.isVisible = state.showProcessing
        farnebackViewCard.isVisible = state.showDisplay

        setSegmentSelected(
            btnProcessingOffline,
            state.processingMode == VideoProcessOptions.ProcessingMode.OFFLINE
        )

        setSegmentSelected(
            btnProcessingMyServer,
            state.processingMode == VideoProcessOptions.ProcessingMode.ONLINE
        )

        setSegmentSelected(
            btnFarnebackVectors,
            !state.useFarnebackHeatmap
        )

        setSegmentSelected(
            btnFarnebackHeatmap,
            state.useFarnebackHeatmap
        )

        setSegmentSelected(
            btnMotionStill,
            state.motionMode == VideoProcessOptionsViewModel.MotionMode.STILL
        )

        setSegmentSelected(
            btnMotionMoving,
            state.motionMode == VideoProcessOptionsViewModel.MotionMode.MOVING
        )

        setSegmentSelected(
            btnRoiSelect,
            state.roiSelectEnabled || state.hasRoi
        )

        setSegmentSelected(
            btnRoiFull,
            !state.roiSelectEnabled && !state.hasRoi
        )

        tvRoiLabel.text = if (state.hasRoi) {
            "ROI On"
        } else {
            "ROI"
        }

        roiOverlay.setSelectionEnabled(state.roiSelectEnabled)

        if (sensitivityBar.progress != state.sensitivity) {
            sensitivityBar.progress = state.sensitivity
        }

        tvSensitivity.text = "Sensitivity: ${state.sensitivity}"
    }

    private fun setupPreviewPlayer() {
        player = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            volume = 0f

            addListener(object : Player.Listener {
                @OptIn(UnstableApi::class)
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!isAdded || view == null) return
                    if (videoSize.width <= 0 || videoSize.height <= 0) return

                    val aspectRatio =
                        (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height

                    if (aspectRatio > 0f) {
                        videoAspectRatio = aspectRatio
                        binding.root.post {
                            applyVideoPreviewBounds()
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    disableUnsupportedPreview()
                }
            })
        }
    }

    private fun setupPreviewSurface() = with(binding) {
        videoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                bindPreviewSurface(surfaceTexture)
                startLoopPreview()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                player?.setVideoSurface(null)

                previewSurface?.release()
                previewSurface = null

                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

        if (videoPreview.isAvailable) {
            videoPreview.surfaceTexture?.let { surfaceTexture ->
                bindPreviewSurface(surfaceTexture)
                startLoopPreview()
            }
        }
    }

    private fun bindPreviewSurface(surfaceTexture: SurfaceTexture) {
        previewSurface?.release()

        previewSurface = Surface(surfaceTexture)
        player?.setVideoSurface(previewSurface)
    }

    private fun setupRoiOverlay() = with(binding) {
        roiOverlay.onRoiChanged = {
            val selectedRoi = roiOverlay.normalizedRoi?.let { roi ->
                VideoProcessOptions.NormalizedRoi(
                    left = roi.left,
                    top = roi.top,
                    right = roi.right,
                    bottom = roi.bottom,
                    viewAspectRatio = if (roiOverlay.height > 0) {
                        roiOverlay.width.toFloat() / roiOverlay.height.toFloat()
                    } else {
                        1f
                    },
                    pathPoints = roiOverlay.normalizedPath.map { point ->
                        VideoProcessOptions.NormalizedPoint(point.x, point.y)
                    }
                )
            }

            optionsViewModel.updateSelectedRoi(selectedRoi)
        }

        roiOverlay.onInvalidSelection = {
            Toast.makeText(requireContext(), CLOSED_AREA_MESSAGE, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLoopPreview() {
        if (previewPrepared) {
            if (!previewUnsupportedShown) {
                player?.play()
            }
            return
        }

        val uri = videoUri ?: return
        val context = context ?: return
        if (!VideoPlaybackSupport.canDecode(context, uri)) {
            previewPrepared = true
            disableUnsupportedPreview()
            return
        }

        binding.videoPreview.isVisible = true
        previewUnsupportedShown = false
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.play()

        previewPrepared = true
    }

    private fun disableUnsupportedPreview() {
        if (!isAdded || view == null) return
        player?.stop()
        binding.videoPreview.isVisible = false
        if (previewUnsupportedShown) return
        previewUnsupportedShown = true
        Toast.makeText(
            requireContext(),
            "This device cannot preview this video",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setSegmentSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) {
                R.drawable.bg_gradient_update_button_12
            } else {
                R.drawable.bg_glass_chip
            }
        )
    }

    @OptIn(UnstableApi::class)
    private fun applyVideoPreviewBounds() = with(binding) {
        val aspectRatio = videoAspectRatio.takeIf { it > 0f } ?: return@with
        val maxWidth = optionsScroll.width.takeIf { it > 0 } ?: return@with
        val layoutParams = videoPreviewCard.layoutParams as ConstraintLayout.LayoutParams

        val extraGap = dpToPx(6)
        val maxHeight = (
                optionsScroll.top -
                        tvTitle.bottom -
                        layoutParams.topMargin -
                        layoutParams.bottomMargin -
                        extraGap
                ).coerceAtLeast(1)

        val targetSize = if (aspectRatio >= 1f) {
            val widthByMaxWidth = maxWidth
            val heightByMaxWidth = (widthByMaxWidth / aspectRatio).roundToInt()

            if (heightByMaxWidth <= maxHeight) {
                widthByMaxWidth to heightByMaxWidth.coerceAtLeast(1)
            } else {
                val width = (maxHeight * aspectRatio)
                    .roundToInt()
                    .coerceAtMost(maxWidth)
                    .coerceAtLeast(1)

                width to maxHeight
            }
        } else {
            val heightByMaxHeight = maxHeight
            val widthByMaxHeight = (heightByMaxHeight * aspectRatio).roundToInt()

            if (widthByMaxHeight <= maxWidth) {
                widthByMaxHeight.coerceAtLeast(1) to heightByMaxHeight
            } else {
                val height = (maxWidth / aspectRatio)
                    .roundToInt()
                    .coerceAtMost(maxHeight)
                    .coerceAtLeast(1)

                maxWidth to height
            }
        }

        previewAspectFrame.setAspectRatio(aspectRatio)

        layoutParams.width = targetSize.first
        layoutParams.height = targetSize.second
        layoutParams.dimensionRatio = null

        videoPreviewCard.layoutParams = layoutParams
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val TAG = "VideoProcessOptionsDialog"
        private const val ARG_VIDEO_URI = "video_uri"
        private const val CLOSED_AREA_MESSAGE = "You must draw a closed area"

        fun show(
            fragmentManager: FragmentManager,
            videoUri: Uri,
            onApplyOptions: (VideoProcessOptions) -> Unit
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return

            VideoProcessOptionsDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_VIDEO_URI, videoUri.toString())
                }

                this.onApplyOptions = onApplyOptions
            }.show(fragmentManager, TAG)
        }
    }
}
