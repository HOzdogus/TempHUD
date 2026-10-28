package com.hozdogus.temphud

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    const val SHOW_BATTERY = "show_battery"
    const val SHOW_CPU = "show_cpu"
    const val SHOW_GPU = "show_gpu"
    const val SHOW_THERMAL = "show_thermal"
    const val OPACITY = "opacity"      // 20..100 (%)
    const val TEXT_SIZE = "text_size"  // sp
    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"
    const val HORIZONTAL = "horizontal"
    const val PING_HOST = "ping_host"
    const val DEFAULT_PING_HOST = "1.1.1.1"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("temphud", Context.MODE_PRIVATE)
}

/** One stat you can show on the overlay. */
data class StatDef(val key: String, val label: String, val onByDefault: Boolean, val needsShizuku: Boolean = false)

object StatList {
    val all = listOf(
        StatDef("show_fps", "FPS", true, needsShizuku = true),
        StatDef(Prefs.SHOW_BATTERY, "Battery temperature", true),
        StatDef(Prefs.SHOW_CPU, "CPU temperature", true),
        StatDef(Prefs.SHOW_GPU, "GPU temperature", true),
        StatDef(Prefs.SHOW_THERMAL, "Distance to throttling", true),
        StatDef("show_cpu_usage", "CPU usage", false, needsShizuku = true),
        StatDef("show_gpu_usage", "GPU usage", false, needsShizuku = true),
        StatDef("show_cpu_clock", "CPU clock speed", false),
        StatDef("show_gpu_clock", "GPU clock speed", false, needsShizuku = true),
        StatDef("show_watts", "Battery power (watts)", true),
        StatDef("show_time_left", "Battery time left", false),
        StatDef("show_ram", "RAM in use", false),
        StatDef("show_wifi", "Wi-Fi signal", false),
        StatDef("show_ping", "Ping", true),
        StatDef("show_session", "Session timer", false),
        StatDef("show_clock", "Clock", false),
    )

    fun isOn(p: SharedPreferences, key: String): Boolean =
        p.getBoolean(key, all.firstOrNull { it.key == key }?.onByDefault ?: false)
}

/** One line of output: a short label, the value, and a colour for the value. */
data class StatLine(val key: String, val label: String, val value: String, val colour: Int)

object Format {
    val GREEN = Color.parseColor("#7CE38B")
    val AMBER = Color.parseColor("#FFC24B")
    val RED = Color.parseColor("#FF6B5B")
    val GREY = Color.parseColor("#AAAAAA")
    val WHITE = Color.WHITE

    fun deg(c: Float?): String = if (c == null) "n/a" else String.format(Locale.UK, "%.1f°C", c)

    fun colourFor(c: Float?, warn: Float, hot: Float): Int = when {
        c == null -> GREY
        c >= hot -> RED
        c >= warn -> AMBER
        else -> GREEN
    }

    /** Lower is worse (FPS, Wi-Fi signal). */
    private fun colourLow(v: Float?, ok: Float, bad: Float): Int = when {
        v == null -> GREY
        v >= ok -> GREEN
        v >= bad -> AMBER
        else -> RED
    }

    // Android scales headroom so 0.0 is ~30°C below the throttling point and
    // 1.0 is the throttling point, so convert it to degrees left before throttling.
    fun degreesToThrottle(h: Float?): Float? = h?.let { ((1f - it) * 30f).coerceAtLeast(0f) }

    fun headroomColour(h: Float?): Int {
        val d = degreesToThrottle(h) ?: return GREY
        return when {
            d <= 2f -> RED
            d <= 5f -> AMBER
            else -> GREEN
        }
    }

    fun headroomText(h: Float?): String {
        val d = degreesToThrottle(h) ?: return "n/a"
        return if (d <= 0f) "throttling" else String.format(Locale.UK, "%.1f°C away", d)
    }

    fun duration(hours: Float): String {
        val mins = (hours * 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }

    private fun one(v: Float) = String.format(Locale.UK, "%.1f", v)

    /**
     * Every stat as a labelled, coloured line. [sessionStart] is when the overlay
     * was switched on (elapsedRealtime), or null if it isn't on.
     */
    fun lines(s: Snapshot, shizukuReady: Boolean, sessionStart: Long?): List<StatLine> {
        val shizukuNote = if (shizukuReady) "n/a" else "needs Shizuku"
        val out = mutableListOf<StatLine>()
        fun add(key: String, label: String, value: String, colour: Int) {
            out += StatLine(key, label, value, colour)
        }

        add("show_fps", "FPS", s.fps?.let { "%.0f".format(it) } ?: shizukuNote, colourLow(s.fps, 50f, 30f))
        add(
            Prefs.SHOW_BATTERY, "BAT",
            deg(s.batteryC) + (s.batteryPct?.let { "  $it%" } ?: ""),
            colourFor(s.batteryC, 40f, 45f),
        )
        add(Prefs.SHOW_CPU, "CPU", deg(s.cpuC), colourFor(s.cpuC, 70f, 85f))
        add(Prefs.SHOW_GPU, "GPU", deg(s.gpuC), colourFor(s.gpuC, 70f, 85f))
        add(Prefs.SHOW_THERMAL, "THROTTLE", headroomText(s.headroom), headroomColour(s.headroom))
        add(
            "show_cpu_usage", "CPU USE", s.cpuUsage?.let { "$it%" } ?: shizukuNote,
            colourFor(s.cpuUsage?.toFloat(), 70f, 90f),
        )
        add(
            "show_gpu_usage", "GPU USE", s.gpuUsage?.let { "$it%" } ?: shizukuNote,
            colourFor(s.gpuUsage?.toFloat(), 70f, 90f),
        )
        add(
            "show_cpu_clock", "CPU CLK",
            s.cpuGhz?.let { cur -> "${String.format(Locale.UK, "%.2f", cur)} GHz" + (s.cpuMaxGhz?.let { " / ${String.format(Locale.UK, "%.2f", it)}" } ?: "") }
                ?: "n/a",
            if (s.cpuGhz == null) GREY else WHITE,
        )
        add("show_gpu_clock", "GPU CLK", s.gpuMhz?.let { "$it MHz" } ?: shizukuNote, if (s.gpuMhz == null) GREY else WHITE)
        add(
            "show_watts", "POWER",
            s.watts?.let { if (s.charging) "+${one(it)} W charging" else "${one(it)} W" } ?: "n/a",
            when {
                s.watts == null -> GREY
                s.charging -> GREEN
                s.watts >= 9f -> RED
                s.watts >= 6f -> AMBER
                else -> GREEN
            },
        )
        add(
            "show_time_left", if (s.charging) "FULL IN" else "LEFT",
            s.hoursLeft?.let { duration(it) } ?: "n/a",
            when {
                s.hoursLeft == null -> GREY
                s.charging -> GREEN
                s.hoursLeft < 0.5f -> RED
                s.hoursLeft < 1.5f -> AMBER
                else -> GREEN
            },
        )
        add(
            "show_ram", "RAM",
            if (s.ramUsedGb != null && s.ramTotalGb != null) "${one(s.ramUsedGb)} / ${one(s.ramTotalGb)} GB" else "n/a",
            if (s.ramUsedGb != null && s.ramTotalGb != null && s.ramUsedGb / s.ramTotalGb > 0.9f) AMBER else WHITE,
        )
        add(
            "show_wifi", "WI-FI",
            when {
                !s.onWifi -> "not on Wi-Fi"
                s.wifiDbm == null -> "connected"
                else -> "${s.wifiDbm} dBm" + (s.wifiMbps?.let { "  $it Mbps" } ?: "")
            },
            if (!s.onWifi) GREY else colourLow(s.wifiDbm?.toFloat(), -60f, -72f),
        )
        add(
            "show_ping", "PING",
            when {
                s.pingMs != null -> "${s.pingMs} ms"
                s.pingFailed -> "no reply"
                else -> "…"
            },
            when {
                s.pingFailed -> RED
                s.pingMs == null -> GREY
                s.pingMs >= 100 -> RED
                s.pingMs >= 50 -> AMBER
                else -> GREEN
            },
        )
        val session = sessionStart?.let { start ->
            val secs = (SystemClock.elapsedRealtime() - start) / 1000
            "%d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60)
        }
        add("show_session", "SESSION", session ?: "overlay off", if (session == null) GREY else WHITE)
        add("show_clock", "TIME", SimpleDateFormat("HH:mm", Locale.UK).format(Date()), WHITE)
        return out
    }

    /** Builds the coloured text for the overlay, in a column or a single row. */
    fun overlayText(s: Snapshot, p: SharedPreferences, shizukuReady: Boolean, sessionStart: Long?): CharSequence {
        val horizontal = p.getBoolean(Prefs.HORIZONTAL, false)
        val sb = SpannableStringBuilder()
        for (l in lines(s, shizukuReady, sessionStart)) {
            if (!StatList.isOn(p, l.key)) continue
            if (sb.isNotEmpty()) sb.append(if (horizontal) "   " else "\n")
            sb.append(l.label).append(" ")
            val start = sb.length
            sb.append(l.value)
            sb.setSpan(ForegroundColorSpan(l.colour), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isEmpty()) sb.append("TempHUD")
        return sb
    }
}
