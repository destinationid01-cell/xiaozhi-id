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
import me.xiaozhi.androidclient.audio.SmartWakeListener

/**
 * Foreground service "Siaga Selalu".
 *
 * Tugasnya dua:
 * 1. Menjaga proses aplikasi tetap hidup (sistem tidak membunuh saat layar
 *    mati atau aplikasi di-background).
 * 2. Menjalankan [SmartWakeListener] yang mendeteksi suara tanpa layanan
 *    speech Google (tanpa beep).
 *
 * Deteksi kata kunci yang sebenarnya dilakukan di MainViewModel via
 * transkrip server; service ini hanya meneruskan event "ada suara".
 */
class XiaozhiVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "xiaozhi_voice_standby"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "me.xiaozhi.androidclient.action.START_STANDBY"
        const val ACTION_STOP = "me.xiaozhi.androidclient.action.STOP_STANDBY"
        const val ACTION_SPEECH = "me.xiaozhi.androidclient.action.SPEECH_DETECTED"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeListener: SmartWakeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithType()
        wakeListener = SmartWakeListener(this) {
            // Teruskan sebagai broadcast agar MainViewModel yang hidup bisa menangani.
            // Jika activity mati, broadcast ini diabaikan (tidak crash).
            sendBroadcast(Intent(ACTION_SPEECH).setPackage(packageName))
        }.also { it.start() }
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
        scope.launch {
            wakeListener?.stop()
            wakeListener = null
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
