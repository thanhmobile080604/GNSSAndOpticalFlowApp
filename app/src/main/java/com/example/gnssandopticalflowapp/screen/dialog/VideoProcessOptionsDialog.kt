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
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.constraintlayout.widget.ConstraintLayout
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
import kotlin.math.roundToInt

class VideoProcessOptionsDialog :
    BaseDialogFragment<DialogVideoProcessOptionsBinding>(DialogVideoProcessOptionsBinding::inflate) {

    var onApplyOptions: ((VideoProcessOptions) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var previewSurface: Surface? = null
    private var previewPrepared = false
    private var videoUri: Uri? = null
    private var videoAspectRatio = 0f

    private var useFarneback = false
    private var useFarnebackHeatmap = false
    private var selectedMotionMode = VideoMotionMode.STILL
    private var roiSelectEnabled = false
    private var selectedRoi: VideoProcessOptions.NormalizedRoi? = null

    override fun DialogVideoProcessOptionsBinding.initView() {
        isCancelable = true
        videoUri = arguments?.getString(ARG_VIDEO_URI)?.toUri()
        sensitivityBar.progress = DEFAULT_SENSITIVITY

        setupPreviewPlayer()
        setupPreviewSurface()
        setupRoiOverlay()
        updateAlgorithmModeUi()
        updateFarnebackDisplayUi()
        updateMotionModeUi()
        updateRoiUi()
        updateSensitivityValue(DEFAULT_SENSITIVITY)
    }

    override fun DialogVideoProcessOptionsBinding.initListener() {
        btnAlgorithmKlt.setSingleClick {
            useFarneback = false
            updateAlgorithmModeUi()
            updateFarnebackDisplayUi()
        }

        btnAlgorithmFarneback.setSingleClick {
            useFarneback = true
            updateAlgorithmModeUi()
            updateFarnebackDisplayUi()
        }

        btnFarnebackVectors.setSingleClick {
            if (!useFarneback) return@setSingleClick
            useFarnebackHeatmap = false
            updateFarnebackDisplayUi()
        }

        btnFarnebackHeatmap.setSingleClick {
            if (!useFarneback) return@setSingleClick
            useFarnebackHeatmap = true
            updateFarnebackDisplayUi()
        }

        btnMotionStill.setSingleClick {
            selectedMotionMode = VideoMotionMode.STILL
            updateMotionModeUi()
        }

        btnMotionMoving.setSingleClick {
            selectedMotionMode = VideoMotionMode.MOVING
            updateMotionModeUi()
        }

        btnRoiSelect.setSingleClick {
            roiSelectEnabled = true
            roiOverlay.setSelectionEnabled(true)
            updateRoiUi()
        }

        btnRoiFull.setSingleClick {
            roiSelectEnabled = false
            selectedRoi = null
            roiOverlay.setSelectionEnabled(false)
            roiOverlay.clearSelection()
            updateRoiUi()
        }

        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityValue(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        tvCancel.setSingleClick {
            dismissAllowingStateLoss()
        }

        tvApply.setSingleClick {
            if (roiSelectEnabled && selectedRoi == null) {
                Toast.makeText(requireContext(), CLOSED_AREA_MESSAGE, Toast.LENGTH_SHORT).show()
                return@setSingleClick
            }

            val options = VideoProcessOptions(
                isMoving = selectedMotionMode == VideoMotionMode.MOVING,
                useFarneback = useFarneback,
                sensitivity = sensitivityBar.progress.coerceIn(0, 100),
                useFarnebackHeatmap = useFarnebackHeatmap,
                roi = selectedRoi.takeIf { roiSelectEnabled }
            )
            onApplyOptions?.invoke(options)
            dismissAllowingStateLoss()
        }
    }

    override fun initObserver() = Unit

    override fun onBackPressed() {
        dismissAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        if (previewPrepared) {
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

    private fun DialogVideoProcessOptionsBinding.setupPreviewPlayer() {
        player = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            volume = 0f
            addListener(object : Player.Listener {
                @OptIn(UnstableApi::class)
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!isAdded || view == null) return
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    if (aspectRatio > 0f) {
                        videoAspectRatio = aspectRatio
                        binding.root.post { applyVideoPreviewBounds() }
                    }
                }
            })
        }
    }

    private fun DialogVideoProcessOptionsBinding.setupPreviewSurface() {
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

    private fun DialogVideoProcessOptionsBinding.setupRoiOverlay() {
        roiOverlay.onRoiChanged = {
            selectedRoi = roiOverlay.normalizedRoi?.let { roi ->
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
            updateRoiUi()
        }
        roiOverlay.onInvalidSelection = {
            Toast.makeText(requireContext(), CLOSED_AREA_MESSAGE, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLoopPreview() {
        if (previewPrepared) {
            player?.play()
            return
        }

        val uri = videoUri ?: return
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.play()
        previewPrepared = true
    }

    private fun updateAlgorithmModeUi() = with(binding) {
        setSegmentSelected(btnAlgorithmKlt, !useFarneback)
        setSegmentSelected(btnAlgorithmFarneback, useFarneback)
    }

    private fun updateFarnebackDisplayUi() = with(binding) {
        setSegmentSelected(btnFarnebackVectors, !useFarnebackHeatmap)
        setSegmentSelected(btnFarnebackHeatmap, useFarnebackHeatmap)
        btnFarnebackVectors.isEnabled = useFarneback
        btnFarnebackHeatmap.isEnabled = useFarneback
        val alpha = if (useFarneback) 1f else 0.45f
        farnebackViewCard.alpha = alpha
        btnFarnebackVectors.alpha = alpha
        btnFarnebackHeatmap.alpha = alpha
    }

    private fun updateMotionModeUi() = with(binding) {
        setSegmentSelected(btnMotionStill, selectedMotionMode == VideoMotionMode.STILL)
        setSegmentSelected(btnMotionMoving, selectedMotionMode == VideoMotionMode.MOVING)
    }

    private fun updateRoiUi() = with(binding) {
        val hasRoi = selectedRoi != null
        setSegmentSelected(btnRoiSelect, roiSelectEnabled || hasRoi)
        setSegmentSelected(btnRoiFull, !roiSelectEnabled && !hasRoi)
        tvRoiLabel.text = if (hasRoi) "ROI On" else "ROI"
    }

    private fun updateSensitivityValue(sensitivity: Int) {
        binding.tvSensitivity.text = "Sensitivity: ${sensitivity.coerceIn(0, 100)}"
    }

    private fun setSegmentSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_gradient_update_button_12 else R.drawable.bg_glass_chip
        )
    }

    @OptIn(UnstableApi::class)
    private fun applyVideoPreviewBounds() = with(binding) {
        val aspectRatio = videoAspectRatio.takeIf { it > 0f } ?: return@with
        val maxWidth = optionsScroll.width.takeIf { it > 0 } ?: return@with
        val layoutParams = videoPreviewCard.layoutParams as ConstraintLayout.LayoutParams

        val extraGap = dpToPx(6)
        val maxHeight = (optionsScroll.top - tvTitle.bottom - layoutParams.topMargin - layoutParams.bottomMargin - extraGap)
            .coerceAtLeast(1)

        val targetSize = if (aspectRatio >= 1f) {
            val widthByMaxWidth = maxWidth
            val heightByMaxWidth = (widthByMaxWidth / aspectRatio).roundToInt()
            if (heightByMaxWidth <= maxHeight) {
                widthByMaxWidth to heightByMaxWidth.coerceAtLeast(1)
            } else {
                ((maxHeight * aspectRatio).roundToInt().coerceAtMost(maxWidth).coerceAtLeast(1)) to maxHeight
            }
        } else {
            val heightByMaxHeight = maxHeight
            val widthByMaxHeight = (heightByMaxHeight * aspectRatio).roundToInt()
            if (widthByMaxHeight <= maxWidth) {
                widthByMaxHeight.coerceAtLeast(1) to heightByMaxHeight
            } else {
                maxWidth to (maxWidth / aspectRatio).roundToInt().coerceAtMost(maxHeight).coerceAtLeast(1)
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

    private enum class VideoMotionMode {
        STILL,
        MOVING
    }

    companion object {
        private const val TAG = "VideoProcessOptionsDialog"
        private const val DEFAULT_SENSITIVITY = 50
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
