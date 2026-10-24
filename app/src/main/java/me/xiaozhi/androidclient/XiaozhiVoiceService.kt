package me.xiaozhi.androidclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.audio.WakeWordRecognizer
import me.xiaozhi.androidclient.data.AppPreferences

/**
 * Foreground service "Siaga Selalu".
 *
 * Tugasnya dua:
 * 1. Menjaga proses aplikasi tetap hidup (sistem tidak membunuh saat layar
 *    mati atau aplikasi di-background).
 * 2. Menjalankan [WakeWordRecognizer] yang mendeteksi kata kunci secara
 *    lokal (tanpa beep — stream audio di-mute selama mendengarkan).
 *
 * Deteksi dilakukan LOKAL sebelum audio dikirim ke server, sehingga tidak
 * ada turn palsu yang terlanjur diproses server.
 */
class XiaozhiVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "xiaozhi_voice_standby"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "me.xiaozhi.androidclient.action.START_STANDBY"
        const val ACTION_STOP = "me.xiaozhi.androidclient.action.STOP_STANDBY"
        const val ACTION_WAKE_WORD = "me.xiaozhi.androidclient.action.WAKE_WORD_DETECTED"
        const val ACTION_PAUSE = "me.xiaozhi.androidclient.action.PAUSE_LISTENING"
        const val ACTION_RESUME = "me.xiaozhi.androidclient.action.RESUME_LISTENING"
        const val EXTRA_PHRASE = "phrase"

        fun start(context: Context) {
            val intent = Intent(context, XiaozhiVoiceService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XiaozhiVoiceService::class.java))
        }

        fun pauseListening(context: Context) {
            context.sendBroadcast(Intent(ACTION_PAUSE).setPackage(context.packageName))
        }

        fun resumeListening(context: Context) {
            context.sendBroadcast(Intent(ACTION_RESUME).setPackage(context.packageName))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeRecognizer: WakeWordRecognizer? = null
    private var resumeJob: kotlinx.coroutines.Job? = null

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE -> pauseRecognizer()
                ACTION_RESUME -> resumeRecognizer()
            }
        }
    }

    /** Hentikan pendengar (dan unmute) selama sesi percakapan berlangsung. */
    private fun pauseRecognizer() {
        resumeJob?.cancel()
        resumeJob = null
        wakeRecognizer?.stop()
        updateNotification("Dalam sesi percakapan…")
    }

    /** Mulai lagi pendengar kata kunci (setelah sesi selesai). */
    private fun resumeRecognizer() {
        resumeJob?.cancel()
        resumeJob = null
        wakeRecognizer?.start(AppPreferences(this).load().wakeWords)
    }

    /** Fallback: kembali mendengarkan setelah 180 detik bila tidak ada sinyal resume. */
    private fun scheduleFallbackResume() {
        resumeJob?.cancel()
        resumeJob = scope.launch {
            kotlinx.coroutines.delay(180_000)
            resumeRecognizer()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithType()
        registerReceiver(
            controlReceiver,
            android.content.IntentFilter().apply {
                addAction(ACTION_PAUSE)
                addAction(ACTION_RESUME)
            },
            android.content.Context.RECEIVER_NOT_EXPORTED,
        )
        val wakeWords = AppPreferences(this).load().wakeWords
        wakeRecognizer = WakeWordRecognizer(
            context = this,
            onWakeWordDetected = { phrase ->
                // Teruskan sebagai broadcast agar MainViewModel yang hidup bisa menangani.
                // Jika activity mati, broadcast ini diabaikan (tidak crash).
                sendBroadcast(
                    Intent(ACTION_WAKE_WORD)
                        .setPackage(packageName)
                        .putExtra(EXTRA_PHRASE, phrase)
                )
                updateNotification("Terdeteksi: $phrase — memulai percakapan…")
                // JANGAN restart otomatis di sini: mute akan membungkam TTS.
                // Pendengar kembali hanya lewat RESUME (dari activity) atau fallback 180 detik.
                pauseRecognizer()
                scheduleFallbackResume()
            },
            onStatusChanged = { status -> updateNotification(status) },
            onError = { error -> updateNotification("Error: $error") },
        ).also { it.start(wakeWords) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: jika sistem membunuh service, hidupkan lagi otomatis.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(controlReceiver) }
        resumeJob?.cancel()
        scope.launch {
            wakeRecognizer?.release()
            wakeRecognizer = null
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Siaga Suara XiaoZhi",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "XiaoZhi siaga mendengarkan kata kunci"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, XiaozhiVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XiaoZhi siaga")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithType() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, XiaozhiVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XiaoZhi siaga")
            .setContentText("Ketuk untuk buka • sebut kata kunci untuk bicara")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
