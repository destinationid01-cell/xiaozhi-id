package me.xiaozhi.androidclient

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import me.xiaozhi.androidclient.ui.theme.HoloColors
import me.xiaozhi.androidclient.ui.theme.OrbStatus

/**
 * Orb hologram mengambang di atas aplikasi lain (overlay).
 *
 * Butuh izin SYSTEM_ALERT_WINDOW ("Tampil di atas aplikasi lain").
 * Orb bisa digeser; ketuk untuk membuka aplikasi.
 */
class HoloOrbService : Service() {

    companion object {
        const val ACTION_START = "me.xiaozhi.androidclient.action.START_ORB"
        const val ACTION_STOP = "me.xiaozhi.androidclient.action.STOP_ORB"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                // Izin belum ada — buka pengaturan overlay
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, HoloOrbService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HoloOrbService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var orbView: View? = null
    private var orbStatus by mutableStateOf(OrbStatus.STANDBY)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showOrb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOrb()
        super.onDestroy()
    }

    private fun showOrb() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        HoloColors.orbColorFor(orbStatus).copy(alpha = 0.95f),
                                        HoloColors.orbColorFor(orbStatus).copy(alpha = 0.4f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }

        // Geser orb + ketuk untuk buka aplikasi
        var lastX = 0f
        var lastY = 0f
        var downX = 0f
        var downY = 0f
        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x -= (event.rawX - lastX).toInt()
                    params.y += (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    runCatching { wm.updateViewLayout(composeView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY)
                    if (moved < 10) {
                        // Ketuk → buka aplikasi
                        val openIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(openIntent)
                    }
                    true
                }
                else -> false
            }
        }

        orbView = composeView
        runCatching { wm.addView(composeView, params) }
    }

    private fun removeOrb() {
        orbView?.let { runCatching { windowManager?.removeView(it) } }
        orbView = null
        windowManager = null
    }
}
