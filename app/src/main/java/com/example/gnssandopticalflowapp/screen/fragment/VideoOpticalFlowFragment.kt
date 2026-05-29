package com.example.gnssandopticalflowapp.screen.fragment

import android.Manifest
import android.content.ContentValues
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentVideoOpticalFlowBinding
import com.example.gnssandopticalflowapp.util.VideoPlaybackSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoOpticalFlowFragment :
    BaseFragment<FragmentVideoOpticalFlowBinding>(FragmentVideoOpticalFlowBinding::inflate) {
    private var player: ExoPlayer? = null
    private var isPlaying = true
    private val progressUpdateIntervalMs = 16L

    private var videoWidth = 0
    private var videoHeight = 0
    private var isRotated = false
    private var playbackUnsupportedShown = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, progressUpdateIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = context?.let { ExoPlayer.Builder(it).build() }
        player?.playWhenReady = true
    }

    override fun FragmentVideoOpticalFlowBinding.initView() {
        tvTimer.text = formatTimer(0L, 0L)

        binding.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                val surface = Surface(surfaceTexture)
                player?.setVideoSurface(surface)

                val path = mainViewModel.selectedVideoPath.value
                if (!path.isNullOrEmpty()) {
                    play(path)
                    setVideoTitle(path)
                }
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                player?.setVideoSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        player?.addListener(object : Player.Listener {

            @OptIn(UnstableApi::class)
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(
                    "VIDEO-PLAYER",
                    "Video size changed: ${videoSize.width}x${videoSize.height}"
                )

                checkIfFragmentAttached {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height

                    if (videoWidth <= 0 || videoHeight <= 0) return@checkIfFragmentAttached

                    val aspectRatio =
                        (videoWidth * videoSize.pixelWidthHeightRatio) / videoHeight

                    binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)

                    val layoutParams =
                        binding.videoGroup.layoutParams as ConstraintLayout.LayoutParams

                    layoutParams.width = 0
                    layoutParams.height = 0

                    // Ratio dạng width:height
                    val ratioWidth = (videoWidth * videoSize.pixelWidthHeightRatio).toInt()
                    val ratioHeight = videoHeight

                    layoutParams.dimensionRatio = "$ratioWidth:$ratioHeight"

                    binding.videoGroup.layoutParams = layoutParams

                    isRotated = false
                    setVideoFrameTransform(rotation = 0f, scale = 1f)

                    if (videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight) ivFullScreen.show()
                    else ivFullScreen.hide()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("VIDEO-PLAYER", "Playback state changed: $playbackState")
                if (playbackState == Player.STATE_READY) {
                    updateProgress()
                    startProgressUpdates()
                }
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                binding.ivVideoControl.setImageResource(
                    if (isPlayingNow) R.drawable.ic_pause_video else R.drawable.ic_play_video
                )

                if (isPlayingNow) {
                    startProgressUpdates()
                } else {
                    handler.removeCallbacks(updateProgressAction)
                    updateProgress()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w("VIDEO-PLAYER", "Playback unsupported: ${error.message}")
                checkIfFragmentAttached {
                    showPlaybackUnsupportedMessage()
                }
            }
        })
    }

    private fun setVideoTitle(path: String) {
        val file = File(path)
        val timestamp = file.lastModified()
        val sdf = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        binding.tvVideoDate.text = sdf.format(Date(timestamp))
    }

    override fun FragmentVideoOpticalFlowBinding.initListener() {
        ivFullScreen.setSingleClick {
            if (videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight) {
                isRotated = !isRotated
                if (isRotated) {
                    val currentHeight = videoGroup.height.toFloat()
                    val currentWidth = videoGroup.width.toFloat()

                    val hAvail = ivVideoControl.top.toFloat() - ivBack.bottom.toFloat() - (24f * resources.displayMetrics.density)
                    val wAvail = currentWidth

                    if (currentHeight > 0 && currentWidth > 0) {
                        val scaleW = wAvail / currentHeight
                        val scaleH = hAvail / currentWidth
                        val scale = minOf(scaleW, scaleH)

                        animateVideoFrameTransform(rotation = 90f, scale = scale)
                    } else {
                        setVideoFrameTransform(rotation = 90f, scale = 1f)
                    }
                } else {
                    animateVideoFrameTransform(rotation = 0f, scale = 1f)
                }
            }
        }
        ivBack.setOnClickListener { onBack() }

        ivDownload.setSingleClick {
            saveVideo()
        }

        ivVideoControl.setOnClickListener {
            if (isPlaying) {
                player?.pause()
            } else {
                player?.play()
            }
        }

        videoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seekPosition = progress.toLong()
                    player?.seekTo(seekPosition)
                    binding.tvTimer.text = formatTimer(seekPosition, normalizedDurationMs())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun saveVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveVideoToPhotos()
        } else {
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        doRequestPermission(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            object : IPermissionListener {
                override fun onAllow() {
                    saveVideoToPhotosBelowAndroid10()
                }

                override fun onDenied() {
                    showDownloadResult(success = false)
                }

                override fun onNeverAskAgain(permission: String) {
                    showDialogRequestStoragePermission()
                }
            }
        )
    }

    private fun saveVideoToPhotosBelowAndroid10() {
        val sourceFile = currentVideoFile()
        if (sourceFile == null) {
            showDownloadResult(success = false)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    val cameraDir = File(dcimDir, "Camera")
                    if (!cameraDir.exists() && !cameraDir.mkdirs()) {
                        throw IOException("Cannot create Camera directory")
                    }

                    val outputFile = File(cameraDir, downloadFileName())
                    sourceFile.copyTo(outputFile, overwrite = true)
                    MediaScannerConnection.scanFile(
                        safeContext(),
                        arrayOf(outputFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                }.isSuccess
            }
            showDownloadResult(saved)
        }
    }

    private fun saveVideoToPhotos() {
        val context = context ?: return
        val sourceFile = currentVideoFile()
        if (sourceFile == null) {
            showDownloadResult(success = false)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, downloadFileName())
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext false

                runCatching {
                    resolver.openOutputStream(uri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Cannot open output stream")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                    true
                }.getOrElse { error ->
                    Log.e("VIDEO-PLAYER", "Save video failed: ${error.message}", error)
                    resolver.delete(uri, null, null)
                    false
                }
            }
            showDownloadResult(saved)
        }
    }

    private fun currentVideoFile(): File? {
        val path = mainViewModel.selectedVideoPath.value?.takeIf { it.isNotBlank() } ?: return null
        return File(path).takeIf { it.isFile && it.length() > 0L }
    }

    private fun downloadFileName(): String {
        return "optical_flow_${System.currentTimeMillis()}.mp4"
    }

    private fun showDownloadResult(success: Boolean) {
        if (!isAdded) return
        Toast.makeText(
            safeContext(),
            if (success) "Video saved to Photos" else "Cannot save video",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDialogRequestStoragePermission() {
        if (!isAdded) return
        AlertDialog.Builder(safeContext())
            .setTitle("Storage permission required")
            .setMessage("Enable storage permission in Settings to save this video.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Settings") { _, _ -> openAppSettings() }
            .show()
    }

    private fun updateProgress() {
        val player = player ?: return
        val duration = normalizedDurationMs()
        val position = player.currentPosition
            .coerceAtLeast(0L)
            .let { if (duration > 0L) it.coerceAtMost(duration) else it }

        val max = if (duration > 0L) duration else 100L
        binding.videoProgress.max = max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.videoProgress.progress = position
            .coerceAtMost(binding.videoProgress.max.toLong())
            .toInt()
        binding.tvTimer.text = formatTimer(position, duration)
    }

    private fun animateVideoFrameTransform(rotation: Float, scale: Float) {
        listOf(binding.bgGlass, binding.videoGroup).forEach { view ->
            view.animate().cancel()
            view.animate()
                .rotation(rotation)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(300)
                .start()
        }
    }

    private fun setVideoFrameTransform(rotation: Float, scale: Float) {
        listOf(binding.bgGlass, binding.videoGroup).forEach { view ->
            view.animate().cancel()
            view.rotation = rotation
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    private fun startProgressUpdates() {
        handler.removeCallbacks(updateProgressAction)
        handler.post(updateProgressAction)
    }

    private fun normalizedDurationMs(): Long {
        val duration = player?.duration ?: 0L
        return if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
    }

    private fun formatTimer(positionMs: Long, durationMs: Long): String {
        return "${formatVideoTime(positionMs)}/${formatVideoTime(durationMs)}"
    }

    private fun formatVideoTime(timeMs: Long): String {
        val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun play(url: String) {
        try {
            val file = File(url)
            val uri = file.toUri()
            if (!VideoPlaybackSupport.canDecode(safeContext(), uri)) {
                showPlaybackUnsupportedMessage()
                return
            }
            playbackUnsupportedShown = false
            binding.videoView.show()
            val mediaItem = MediaItem.fromUri(uri)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            player?.repeatMode = Player.REPEAT_MODE_ONE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showPlaybackUnsupportedMessage() {
        player?.stop()
        handler.removeCallbacks(updateProgressAction)
        binding.videoView.hide()
        binding.ivVideoControl.setImageResource(R.drawable.ic_play_video)
        isPlaying = false
        if (playbackUnsupportedShown) return
        playbackUnsupportedShown = true
        Toast.makeText(
            safeContext(),
            "This device cannot play this video's codec or resolution",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        isPlaying = false
        binding.ivVideoControl.setImageResource(R.drawable.ic_play_video)
        handler.removeCallbacks(updateProgressAction)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        handler.removeCallbacks(updateProgressAction)
    }

    override fun initObserver() {
    }
}
