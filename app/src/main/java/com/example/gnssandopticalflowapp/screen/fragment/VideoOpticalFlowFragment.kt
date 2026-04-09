package com.example.gnssandopticalflowapp.screen.fragment

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.databinding.FragmentVideoOpticalFlowBinding
import kotlinx.coroutines.Runnable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoOpticalFlowFragment : BaseFragment<FragmentVideoOpticalFlowBinding>(FragmentVideoOpticalFlowBinding::inflate) {
    private var player: ExoPlayer? = null
    private var isPlaying = true
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = context?.let { ExoPlayer.Builder(it).build() }
        player?.playWhenReady = true
    }

    override fun FragmentVideoOpticalFlowBinding.initView() {
        binding.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
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
                Log.d("VIDEO-PLAYER", "Video size changed: ${videoSize.width}x${videoSize.height}")
                checkIfFragmentAttached {
                    val aspectRatio = if (videoSize.height == 0) 1f 
                        else (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("VIDEO-PLAYER", "Playback state changed: $playbackState")
                if (playbackState == Player.STATE_READY) {
                    binding.videoProgress.max = player?.duration?.toInt() ?: 100
                    handler.post(updateProgressAction)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("VIDEO-PLAYER", "ExoPlayer Error: ${error.message}", error)
                checkIfFragmentAttached {
                    Toast.makeText(requireContext(), "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
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
        ivBack.setOnClickListener { onBack() }

        ivVideoControl.setOnClickListener {
            if (isPlaying) {
                player?.pause()
                ivVideoControl.setImageResource(R.drawable.ic_play_video)
            } else {
                player?.play()
                ivVideoControl.setImageResource(R.drawable.ic_pause_video)
            }
            isPlaying = !isPlaying
        }

        videoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateProgress() {
        player?.let {
            binding.videoProgress.progress = it.currentPosition.toInt()
        }
    }

    private fun play(url: String) {
        try {
            val file = File(url)
            val uri = file.toUri()
            val mediaItem = MediaItem.fromUri(uri)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            player?.repeatMode = Player.REPEAT_MODE_ONE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
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
