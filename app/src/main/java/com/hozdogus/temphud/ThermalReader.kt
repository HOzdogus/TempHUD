package com.hozdogus.temphud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

data class Zone(val name: String, val celsius: Float)

/** Everything TempHUD measures at one moment. Null means "not available on this phone right now". */
data class Snapshot(
    // Temperatures
    val batteryC: Float?,
    val batteryPct: Int?,
    val cpuC: Float?,
    val gpuC: Float?,
    /** 0.0 = cool, 1.0 = the phone starts heavy throttling. */
    val headroom: Float?,
    val thermalStatus: Int,
    val zones: List<Zone>,
    // Power
    val charging: Boolean,
    /** Watts going out of (or, when charging, into) the battery. */
    val watts: Float?,
    /** Hours until empty (or until full when charging). */
    val hoursLeft: Float?,
    // Memory and CPU
    val ramUsedGb: Float?,
    val ramTotalGb: Float?,
    val cpuGhz: Float?,
    val cpuMaxGhz: Float?,
    // Network
    val onWifi: Boolean,
    val wifiDbm: Int?,
    val wifiMbps: Int?,
    val pingMs: Int?,
    val pingFailed: Boolean,
    // Needs Shizuku
    val fps: Float?,
    val cpuUsage: Int?,
    val gpuUsage: Int?,
    val gpuMhz: Int?,
)

/**
 * Reads every stat. Keeps a little history so it can work out rates (FPS, usage, ping).
 * [light] skips the network, clock and Shizuku-only stats, for cheap background logging.
 */
class Sampler(private val app: Context, private val light: Boolean = false) {

    private var lastFrames: Int? = null
    private var lastFramesAt = 0L
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private val currentHistory = ArrayDeque<Float>()
    private var tickCount = 0
    private var halCpu: Float? = null
    private var halGpu: Float? = null
    private var lastHeadroom: Float? = null

    @Volatile private var pingMs: Int? = null
    @Volatile private var pingFailed = false
    @Volatile private var pinging = false
    private val pingWorker = Executors.newSingleThreadExecutor()

    fun sample(): Snapshot {
        tickCount++
        val shizuku = ShizukuStats.ready()

        // ---- battery ----
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val volts = (battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).let { mv ->
            if (mv > 100) mv / 1000f else null // reported in millivolts
        }

        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentMa = normaliseCurrent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        if (currentMa != null) {
            currentHistory.addLast(currentMa)
            while (currentHistory.size > 20) currentHistory.removeFirst() // ~20 seconds of smoothing
        }
        val avgMa = if (currentHistory.isEmpty()) null else currentHistory.average().toFloat()
        val watts = if (currentMa != null && volts != null) currentMa * volts / 1000f else null
        val hoursLeft = if (charging) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { it / 3_600_000f }
        } else {
            val remainingMah = normaliseCharge(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            if (remainingMah != null && avgMa != null && avgMa > 30f) remainingMah / avgMa else null
        }

        // ---- temperatures ----
        val zones = readZones()
        var cpuC = zones.filter { isCpu(it.name) }.maxOfOrNull { it.celsius }
        var gpuC = zones.filter { isGpu(it.name) }.maxOfOrNull { it.celsius }
        if (shizuku && (cpuC == null || gpuC == null) && (light || tickCount % 3 == 1)) readHalTemps()
        if (cpuC == null) cpuC = halCpu
        if (gpuC == null) gpuC = halGpu

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val h = try { pm.getThermalHeadroom(10) } catch (e: Exception) { Float.NaN }
        if (!h.isNaN() && h >= 0f) lastHeadroom = h

        // ---- memory ----
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = 1024f * 1024f * 1024f
        val ramTotal = if (mi.totalMem > 0) mi.totalMem / gb else null
        val ramUsed = if (mi.totalMem > 0) (mi.totalMem - mi.availMem) / gb else null

        // ---- CPU clocks ----
        val (cpuGhz, cpuMaxGhz) = if (light) Pair(null, null) else readCpuClocks(shizuku)

        // ---- network ----
        val (onWifi, dbm, mbps) = if (light) WifiNow(false, null, null) else readWifi()
        if (!light && tickCount % 2 == 0) startPing()

        // ---- Shizuku-only stats ----
        val fps = if (shizuku && !light) readFps() else null
        val cpuUsage = if (shizuku && !light) readCpuUsage() else null
        val (gpuUsage, gpuMhz) = if (light) Pair(null, null) else readGpu(shizuku)

        return Snapshot(
            batteryC = batteryC, batteryPct = pct, cpuC = cpuC, gpuC = gpuC,
            headroom = lastHeadroom, thermalStatus = pm.currentThermalStatus, zones = zones,
            charging = charging, watts = watts?.let { abs(it) }, hoursLeft = hoursLeft,
            ramUsedGb = ramUsed, ramTotalGb = ramTotal, cpuGhz = cpuGhz, cpuMaxGhz = cpuMaxGhz,
            onWifi = onWifi, wifiDbm = dbm, wifiMbps = mbps, pingMs = pingMs, pingFailed = pingFailed,
            fps = fps, cpuUsage = cpuUsage, gpuUsage = gpuUsage, gpuMhz = gpuMhz,
        )
    }

    // Phones report current in microamps or milliamps; work out which.
    private fun normaliseCurrent(raw: Int): Float? {
        if (raw == 0 || raw == Int.MIN_VALUE) return null
        val a = abs(raw).toFloat()
        return if (a > 10_000f) a / 1000f else a
    }

    private fun normaliseCharge(raw: Int): Float? {
        if (raw <= 0 || raw == Int.MIN_VALUE) return null
        return if (raw > 100_000) raw / 1000f else raw.toFloat()
    }

    // ---------- temperatures ----------

    private fun readZones(): List<Zone> {
        val dirs = File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            try {
                val type = File(dir, "type").readText().trim()
                val raw = File(dir, "temp").readText().trim().toFloat()
                val c = toCelsius(raw)
                if (c < -30f || c > 150f) null else Zone(type, c)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }

    private fun toCelsius(raw: Float): Float = when {
        abs(raw) >= 1000f -> raw / 1000f
        abs(raw) >= 150f -> raw / 10f
        else -> raw
    }

    /** Android's own thermal service knows CPU and GPU temperatures; only readable with Shizuku. */
    private fun readHalTemps() {
        val out = ShizukuStats.run("dumpsys thermalservice") ?: return
        // Only the "Current temperatures" part, e.g. Temperature{mValue=41.2, mType=0, mName=cpu-1-0, ...}
        val section = out.substringAfter("Current temperatures from HAL", out)
        val re = Regex("""mValue=([-\d.]+),\s*mType=(-?\d+)""")
        var cpu: Float? = null
        var gpu: Float? = null
        for (m in re.findAll(section.substringBefore("Current cooling devices"))) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            when (m.groupValues[2]) {
                "0" -> cpu = maxOf(cpu ?: v, v)
                "1" -> gpu = maxOf(gpu ?: v, v)
            }
        }
        if (cpu != null) halCpu = cpu
        if (gpu != null) halGpu = gpu
    }

    private fun isCpu(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("cpu") || n.startsWith("big") || n.startsWith("little") || n.startsWith("mid")
    }

    private fun isGpu(name: String): Boolean = name.lowercase().contains("gpu")

    // ---------- CPU ----------

    /** Reads a system file directly, or through Shizuku if the phone blocks it. */
    private fun readText(path: String, shizuku: Boolean): String? {
        val direct = try { File(path).readText() } catch (e: Exception) { null }
        if (direct != null) return direct
        return if (shizuku) ShizukuStats.readFile(path) else null
    }

    private fun readCpuClocks(shizuku: Boolean): Pair<Float?, Float?> {
        var cur = 0L
        var max = 0L
        for (i in 0 until 16) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            if (!File("/sys/devices/system/cpu/cpu$i").exists()) break
            readText("$base/scaling_cur_freq", shizuku)?.trim()?.toLongOrNull()?.let { if (it > cur) cur = it }
            readText("$base/cpuinfo_max_freq", shizuku)?.trim()?.toLongOrNull()?.let { if (it > max) max = it }
        }
        // Values are in kHz.
        return (if (cur > 0) cur / 1_000_000f else null) to (if (max > 0) max / 1_000_000f else null)
    }

    private fun readCpuUsage(): Int? {
        val line = ShizukuStats.readFile("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val nums = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null
        val idle = nums[3] + nums[4]
        val total = nums.sum()
        val dTotal = total - lastCpuTotal
        val dIdle = idle - lastCpuIdle
        val first = lastCpuTotal == 0L
        lastCpuTotal = total
        lastCpuIdle = idle
        if (first || dTotal <= 0) return null
        return ((1f - dIdle.toFloat() / dTotal) * 100f).toInt().coerceIn(0, 100)
    }

    // ---------- GPU ----------

    private fun readGpu(shizuku: Boolean): Pair<Int?, Int?> {
        val usage = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Snapdragon (Adreno)
            "/sys/kernel/gpu/gpu_busy",                     // Samsung Exynos / Mali
        ).firstNotNullOfOrNull { p ->
            readText(p, shizuku)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        }
        val mhz = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk" to 1_000_000,       // Hz
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq" to 1_000_000,
            "/sys/kernel/gpu/gpu_clock" to 1,                     // MHz
        ).firstNotNullOfOrNull { (p, div) ->
            readText(p, shizuku)?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { (it / div).toInt() }
        }
        return usage?.coerceIn(0, 100) to mhz
    }

    // ---------- FPS ----------

    private fun readFps(): Float? {
        val frames = ShizukuStats.frameCount() ?: return null
        val now = SystemClock.elapsedRealtime()
        val prev = lastFrames
        val prevAt = lastFramesAt
        lastFrames = frames
        lastFramesAt = now
        if (prev == null || now <= prevAt) return null
        val diff = frames - prev
        if (diff < 0) return null
        return diff * 1000f / (now - prevAt)
    }

    // ---------- network ----------

    private data class WifiNow(val onWifi: Boolean, val dbm: Int?, val mbps: Int?)

    @Suppress("DEPRECATION")
    private fun readWifi(): WifiNow {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return WifiNow(false, null, null)
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WifiNow(false, null, null)
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= 31) {
            caps.transportInfo as? WifiInfo
        } else {
            (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        }
        val rssi = info?.rssi?.takeIf { it in -126..-1 }
        val speed = info?.linkSpeed?.takeIf { it > 0 }
        return WifiNow(true, rssi, speed)
    }

    /** Times a connection to the ping host in the background (no special permission needed). */
    private fun startPing() {
        if (pinging) return
        pinging = true
        val host = Prefs.get(app).getString(Prefs.PING_HOST, Prefs.DEFAULT_PING_HOST)?.trim()
            .orEmpty().ifEmpty { Prefs.DEFAULT_PING_HOST }
        pingWorker.execute {
            try {
                val (name, port) = if (host.contains(":")) {
                    host.substringBeforeLast(":") to (host.substringAfterLast(":").toIntOrNull() ?: 443)
                } else host to 443
                val address = InetSocketAddress(name, port) // looks up the name first, outside the timing
                val start = SystemClock.elapsedRealtimeNanos()
                Socket().use { it.connect(address, 1500) }
                pingMs = ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).toInt()
                pingFailed = false
            } catch (e: Exception) {
                pingMs = null
                pingFailed = true
            } finally {
                pinging = false
            }
        }
    }
}

/** Samples once a second on a background thread while anything is watching. */
object Stats {
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var sampler: Sampler? = null

    @Volatile
    var latest: Snapshot? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            val s = try { sampler?.sample() } catch (e: Exception) { null }
            if (s != null) {
                latest = s
                main.post { listeners.forEach { it(s) } }
            }
            worker?.postDelayed(this, 1000L)
        }
    }

    @Synchronized
    fun subscribe(context: Context, listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        latest?.let { s -> main.post { listener(s) } }
        if (thread == null) {
            ShizukuStats.start()
            sampler = Sampler(context.applicationContext)
            val t = HandlerThread("temphud-stats").also { it.start() }
            thread = t
            worker = Handler(t.looper).also { it.post(tick) }
        }
    }

    @Synchronized
    fun unsubscribe(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            worker?.removeCallbacks(tick)
            thread?.quitSafely()
            thread = null
            worker = null
        }
    }
}

object ThermalLabels {
    fun status(status: Int): String = when (status) {
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
