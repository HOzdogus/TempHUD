package com.hozdogus.temphud

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Connects to Shizuku for the stats Android hides from normal apps. All optional. */
object ShizukuStats {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

    @Volatile
    private var service: IStatsService? = null
    @Volatile
    private var binding = false
    private var watching = false

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun hasPermission(): Boolean = isRunning() &&
        try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (t: Throwable) { false }

    fun state(context: Context): State = when {
        service != null && isRunning() -> State.READY
        !isRunning() -> if (isInstalled(context)) State.NOT_RUNNING else State.NOT_INSTALLED
        !hasPermission() -> State.NO_PERMISSION
        else -> State.CONNECTING
    }

    fun ready(): Boolean = service != null

    fun requestPermission() {
        try { Shizuku.requestPermission(7) } catch (t: Throwable) { }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.hozdogus.temphud", StatsService::class.java.name))
            .daemon(false)
            .processNameSuffix("stats")
            .debuggable(false)
            .version(1) // bump whenever StatsService changes
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) IStatsService.Stub.asInterface(binder) else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
        }
    }

    /** Starts watching Shizuku and connects whenever it's running and allowed. Safe to call often. */
    fun start() {
        if (!watching) {
            watching = true
            try {
                Shizuku.addBinderReceivedListenerSticky { connect() }
                Shizuku.addBinderDeadListener {
                    service = null
                    binding = false
                }
                Shizuku.addRequestPermissionResultListener { _, _ -> connect() }
            } catch (t: Throwable) {
            }
        }
        connect()
    }

    fun connect() {
        if (service != null || binding || !hasPermission()) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
        }
    }

    // These are called from the background sampler thread.

    fun frameCount(): Int? = try {
        service?.frameCount()?.takeIf { it >= 0 }
    } catch (t: Throwable) {
        null
    }

    fun readFile(path: String): String? = try { service?.readFile(path) } catch (t: Throwable) { null }

    fun run(command: String): String? = try { service?.run(command) } catch (t: Throwable) { null }
}
