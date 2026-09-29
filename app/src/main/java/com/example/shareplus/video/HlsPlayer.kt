package com.example.shareplus.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * "AirPlay video": apps like YouTube hand the TV an HLS URL (served back through UxPlay's
 * local proxy) instead of mirroring pixels; the iPhone becomes the remote. Play/pause/seek
 * from the iPhone arrive as rate/scrub calls, and the iPhone's progress bar is driven by
 * the position we push back every REPORT_MS via [onInfo].
 */
class HlsPlayer {

    // position s, duration s (-1 = finished), rate, ready
    var onInfo: ((Float, Float, Float, Boolean) -> Unit)? = null
    var onVideoSize: ((Int, Int) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null

    private val tick = object : Runnable {
        override fun run() {
            report()
            main.postDelayed(this, REPORT_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded?.invoke()
            report()
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "AirPlay video error", error)
            onEnded?.invoke()
        }
        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.height > 0) onVideoSize?.invoke((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
        }
    }

    fun play(context: Context, url: String, startSeconds: Float) = main.post {
        release()
        Log.i(TAG, "AirPlay video: $url @ ${startSeconds}s")
        player = ExoPlayer.Builder(context).build().apply {
            addListener(listener)
            surface?.let { setVideoSurface(it) }
            setMediaItem(MediaItem.fromUri(url), (startSeconds * 1000).toLong())
            playWhenReady = true
            prepare()
        }
        main.postDelayed(tick, REPORT_MS)
    }

    fun seek(seconds: Float) = main.post { player?.seekTo((seconds * 1000).toLong()) }

    // rate 0 = pause, 1 = play
    fun setRate(rate: Float) = main.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate)
            p.playWhenReady = true
        }
    }

    fun togglePause() = main.post { player?.let { it.playWhenReady = !it.playWhenReady } }

    fun stop() = main.post {
        release()
        onInfo?.invoke(0f, -1f, 0f, false)
    }

    fun attachSurface(s: Surface) = main.post {
        surface = s
        player?.setVideoSurface(s)
    }

    fun detachSurface(s: Surface) = main.post {
        if (surface !== s) return@post
        surface = null
        player?.setVideoSurface(null)
    }

    private fun release() {
        main.removeCallbacks(tick)
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
    }

    private fun report() {
        val p = player ?: return
        val durationMs = p.duration
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onInfo?.invoke(p.currentPosition / 1000f,
            if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f, rate, ready)
    }

    private companion object {
        const val TAG = "SharePlus"
        const val REPORT_MS = 250L
    }
}
