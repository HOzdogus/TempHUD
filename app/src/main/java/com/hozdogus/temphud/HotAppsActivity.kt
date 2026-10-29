package com.hozdogus.temphud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Ranks apps by how much they heat the phone, from the background log. */
class HotAppsActivity : Activity() {

    private enum class Sort(val label: String) {
        RISE("Heats fastest"), SPIKES("Most spikes"), PEAK("Hottest peak"), HOT_TIME("Time hot")
    }

    private val bg = Color.parseColor("#0E1218")
    private val card = Color.parseColor("#1A212B")
    private val muted = Color.parseColor("#9AA4B2")
    private val accent = Color.parseColor("#FF8A5B")

    private val io = Executors.newSingleThreadExecutor()
    private var samples: List<HeatSample> = emptyList()
    private var sensor = Sensor.BATTERY
    private var sort = Sort.RISE
    private var includeCharging = false

    private lateinit var summary: TextView
    private lateinit var permissionCard: LinearLayout
    private lateinit var sensorRow: LinearLayout
    private lateinit var sortRow: LinearLayout
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        root.addView(text("Hot apps", 26f, Color.WHITE, bold = true))
        root.addView(text("Which apps heat your phone up the most", 13f, muted))
        root.addView(space(12))

        permissionCard = cardLayout().apply {
            addView(
                text(
                    "To know which app is open, TempHUD needs Usage access. Turn it on for " +
                        "TempHUD on the next screen, then come back.",
                    14f, Color.WHITE,
                )
            )
            addView(space(8))
            addView(Button(this@HotAppsActivity).apply {
                text = "Open Usage access"
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                }
            })
        }
        root.addView(permissionCard)

        summary = text("Loading…", 13f, muted)
        root.addView(summary)
        root.addView(space(10))

        sensorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(sensorRow)
        root.addView(space(6))
        sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sortRow)
        })
        root.addView(CheckBox(this).apply {
            text = "Include time spent charging"
            setTextColor(Color.WHITE)
            isChecked = includeCharging
            setOnCheckedChangeListener { _, checked ->
                includeCharging = checked
                render()
            }
        })
        root.addView(space(8))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(space(16))

        root.addView(
            text(
                "How to read this: \"Heats fastest\" is how many degrees an app adds per 10 minutes of " +
                    "use, which is the fairest comparison. Heat builds up slowly, so an app opened " +
                    "straight after a heavy game can look hotter than it is. Charging time is left " +
                    "out by default because charging heats the battery by itself.",
                12f, muted,
            )
        )
        root.addView(space(12))
        root.addView(Button(this).apply {
            text = "Clear log"
            setOnClickListener {
                AlertDialog.Builder(this@HotAppsActivity)
                    .setTitle("Clear the heat log?")
                    .setMessage("All logged readings will be deleted and Hot apps will start again from nothing.")
                    .setPositiveButton("Clear") { _, _ ->
                        io.execute {
                            HeatLog.clear(this@HotAppsActivity)
                            runOnUiThread { load() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val allowed = HeatLogService.hasUsageAccess(this)
        permissionCard.visibility = if (allowed) View.GONE else View.VISIBLE
        if (allowed) HeatLogService.startIfEnabled(this)
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        io.execute {
            val loaded = HeatLog.readAll(this)
            runOnUiThread {
                samples = loaded
                // Default to CPU when most readings have it (usually with Shizuku), else battery.
                val cpuShare = if (loaded.isEmpty()) 0f else loaded.count { it.cpuC != null }.toFloat() / loaded.size
                if (cpuShare > 0.5f && sensor == Sensor.BATTERY && !userPickedSensor) sensor = Sensor.CPU
                render()
            }
        }
    }

    private var userPickedSensor = false

    private fun render() {
        val hasCpu = samples.any { it.cpuC != null }
        if (!hasCpu) sensor = Sensor.BATTERY

        sensorRow.removeAllViews()
        for (s in Sensor.entries) {
            if (s == Sensor.CPU && !hasCpu) continue
            sensorRow.addView(chip("${s.label} temp", s == sensor) {
                sensor = s
                userPickedSensor = true
                render()
            })
        }
        sortRow.removeAllViews()
        for (o in Sort.entries) {
            sortRow.addView(chip(o.label, o == sort) {
                sort = o
                render()
            })
        }

        val apps = HeatAnalysis.analyse(samples, sensor, includeCharging)
            .filter { it.minutes >= 1f }
        val sorted = when (sort) {
            Sort.RISE -> apps.sortedByDescending { it.risePer10Min ?: Float.NEGATIVE_INFINITY }
            Sort.SPIKES -> apps.sortedWith(compareByDescending<AppHeat> { it.spikes }.thenByDescending { it.peakC })
            Sort.PEAK -> apps.sortedByDescending { it.peakC }
            Sort.HOT_TIME -> apps.sortedByDescending { it.hotShare }
        }

        if (samples.isEmpty()) {
            summary.text = "No readings yet. Keep using your phone as normal. Logging runs in the " +
                "background and results appear here after a few minutes."
        } else {
            val first = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(samples.first().ts))
            val hours = samples.size * HeatLog.INTERVAL_MS / 3_600_000f
            summary.text = "Based on ${Format.duration(hours)} of screen-on logging since $first."
        }

        list.removeAllViews()
        if (samples.isNotEmpty() && sorted.isEmpty()) {
            list.addView(text("Not enough readings yet for this view.", 14f, muted))
        }
        val maxRise = sorted.mapNotNull { it.risePer10Min }.maxOrNull()?.coerceAtLeast(0.1f) ?: 1f
        for (app in sorted) {
            list.addView(appRow(app, maxRise))
            list.addView(space(8))
        }
    }

    private fun appRow(app: AppHeat, maxRise: Float): View {
        val row = cardLayout().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showDetails(app) }
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(iconFor(app.pkg))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(labelFor(app.pkg), 15f, Color.WHITE, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val (headline, colour) = headlineFor(app)
        top.addView(text(headline, 15f, colour, bold = true))
        col.addView(top)

        // Bar showing heating rate compared with the worst app.
        val fraction = ((app.risePer10Min ?: 0f) / maxRise).coerceIn(0f, 1f)
        val track = LinearLayout(this).apply {
            background = rounded(Color.parseColor("#2A323E"), 3)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            weightSum = 1f
        }
        if (fraction > 0f) {
            track.addView(View(this).apply {
                background = rounded(accent, 3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fraction.coerceAtLeast(0.03f))
            })
        }
        col.addView(track)

        col.addView(
            text(
                "Peak ${Format.deg(app.peakC)} · ${spikesText(app.spikes)} · " +
                    "${(app.hotShare * 100).toInt()}% of time hot · ${minutesText(app.minutes)} logged",
                12f, muted,
            )
        )
        row.addView(col)
        return row
    }

    private fun headlineFor(app: AppHeat): Pair<String, Int> = when (sort) {
        Sort.RISE -> {
            val r = app.risePer10Min
            when {
                r == null -> "too little data" to muted
                r >= 0.05f -> String.format(Locale.UK, "+%.1f°C/10m", r) to Format.colourFor(r, 1f, 3f)
                else -> String.format(Locale.UK, "%.1f°C/10m", r) to Format.GREEN
            }
        }
        Sort.SPIKES -> spikesText(app.spikes) to (if (app.spikes > 0) Format.AMBER else Format.GREEN)
        Sort.PEAK -> Format.deg(app.peakC) to Format.colourFor(app.peakC, sensor.hotAt - 4f, sensor.hotAt)
        Sort.HOT_TIME -> "${(app.hotShare * 100).toInt()}% hot" to
            Format.colourFor(app.hotShare * 100, 10f, 40f)
    }

    private fun showDetails(app: AppHeat) {
        val time = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK)
        val body = buildString {
            append("${sensor.label} temperature while open\n")
            append("Average ${Format.deg(app.avgC)} · Peak ${Format.deg(app.peakC)}\n")
            app.risePer10Min?.let { append(String.format(Locale.UK, "Heats %+.1f°C per 10 minutes of use\n", it)) }
            append("${spikesText(app.spikes)} (a jump of ${sensor.spikeRise.toInt()}°C+ within 3 minutes)\n")
            append("${(app.hotShare * 100).toInt()}% of the time at ${sensor.hotAt.toInt()}°C or more\n")
            append("${minutesText(app.minutes)} logged over ${app.sessions.size} sessions\n\n")
            append("Recent sessions\n")
            for (s in app.sessions.take(8)) {
                append(
                    "${time.format(Date(s.start))} · ${minutesText(s.minutes)} · " +
                        "${Format.deg(s.startC)} → ${Format.deg(s.endC)} (peak ${Format.deg(s.peakC)})" +
                        (if (s.spikes > 0) " · ${spikesText(s.spikes)}" else "") + "\n"
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(labelFor(app.pkg))
            .setMessage(body.trimEnd())
            .setPositiveButton("Close", null)
            .setNeutralButton("App info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                )
            }
            .show()
    }

    // ---- helpers ----

    private fun spikesText(n: Int) = if (n == 1) "1 spike" else "$n spikes"

    private fun minutesText(m: Float): String = Format.duration(m / 60f).let { if (it == "0m") "<1m" else it }

    private val labels = HashMap<String, String>()
    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun iconFor(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(if (selected) bg else Color.WHITE)
        if (selected) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(if (selected) accent else card, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun rounded(colour: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(colour)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(colour)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(card, 12)
    }
}
