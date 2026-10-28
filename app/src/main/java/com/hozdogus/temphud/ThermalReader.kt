package com.hozdogus.temphud

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File
import kotlin.math.abs

data class Zone(val name: String, val celsius: Float)

data class Snapshot(
    val batteryC: Float?,
    val batteryPct: Int?,
    val cpuC: Float?,
    val gpuC: Float?,
    /** 0.0 = cool, 1.0 = the phone starts heavy throttling. Null if unknown. */
    val headroom: Float?,
    val thermalStatus: Int,
    val zones: List<Zone>,
)

object ThermalReader {

    // Android rate-limits getThermalHeadroom() and returns NaN if it's called
    // too often, so keep the last good value to fall back on.
    @Volatile
    private var lastHeadroom: Float? = null

    fun read(context: Context): Snapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null

        val zones = readZones()
        val cpu = zones.filter { isCpu(it.name) }.maxOfOrNull { it.celsius }
        val gpu = zones.filter { isGpu(it.name) }.maxOfOrNull { it.celsius }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val h = try {
            pm.getThermalHeadroom(10)
        } catch (e: Exception) {
            Float.NaN
        }
        if (!h.isNaN() && h >= 0f) lastHeadroom = h

        return Snapshot(batteryC, pct, cpu, gpu, lastHeadroom, pm.currentThermalStatus, zones)
    }

    /** Reads every thermal sensor the system lets us see. Blocked ones are skipped. */
    private fun readZones(): List<Zone> {
        val dirs = File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?: return emptyList()

        return dirs.mapNotNull { dir ->
            try {
                val type = File(dir, "type").readText().trim()
                val raw = File(dir, "temp").readText().trim().toFloat()
                // Sensors report in millidegrees, tenths of a degree or whole degrees.
                val c = when {
                    abs(raw) >= 1000f -> raw / 1000f
                    abs(raw) >= 150f -> raw / 10f
                    else -> raw
                }
                if (c < -30f || c > 150f) null else Zone(type, c)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }

    private fun isCpu(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("cpu") || n.startsWith("big") || n.startsWith("little") || n.startsWith("mid")
    }

    private fun isGpu(name: String): Boolean = name.lowercase().contains("gpu")

    fun statusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown"
    }
}
