package com.hozdogus.temphud

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("temphud", Context.MODE_PRIVATE)
}

object Format {
    val GREEN = Color.parseColor("#7CE38B")
    val AMBER = Color.parseColor("#FFC24B")
    val RED = Color.parseColor("#FF6B5B")
    val GREY = Color.parseColor("#AAAAAA")

    fun deg(c: Float?): String = if (c == null) "n/a" else String.format(Locale.UK, "%.1f°C", c)

    fun colourFor(c: Float?, warn: Float, hot: Float): Int = when {
        c == null -> GREY
        c >= hot -> RED
        c >= warn -> AMBER
        else -> GREEN
    }

    fun headroomColour(h: Float?): Int = when {
        h == null -> GREY
        h >= 0.9f -> RED
        h >= 0.7f -> AMBER
        else -> GREEN
    }

    fun headroomText(h: Float?): String = if (h == null) "n/a" else "${(h * 100).toInt()}%"

    /** Builds the coloured multi-line text used by the overlay. */
    fun overlayText(s: Snapshot, p: SharedPreferences): CharSequence {
        val sb = SpannableStringBuilder()
        fun line(label: String, value: String, colour: Int) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(label)
            val start = sb.length
            sb.append(value)
            sb.setSpan(ForegroundColorSpan(colour), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (p.getBoolean(Prefs.SHOW_BATTERY, true)) {
            val pct = s.batteryPct?.let { "  $it%" } ?: ""
            line("BAT ", deg(s.batteryC) + pct, colourFor(s.batteryC, 40f, 45f))
        }
        if (p.getBoolean(Prefs.SHOW_CPU, true)) line("CPU ", deg(s.cpuC), colourFor(s.cpuC, 70f, 85f))
        if (p.getBoolean(Prefs.SHOW_GPU, true)) line("GPU ", deg(s.gpuC), colourFor(s.gpuC, 70f, 85f))
        if (p.getBoolean(Prefs.SHOW_THERMAL, true)) {
            line("HEAT ", headroomText(s.headroom), headroomColour(s.headroom))
        }
        if (sb.isEmpty()) sb.append("TempHUD")
        return sb
    }
}
