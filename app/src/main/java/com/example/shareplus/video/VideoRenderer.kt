package com.example.shareplus.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * Decodes AirPlay mirror frames (Annex-B H.264/H.265, parameter sets in-band) with MediaCodec.
 *
 * The codec renders into a private "parking" surface until the Activity attaches its
 * SurfaceView, and is moved between surfaces with setOutputSurface(). iOS only sends a
 * keyframe when a stream starts, so restarting the codec on every surface change would
 * leave the screen black until the iPhone reconnects.
 */
class VideoRenderer {

    var onVideoSize: ((Int, Int) -> Unit)? = null

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var codecIsH265 = false
    private var paramSets: ByteArray? = null   // SPS/VPS the running codec was started with
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    private var queuedAny = false

    private val parking = Surface(SurfaceTexture(false))
    @Volatile private var display: Surface? = null

    // stats for the info overlay
    @Volatile var codecName = ""; private set
    @Volatile var fps = 0; private set
    @Volatile var dropped = 0; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    private var framesInWindow = 0
    private var windowStart = 0L

    fun attachSurface(surface: Surface) = synchronized(lock) {
        display = surface
        moveOutput(surface)
    }

    fun detachSurface(surface: Surface) = synchronized(lock) {
        if (display !== surface) return@synchronized
        display = null
        moveOutput(parking)
    }

    private fun moveOutput(target: Surface) {
        val c = codec ?: return
        try {
            c.setOutputSurface(target)
        } catch (e: Exception) {
            Log.w(TAG, "setOutputSurface failed; restarting at next keyframe", e)
            stopCodec()
        }
    }

    // called on the native mirror thread for every frame
    fun feed(data: ByteArray, ptsNs: Long, isH265: Boolean) = synchronized(lock) {
        val sets = parameterSets(data, isH265)
        val restart = codec == null || isH265 != codecIsH265 ||
            (sets != null && !sets.contentEquals(paramSets))
        if (restart) {
            // new stream, codec switch, or resolution change (iPhone rotated): a fresh
            // codec needs the parameter sets, so wait for a frame that carries them
            if (sets == null) return@synchronized
            stopCodec()
            if (!startCodec(isH265)) return@synchronized
            paramSets = sets
        }
        val c = codec ?: return@synchronized
        try {
            // first frame gets longer: some decoders are slow to hand out the first buffer
            repeat(if (queuedAny) 5 else 50) {
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx) ?: return@synchronized
                    buf.clear()
                    buf.put(data)
                    c.queueInputBuffer(idx, 0, data.size, ptsNs / 1000, 0)
                    queuedAny = true
                    return@synchronized
                }
            }
            dropped++
            Log.w(TAG, "decoder input full, dropped frame (total $dropped)")
        } catch (e: Exception) {
            Log.e(TAG, "decoder error, restarting at next keyframe", e)
            stopCodec()
        }
    }

    fun stop() = synchronized(lock) { stopCodec() }

    private fun startCodec(isH265: Boolean): Boolean {
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val c = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "no decoder for $mime", e)
            return false
        }
        val target = display ?: parking
        // low-latency first; some vendor decoders reject unknown keys, so fall back to plain
        val configured = listOf(true, false).any { lowLatency ->
            try {
                c.configure(videoFormat(mime, lowLatency), target, null, 0)
                true
            } catch (e: Exception) {
                Log.w(TAG, "configure(lowLatency=$lowLatency) failed", e)
                c.reset()
                false
            }
        }
        if (!configured) {
            c.release()
            return false
        }
        c.start()
        codec = c
        codecIsH265 = isH265
        codecName = c.name
        queuedAny = false
        Log.i(TAG, "decoder started: ${c.name} ($mime)")
        draining = true
        drainThread = Thread({ drain(c) }, "video-drain").apply { start() }
        return true
    }

    private fun videoFormat(mime: String, lowLatency: Boolean) =
        MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
            if (lowLatency) {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // realtime
            }
        }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                idx >= 0 -> {
                    // no display attached: decode (keeps references valid) but don't render
                    c.releaseOutputBuffer(idx, display != null)
                    countFrame()
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val w = cropped(f, "crop-left", "crop-right", MediaFormat.KEY_WIDTH)
                    val h = cropped(f, "crop-top", "crop-bottom", MediaFormat.KEY_HEIGHT)
                    width = w; height = h
                    Log.i(TAG, "video output ${w}x$h")
                    onVideoSize?.invoke(w, h)
                }
            }
        }
    }

    private fun cropped(f: MediaFormat, lo: String, hi: String, full: String): Int =
        if (f.containsKey(lo) && f.containsKey(hi)) f.getInteger(hi) - f.getInteger(lo) + 1
        else f.getInteger(full)

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = now
        framesInWindow++
        if (now - windowStart >= 1000) {
            fps = (framesInWindow * 1000 / (now - windowStart)).toInt()
            framesInWindow = 0
            windowStart = now
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        draining = false
        drainThread?.join(500)
        drainThread = null
        runCatching { c.stop() }
        runCatching { c.release() }
        codec = null
        paramSets = null
        fps = 0
    }

    companion object {
        private const val TAG = "SharePlus"

        // concatenated SPS (+VPS for H.265) NAL units in an Annex-B frame, or null if none
        fun parameterSets(data: ByteArray, isH265: Boolean): ByteArray? {
            var out: ByteArray? = null
            var i = 0
            val n = data.size
            while (i + 3 < n) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    val start = i + 3
                    if (start >= n) break
                    val type = if (isH265) (data[start].toInt() shr 1) and 0x3F
                               else data[start].toInt() and 0x1F
                    val isParamSet = if (isH265) type == 32 || type == 33 else type == 7
                    var end = start + 1
                    while (end + 2 < n && !(data[end] == 0.toByte() && data[end + 1] == 0.toByte() &&
                                (data[end + 2] == 1.toByte() ||
                                 (end + 3 < n && data[end + 2] == 0.toByte() && data[end + 3] == 1.toByte())))) {
                        end++
                    }
                    if (end + 2 >= n) end = n
                    if (isParamSet) out = (out ?: ByteArray(0)) + data.copyOfRange(start, end)
                    i = end
                } else {
                    i++
                }
            }
            return out
        }
    }
}
