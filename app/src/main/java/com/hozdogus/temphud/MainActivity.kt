package com.hozdogus.temphud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var batteryValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var gpuValue: TextView
    private lateinit var heatValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var blockedNote: TextView
    private lateinit var sensorList: TextView
    private lateinit var overlayButton: Button

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000L)
        }
    }

    private val bg = Color.parseColor("#0E1218")
    private val card = Color.parseColor("#1A212B")
    private val muted = Color.parseColor("#9AA4B2")

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

        root.addView(text("TempHUD", 26f, Color.WHITE, bold = true))
        root.addView(text("Live temperatures, refreshed every 2 seconds", 13f, muted))
        root.addView(space(16))

        // Readings card
        val readings = cardLayout()
        batteryValue = addRow(readings, "Battery")
        cpuValue = addRow(readings, "CPU")
        gpuValue = addRow(readings, "GPU")
        heatValue = addRow(readings, "Heat level")
        statusValue = addRow(readings, "Thermal status")
        root.addView(readings)

        blockedNote = text(
            "Your phone isn't letting apps read the CPU/GPU sensors directly. " +
                "Heat level still works: it's Android's own measure of how close the phone " +
                "is to slowing down to cool off (100% = throttling).",
            13f, muted
        ).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        root.addView(blockedNote)
        root.addView(space(20))

        // Overlay controls
        root.addView(text("Game overlay", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        overlayButton = Button(this).apply { setOnClickListener { toggleOverlay() } }
        root.addView(overlayButton)
        root.addView(space(8))

        val prefs = Prefs.get(this)
        val options = cardLayout()
        options.addView(text("Show on overlay", 14f, muted))
        listOf(
            Prefs.SHOW_BATTERY to "Battery",
            Prefs.SHOW_CPU to "CPU",
            Prefs.SHOW_GPU to "GPU",
            Prefs.SHOW_THERMAL to "Heat level",
        ).forEach { (key, label) ->
            options.addView(CheckBox(this).apply {
                text = label
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                }
            })
        }
        options.addView(space(8))
        options.addView(slider("Background opacity", Prefs.OPACITY, 70, 20, 100, "%"))
        options.addView(slider("Text size", Prefs.TEXT_SIZE, 12, 9, 20, "sp"))
        options.addView(text("Tip: drag the overlay to move it. It remembers where you leave it.", 12f, muted))
        root.addView(options)
        root.addView(space(20))

        // Every readable sensor, for the curious
        root.addView(text("All readable sensors", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        sensorList = text("", 12f, muted).apply { typeface = Typeface.MONOSPACE }
        root.addView(cardLayout().apply { addView(sensorList) })

        setContentView(scroll)
        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        updateButton()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val s = ThermalReader.read(this)
        batteryValue.text = Format.deg(s.batteryC) + (s.batteryPct?.let { "  ($it%)" } ?: "")
        batteryValue.setTextColor(Format.colourFor(s.batteryC, 40f, 45f))
        cpuValue.text = Format.deg(s.cpuC)
        cpuValue.setTextColor(Format.colourFor(s.cpuC, 70f, 85f))
        gpuValue.text = Format.deg(s.gpuC)
        gpuValue.setTextColor(Format.colourFor(s.gpuC, 70f, 85f))
        heatValue.text = Format.headroomText(s.headroom)
        heatValue.setTextColor(Format.headroomColour(s.headroom))
        statusValue.text = ThermalReader.statusLabel(s.thermalStatus)

        blockedNote.visibility = if (s.cpuC == null || s.gpuC == null) View.VISIBLE else View.GONE

        sensorList.text = if (s.zones.isEmpty()) {
            "No sensors are readable on this phone."
        } else {
            s.zones.joinToString("\n") { z -> "%-24s %s".format(z.name.take(24), Format.deg(z.celsius)) }
        }
        updateButton()
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            handler.postDelayed({ updateButton() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        handler.postDelayed({ updateButton() }, 300)
    }

    private fun updateButton() {
        overlayButton.text = when {
            OverlayService.running -> "Hide overlay"
            !Settings.canDrawOverlays(this) -> "Allow overlay permission"
            else -> "Show overlay"
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---- small UI helpers ----

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
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(card)
        }
    }

    private fun addRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(text(label, 16f, muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = text("…", 18f, Color.WHITE, bold = true)
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun slider(label: String, key: String, default: Int, min: Int, max: Int, unit: String): View {
        val prefs = Prefs.get(this)
        val current = prefs.getInt(key, default).coerceIn(min, max)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("$label: $current$unit", 14f, Color.WHITE)
        box.addView(title)
        box.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    title.text = "$label: $value$unit"
                    prefs.edit().putInt(key, value).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        return box
    }
}
