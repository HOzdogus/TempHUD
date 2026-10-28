package com.hozdogus.temphud

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a separate process started by Shizuku, with ADB ("shell") rights,
 * which can read frame counts and system files that normal apps can't.
 */
class StatsService : IStatsService.Stub() {

    private val flinger: IBinder? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (t: Throwable) { }
        }
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "SurfaceFlinger") as? IBinder
    } catch (t: Throwable) {
        null
    }

    override fun destroy() {
        exitProcess(0)
    }

    /** SurfaceFlinger's page-flip counter (the same thing `service call SurfaceFlinger 1013` reads). */
    override fun frameCount(): Int {
        val b = flinger ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.ui.ISurfaceComposer")
            if (!b.transact(1013, data, reply, 0)) -1 else reply.readInt()
        } catch (t: Throwable) {
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun readFile(path: String?): String? = try {
        if (path == null) null else File(path).readText()
    } catch (t: Throwable) {
        null
    }

    override fun run(command: String?): String? {
        if (command == null) return null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(3, TimeUnit.SECONDS)
            out
        } catch (t: Throwable) {
            null
        }
    }
}
