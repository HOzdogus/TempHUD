package com.hozdogus.temphud

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process

/**
 * Runs in the background, and every 15 seconds while the screen is on writes down
 * which app is open and how hot the phone is. The Hot apps screen reads this log.
 */
class HeatLogService : Service() {

    companion object {
        const val ACTION_STOP = "com.hozdogus.temphud.STOP_HEAT_LOG"
        private const val CHANNEL_ID = "heat_log"
        private const val NOTIF_ID = 2
        private const val PRUNE_EVERY_MS = 6 * 60 * 60 * 1000L

        @Volatile
        var running = false
            private set

        /** True once "Usage access" is on for TempHUD, which is how it sees the open app. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun isEnabled(context: Context): Boolean =
            Prefs.get(context).getBoolean(Prefs.HEAT_LOG_ON, true)

        /** Starts logging if it's switched on and allowed. Safe to call any time. */
        fun startIfEnabled(context: Context) {
            if (running || !isEnabled(context) || !hasUsageAccess(context)) return
            try {
                context.startForegroundService(Intent(context, HeatLogService::class.java))
            } catch (_: Exception) {
                // Android sometimes refuses background starts; the next app open will retry.
            }
        }
    }

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var sampler: Sampler? = null
    private var foregroundPkg: String? = null
    private var eventsCheckedUntil = 0L
    private var lastPrune = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                logOnce()
            } catch (_: Exception) {
                // Never let one bad reading stop the logger.
            }
            worker?.postDelayed(this, HeatLog.INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("Starting…")
        running = true
        ShizukuStats.start()
        sampler = Sampler(applicationContext, light = true)
        val t = HandlerThread("temphud-heatlog").also { it.start() }
        thread = t
        worker = Handler(t.looper).also { it.post(tick) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.get(this).edit().putBoolean(Prefs.HEAT_LOG_ON, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        worker = null
        super.onDestroy()
    }

    private fun logOnce() {
        val now = System.currentTimeMillis()
        if (now - lastPrune > PRUNE_EVERY_MS) {
            HeatLog.prune(this)
            lastPrune = now
        }

        // Nothing is "open" with the screen off, so don't blame any app for that time.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return

        val pkg = updateForegroundApp(now) ?: return
        val snap = sampler?.sample() ?: return
        if (snap.batteryC == null && snap.cpuC == null) return

        HeatLog.append(this, HeatSample(now, pkg, snap.batteryC, snap.cpuC, snap.charging))
        updateNotification(pkg, snap)
    }

    /** Replays app open events since last time to work out which app is on screen now. */
    private fun updateForegroundApp(now: Long): String? {
        val usm = getSystemService(UsageStatsManager::class.java)
        // First run: look back an hour to find whatever is already open.
        val from = if (eventsCheckedUntil == 0L) now - 60 * 60 * 1000L else eventsCheckedUntil - 2000
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundPkg = e.packageName
        }
        eventsCheckedUntil = now
        return foregroundPkg
    }

    private fun updateNotification(pkg: String, snap: Snapshot) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        val temp = snap.cpuC?.let { "CPU ${Format.deg(it)}" } ?: "Battery ${Format.deg(snap.batteryC)}"
        startAsForeground("$label · $temp")
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hot apps logging", NotificationManager.IMPORTANCE_MIN)
        )
        val openHotApps = PendingIntent.getActivity(
            this, 2, Intent(this, HotAppsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, HeatLogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Logging temperatures for Hot apps")
            .setContentText(text)
            .setContentIntent(openHotApps)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop logging", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}

/** Restarts logging after the phone reboots or TempHUD is updated. */
class HeatLogBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HeatLogService.startIfEnabled(context)
    }
}
