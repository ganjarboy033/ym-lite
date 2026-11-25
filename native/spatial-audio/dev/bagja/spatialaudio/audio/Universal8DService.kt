package dev.bagja.spatialaudio.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.bagja.spatialaudio.data.MixerRepository
import dev.bagja.spatialaudio.data.UniversalAudioMode
import dev.bagja.spatialaudio.dsp.MixerEngine
import dev.bagja.spatialaudio.dsp.PathLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mode A: Universal 8D & DSP Capture Service.
 * Menangkap audio internal aplikasi eksternal (YouTube, TikTok, YMusic, Spotify, Game)
 * menggunakan MediaProjection + AudioPlaybackCaptureConfiguration (Android 10+)
 * lalu mengalirkannya secara real-time melalui MixerEngine Laguku.
 * Karya DEV BAGJA / KruzenLab (bagja.dev).
 */
class Universal8DService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null

    @Volatile private var currentPath: FloatArray = PathLib.builtinById("lingkaran")!!.build()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureAndSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MixerRepository.init(this)
        createChannel()
        scope.launch {
            MixerRepository.settings
                .map { listOf(it.pathId, it.customRaw, it.smoothLevel, it.lissajousFreqX, it.lissajousFreqZ, it.lissajousPhase) }
                .distinctUntilChanged()
                .collect {
                    val s = MixerRepository.settings.value
                    currentPath = PathLib.resolve(s.pathId, s.customRaw, s.smoothLevel, s.lissajousFreqX, s.lissajousFreqZ, s.lissajousPhase)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureAndSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ mewajibkan startForeground dengan tipe MEDIA_PROJECTION sebelum getMediaProjection()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        stopCaptureOnly()

        // Otomatis jeda pemutar internal ExoPlayer agar suara tidak bertabrakan / feedback loop
        runCatching { SpatialHostBridge.onPausePlayback?.invoke() }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: run {
            stopCaptureAndSelf()
            return
        }
        proj.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = proj

        val sampleRate = 48000
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()) // Wajib kecualikan UID Laguku agar tidak terjadi feedback loop
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        }.getOrElse { err ->
            Log.e(TAG, "Gagal membuat AudioRecord playback capture", err)
            stopCaptureAndSelf()
            return
        }

        val outFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(outFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track
        MixerRepository.universalCaptureActive.value = true
        MixerRepository.universalMode.value = UniversalAudioMode.CAPTURE_8D

        captureJob = scope.launch {
            val engine = MixerEngine(sampleRate, applicationContext) { frame ->
                MixerRepository.frame.value = frame
            }
            val block = 128
            val readShorts = ShortArray(block * 2)
            val outBytes = ByteArray(block * 4)
            val bbOut = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            val inL = FloatArray(block)
            val inR = FloatArray(block)
            val outL = FloatArray(block)
            val outR = FloatArray(block)

            try {
                record.startRecording()
                track.play()
                while (MixerRepository.universalCaptureActive.value && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(readShorts, 0, readShorts.size)
                    if (read <= 1) continue
                    val frames = read / 2
                    for (i in 0 until frames) {
                        inL[i] = readShorts[i * 2] / 32768f
                        inR[i] = readShorts[i * 2 + 1] / 32768f
                    }
                    val s = MixerRepository.settings.value
                    bbOut.clear()
                    if (s.enabled) {
                        engine.process(inL, inR, outL, outR, frames, s, currentPath, MixerRepository.override)
                        for (i in 0 until frames) {
                            bbOut.putShort((outL[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            bbOut.putShort((outR[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                        }
                    } else {
                        for (i in 0 until frames) {
                            bbOut.putShort(readShorts[i * 2])
                            bbOut.putShort(readShorts[i * 2 + 1])
                        }
                    }
                    track.write(outBytes, 0, frames * 4)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error", e)
            } finally {
                stopCaptureAndSelf()
            }
        }
    }

    private fun stopCaptureOnly() {
        MixerRepository.universalCaptureActive.value = false
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        audioRecord = null
        audioTrack = null
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        projection = null
    }

    private fun stopCaptureAndSelf() {
        stopCaptureOnly()
        if (MixerRepository.universalMode.value == UniversalAudioMode.CAPTURE_8D) {
            MixerRepository.universalMode.value = UniversalAudioMode.OFF
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCaptureOnly()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Laguku Universal 8D", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val openIntent = PendingIntent.getActivity(
            this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, Universal8DService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Laguku Universal 8D Aktif")
            .setContentText("Memproses audio YouTube, TikTok, Game & aplikasi lain secara real-time.")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Universal8DService"
        private const val CHANNEL_ID = "laguku_universal_8d"
        private const val NOTIF_ID = 8081
        const val ACTION_START = "dev.bagja.spatialaudio.ACTION_START_UNIVERSAL_8D"
        const val ACTION_STOP = "dev.bagja.spatialaudio.ACTION_STOP_UNIVERSAL_8D"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, Universal8DService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, Universal8DService::class.java).setAction(ACTION_STOP))
        }
    }
}
