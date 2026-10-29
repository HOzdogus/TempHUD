package com.hozdogus.temphud

import android.content.Context
import java.io.File
import java.util.Locale

/** One background reading: which app was on screen and how hot the phone was. */
data class HeatSample(
    val ts: Long,
    val pkg: String,
    val batteryC: Float?,
    val cpuC: Float?,
    val charging: Boolean,
)

/** The log file: one CSV line per sample, kept for [KEEP_DAYS] days. */
object HeatLog {
    const val INTERVAL_MS = 15_000L
    private const val KEEP_DAYS = 14
    private const val FILE = "heatlog.csv"

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun append(context: Context, s: HeatSample) {
        val line = buildString {
            append(s.ts).append(',').append(s.pkg).append(',')
            s.batteryC?.let { append(String.format(Locale.ROOT, "%.1f", it)) }
            append(',')
            s.cpuC?.let { append(String.format(Locale.ROOT, "%.1f", it)) }
            append(',').append(if (s.charging) '1' else '0').append('\n')
        }
        file(context).appendText(line)
    }

    @Synchronized
    fun readAll(context: Context): List<HeatSample> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.useLines { lines -> lines.mapNotNull(::parse).toList() }
    }

    /** Drops samples older than two weeks so the file doesn't grow forever. */
    @Synchronized
    fun prune(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        val kept = f.useLines { lines ->
            lines.filter { (it.substringBefore(',').toLongOrNull() ?: 0L) >= cutoff }.toList()
        }
        f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    private fun parse(line: String): HeatSample? {
        val p = line.split(',')
        if (p.size < 5) return null
        return HeatSample(
            ts = p[0].toLongOrNull() ?: return null,
            pkg = p[1].ifEmpty { return null },
            batteryC = p[2].toFloatOrNull(),
            cpuC = p[3].toFloatOrNull(),
            charging = p[4] == "1",
        )
    }
}

enum class Sensor(val label: String, val spikeRise: Float, val hotAt: Float) {
    BATTERY("Battery", spikeRise = 3f, hotAt = 40f),
    CPU("CPU", spikeRise = 10f, hotAt = 75f);

    fun of(s: HeatSample): Float? = if (this == BATTERY) s.batteryC else s.cpuC
}

/** One continuous stretch of an app being on screen. */
data class HeatSession(
    val start: Long,
    val end: Long,
    val startC: Float,
    val endC: Float,
    val peakC: Float,
    val spikes: Int,
) {
    val minutes: Float get() = (end - start + HeatLog.INTERVAL_MS) / 60_000f
}

data class AppHeat(
    val pkg: String,
    val minutes: Float,
    val avgC: Float,
    val peakC: Float,
    /** Average temperature change per 10 minutes of use. Null if there isn't enough data yet. */
    val risePer10Min: Float?,
    /** Times the temperature jumped sharply within a few minutes while this app was open. */
    val spikes: Int,
    /** Share of this app's time spent at or above the sensor's "hot" level, 0..1. */
    val hotShare: Float,
    val sessions: List<HeatSession>,
)

object HeatAnalysis {
    /** A pause longer than this (screen off, phone asleep) starts a new session. */
    private const val GAP_MS = 60_000L
    private const val SPIKE_WINDOW_MS = 3 * 60_000L
    /** Sessions shorter than this are too brief to judge a heating rate from. */
    private const val MIN_RATE_SESSION_MS = 2 * 60_000L

    fun analyse(all: List<HeatSample>, sensor: Sensor, includeCharging: Boolean): List<AppHeat> {
        val samples = all
            .filter { (includeCharging || !it.charging) && sensor.of(it) != null }
            .sortedBy { it.ts }

        // Split into sessions: same app, no long gaps.
        val sessionsByApp = HashMap<String, MutableList<List<HeatSample>>>()
        var current = ArrayList<HeatSample>()
        fun flush() {
            if (current.isNotEmpty()) {
                sessionsByApp.getOrPut(current[0].pkg) { mutableListOf() }.add(current)
                current = ArrayList()
            }
        }
        for (s in samples) {
            val last = current.lastOrNull()
            if (last != null && (last.pkg != s.pkg || s.ts - last.ts > GAP_MS)) flush()
            current.add(s)
        }
        flush()

        return sessionsByApp.map { (pkg, runs) ->
            val sessions = runs.map { run -> summarise(run, sensor) }
            val temps = runs.flatten().map { sensor.of(it)!! }

            val rated = sessions.filter { it.end - it.start >= MIN_RATE_SESSION_MS }
            val ratedMs = rated.sumOf { it.end - it.start }
            val rise = if (ratedMs > 0) {
                rated.sumOf { (it.endC - it.startC).toDouble() }.toFloat() / ratedMs * 600_000f
            } else null

            AppHeat(
                pkg = pkg,
                minutes = temps.size * HeatLog.INTERVAL_MS / 60_000f,
                avgC = temps.average().toFloat(),
                peakC = temps.max(),
                risePer10Min = rise,
                spikes = sessions.sumOf { it.spikes },
                hotShare = temps.count { it >= sensor.hotAt }.toFloat() / temps.size,
                sessions = sessions.sortedByDescending { it.start },
            )
        }
    }

    private fun summarise(run: List<HeatSample>, sensor: Sensor): HeatSession {
        val temps = run.map { sensor.of(it)!! }
        var spikes = 0
        var lastSpikeAt = Long.MIN_VALUE / 2
        for (i in run.indices) {
            val now = run[i].ts
            // Coolest reading in the last few minutes of this session.
            var low = temps[i]
            var j = i - 1
            while (j >= 0 && now - run[j].ts <= SPIKE_WINDOW_MS) {
                if (temps[j] < low) low = temps[j]
                j--
            }
            if (temps[i] - low >= sensor.spikeRise && now - lastSpikeAt > SPIKE_WINDOW_MS) {
                spikes++
                lastSpikeAt = now
            }
        }
        return HeatSession(
            start = run.first().ts,
            end = run.last().ts,
            startC = temps.first(),
            endC = temps.last(),
            peakC = temps.max(),
            spikes = spikes,
        )
    }
}
