package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

object PlayerInstance {
    var player: ExoPlayer? = null
        private set
    private var isNeedResume = false

    private fun ensure(context: Context) {
        if (player != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(attrs, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    Log.d("Music", "state=$state")
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("Music", "Player error: ${error.errorCodeName}", error)
                }
            })
        }
    }

    fun needResume(): Boolean {
        val playing = player?.isPlaying == true
        isNeedResume = playing
        return playing
    }

    fun play(context: Context, url: String, loop: Boolean = true, autoPlay: Boolean = true) {
        ensure(context)
        val mediaItem = if (url.startsWith("/")) {
            MediaItem.fromUri(Uri.fromFile(File(url)))
        } else {
            MediaItem.fromUri(url)
        }
        player?.apply {
            setMediaItem(mediaItem)
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = autoPlay
            volume = 1f
        }
    }

    fun resumeIfNeed() { if (isNeedResume) { player?.play(); isNeedResume = false } }
    fun pause() = player?.pause()
    fun stop() { player?.stop(); player?.clearMediaItems() }
    fun setLoop(isLoop: Boolean) { player?.repeatMode = if (isLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    fun release() { player?.release(); player = null }
}