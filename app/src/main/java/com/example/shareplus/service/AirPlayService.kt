package com.example.shareplus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.shareplus.AirPlayState
import com.example.shareplus.AirPlayState.Phase
import com.example.shareplus.MainActivity
import com.example.shareplus.R
import com.example.shareplus.bridge.NativeBridge
import com.example.shareplus.bridge.RaopCallbackHandler
import com.example.shareplus.video.VideoRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Runs the UxPlay AirPlay server as a foreground service, so it keeps receiving while
 * other apps (YouTube, Netflix) are on screen, and brings MainActivity to the front
 * when an iPhone connects.
 */
class AirPlayService : Service(), RaopCallbackHandler {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var handle = 0L
    private var nsd: NsdPublisher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connections = 0
    private var serverIp: String? = null
    private val backToIdle = Runnable { if (AirPlayState.phase.value == Phase.PAUSED) AirPlayState.phase.value = Phase.IDLE }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            main.post {
                // Wi-Fi reconnected with a new address: the old mDNS records point nowhere
                if (ip != null && ip != serverIp && handle != 0L) {
                    Log.i(TAG, "network changed ($serverIp -> $ip), restarting server")
                    restartServer()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        goForeground()
        video.onVideoSize = { w, h -> AirPlayState.videoSize.value = w to h }
        startServer()
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        }
        stopServer()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SharePlus is ready")
            .setContentText("Mirror your iPhone to this TV")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startServer() {
        if (handle != 0L) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shareplus:server").apply { acquire() }

        NativeBridge.nativeSetDefaultStreamValues(
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0)

        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        // nohold: a new connection takes over a stale one, so reconnecting after the
        // iPhone locked never gets refused
        handle = NativeBridge.nativeInit(this, deviceId(), SERVER_NAME, keyFile, true, false)
        if (handle == 0L) {
            Log.e(TAG, "native init failed")
            AirPlayState.phase.value = Phase.STOPPED
            return
        }
        // adaptive jitter cushion, default percentile, auto buffer, realtime + low latency
        NativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, false, true, true, false)
        NativeBridge.nativeSetH265Enabled(handle, false)
        // no ffmpeg in this build (NOTES.md 2.2): ask senders for AAC, never ALAC
        NativeBridge.nativeSetCodecs(handle, false, true)
        NativeBridge.nativeSetHlsEnabled(handle, false)
        NativeBridge.nativeSetAudioEnabled(handle, true)
        NativeBridge.nativeSetLang(handle, "", "", resources.configuration.locales.toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetPlist(handle, "maxFPS", 60)
        NativeBridge.nativeSetPlist(handle, "overscanned", 0)
        NativeBridge.nativeSetDisplaySize(handle, 1920, 1080, 60)

        val port = NativeBridge.nativeStart(handle, 7000)
        if (port < 0) {
            Log.e(TAG, "server failed to start")
            NativeBridge.nativeDestroy(handle)
            handle = 0L
            AirPlayState.phase.value = Phase.STOPPED
            return
        }

        val name = NativeBridge.nativeGetServerName(handle) ?: SERVER_NAME
        nsd = NsdPublisher(this).apply {
            register("_raop._tcp", NativeBridge.nativeGetRaopServiceName(handle) ?: "SharePlus",
                port, NativeBridge.nativeGetRaopTxtRecords(handle) ?: emptyMap())
            register("_airplay._tcp", name, port, NativeBridge.nativeGetAirplayTxtRecords(handle) ?: emptyMap())
        }
        serverIp = localIp()
        AirPlayState.serverName.value = name
        AirPlayState.ipAddress.value = serverIp
        AirPlayState.phase.value = Phase.IDLE
        Log.i(TAG, "AirPlay server '$name' running on $serverIp:$port")
    }

    private fun stopServer() {
        nsd?.release()
        nsd = null
        video.stop()
        if (handle != 0L) {
            NativeBridge.nativeStop(handle)
            NativeBridge.nativeDestroy(handle)
            handle = 0L
        }
        connections = 0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AirPlayState.phase.value = Phase.STOPPED
        Log.i(TAG, "AirPlay server stopped")
    }

    private fun restartServer() {
        stopServer()
        startServer()
    }

    // AirPlay needs a stable MAC-like device id; Android 11 hides the real MAC, so
    // generate a locally-administered one once and keep it (iPhone remembers the TV by it)
    private fun deviceId(): ByteArray {
        val prefs = getSharedPreferences("shareplus", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { hex ->
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val id = Random.nextBytes(6)
        id[0] = ((id[0].toInt() and 0xFC) or 0x02).toByte()  // unicast, locally administered
        prefs.edit().putString("device_id", id.joinToString("") { "%02x".format(it) }).apply()
        return id
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun setPhase(p: Phase) {
        main.removeCallbacks(backToIdle)
        AirPlayState.phase.value = p
        if (p == Phase.PAUSED) main.postDelayed(backToIdle, PAUSED_MS)
    }

    private fun bringToFront() {
        if (AirPlayState.activityVisible.value) return
        // allowed from a foreground service (background-activity-start exemption)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---- RaopCallbackHandler: all of these run on native RAOP threads ----

    override fun onConnectionInit() {
        main.post {
            connections++
            Log.i(TAG, "client connected ($connections)")
            val p = AirPlayState.phase.value
            if (p == Phase.IDLE || p == Phase.PAUSED) setPhase(Phase.CONNECTING)
            bringToFront()
        }
    }

    override fun onConnectionDestroy() {
        main.post {
            connections = (connections - 1).coerceAtLeast(0)
            Log.i(TAG, "client disconnected ($connections left)")
            if (connections == 0) {
                if (handle != 0L) NativeBridge.nativeServerAudioStop(handle)
                video.stop()
                val p = AirPlayState.phase.value
                if (p != Phase.IDLE && p != Phase.STOPPED) setPhase(Phase.PAUSED)
            }
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.w(TAG, "connection reset: $reason")
    }

    override fun onMirrorRunning(running: Boolean) {
        Log.i(TAG, "mirroring ${if (running) "started" else "stopped"}")
        if (!running) video.stop()
        main.post { setPhase(if (running) Phase.MIRRORING else Phase.PAUSED) }
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        video.feed(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        Log.i(TAG, "video size ${srcW}x$srcH -> ${w}x$h")
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Log.i(TAG, "audio format ct=$ct spf=$spf screen=$usingScreen")
        if (handle == 0L) return
        NativeBridge.nativeServerAudioStart(handle)
        NativeBridge.nativeServerAudioFormat(handle, ct, spf)
        if (!usingScreen) main.post {
            if (AirPlayState.phase.value != Phase.MIRRORING) setPhase(Phase.AUDIO_ONLY)
        }
    }

    // sender volume in dB: -30..0, -144 = mute. Mapped onto the TV's media volume so the
    // iPhone volume buttons work
    override fun onVolumeChange(volume: Float) {
        val frac = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        main.post {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (frac * max).roundToInt(), 0)
        }
    }

    override fun onClientVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (vol == 0) -144f else -30f + 30f * vol / max
    }

    override fun onAudioTeardown() {
        Log.i(TAG, "audio teardown")
    }

    override fun onLog(msg: String) {}
    override fun onDisplayPin(pin: String) {}
    override fun onMetadata(data: ByteArray) {}
    override fun onCoverArt(data: ByteArray) {}
    override fun onProgress(start: Long, curr: Long, end: Long) {}
    override fun onDacpId(dacpId: String, activeRemote: String) {}
    // HLS (YouTube AirPlay) is PLAN 9.4 and not advertised yet
    override fun onVideoPlay(location: String, startPositionSeconds: Float) {}
    override fun onVideoScrub(positionSeconds: Float) {}
    override fun onVideoRate(rate: Float) {}
    override fun onVideoStop() {}
    override fun onVideoSessionPoll() {}

    companion object {
        private const val TAG = "SharePlus"
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val SERVER_NAME = "SharePlus"
        private const val PAUSED_MS = 10_000L

        // shared with MainActivity, which attaches its SurfaceView to it
        val video = VideoRenderer()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AirPlayService::class.java))
        }
    }
}
