package com.hozdogus.temphud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.hozdogus.temphud.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
        private const val REFRESH_MS = 2000L

        @Volatile
        var running = false
            private set

        /** When the overlay was switched on (elapsedRealtime), for the session timer. */
        @Volatile
        var startedAt: Long? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var overlay: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val onStats: (Snapshot) -> Unit = { refresh(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        running = true
        startedAt = android.os.SystemClock.elapsedRealtime()
        showOverlay()
        Stats.subscribe(this, onStats)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        startedAt = null
        Stats.unsubscribe(onStats)
        overlay?.let { v -> windowManager?.removeView(v) }
        overlay = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TempHUD overlay is on")
            .setContentText("Tap to change settings")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hide overlay", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val prefs = Prefs.get(this)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.POS_X, 40)
            y = prefs.getInt(Prefs.POS_Y, 120)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val pad = dp(8)
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
            text = "TempHUD"
        }
        view.setOnTouchListener(DragListener())
        overlay = view
        applyStyle()
        wm.addView(view, params)
    }

    /** Re-reads appearance settings so changes in the app apply straight away. */
    private fun applyStyle() {
        val v = overlay ?: return
        val prefs = Prefs.get(this)
        val opacity = prefs.getInt(Prefs.OPACITY, 70).coerceIn(20, 100)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt(Prefs.TEXT_SIZE, 12).toFloat())
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(opacity * 255 / 100, 10, 14, 20))
        }
    }

    private fun refresh(snapshot: Snapshot) {
        val v = overlay ?: return
        applyStyle()
        v.text = Format.overlayText(snapshot, Prefs.get(this), ShizukuStats.ready(), startedAt)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Lets you drag the overlay anywhere; remembers where you leave it. */
    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.get(this@OverlayService).edit()
                        .putInt(Prefs.POS_X, params.x)
                        .putInt(Prefs.POS_Y, params.y)
                        .apply()
                    v.performClick()
                }
            }
            return true
        }
    }
}
