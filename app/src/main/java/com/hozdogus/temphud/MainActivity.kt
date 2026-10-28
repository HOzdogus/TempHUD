package com.hozdogus.temphud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val rows = mutableMapOf<String, TextView>()
    private lateinit var statusValue: TextView
    private lateinit var blockedNote: TextView
    private lateinit var sensorList: TextView
    private lateinit var overlayButton: Button
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button

    private val onStats: (Snapshot) -> Unit = { refresh(it) }

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
        root.addView(text("Live gaming stats, refreshed every second", 13f, muted))
        root.addView(space(16))

        // ---- Live readings ----
        val readings = cardLayout()
        for (def in StatList.all) {
            if (def.key == "show_session" || def.key == "show_clock") continue
            rows[def.key] = addRow(readings, def.label)
        }
        statusValue = addRow(readings, "Thermal status")
        root.addView(readings)

        blockedNote = text(
            "Your phone isn't letting apps read the CPU/GPU temperature sensors directly. " +
                "Turning on Shizuku (below) usually fixes that.",
            13f, muted,
        ).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        root.addView(blockedNote)
        root.addView(space(20))

        // ---- Shizuku ----
        root.addView(text("Extra stats with Shizuku", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        val shz = cardLayout()
        shz.addView(
            text(
                "FPS, CPU and GPU usage and GPU clock speed are hidden from normal apps. " +
                    "Shizuku (the same app PC Keyboard uses) unlocks them, and usually CPU/GPU temperatures too.",
                13f, muted,
            ),
        )
        shizukuStatus = text("", 14f, Color.WHITE, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) }
        shz.addView(shizukuStatus)
        shizukuButton = Button(this).apply { setOnClickListener { shizukuAction() } }
        shz.addView(shizukuButton)
        root.addView(shz)
        root.addView(space(20))

        // ---- Overlay controls ----
        root.addView(text("Game overlay", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        overlayButton = Button(this).apply { setOnClickListener { toggleOverlay() } }
        root.addView(overlayButton)
        root.addView(space(8))

        val prefs = Prefs.get(this)
        val options = cardLayout()
        options.addView(text("Show on overlay", 14f, muted))
        for (def in StatList.all) {
            options.addView(CheckBox(this).apply {
                text = if (def.needsShizuku) "${def.label}  (Shizuku)" else def.label
                setTextColor(Color.WHITE)
                isChecked = StatList.isOn(prefs, def.key)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(def.key, checked).apply() }
            })
        }
        options.addView(space(8))
        options.addView(CheckBox(this).apply {
            text = "Show in one row (good for landscape)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(Prefs.HORIZONTAL, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(Prefs.HORIZONTAL, checked).apply() }
        })
        options.addView(space(8))
        options.addView(slider("Background opacity", Prefs.OPACITY, 70, 20, 100, "%"))
        options.addView(slider("Text size", Prefs.TEXT_SIZE, 12, 9, 20, "sp"))
        options.addView(space(8))
        options.addView(text("Ping server", 14f, Color.WHITE))
        options.addView(EditText(this).apply {
            setText(prefs.getString(Prefs.PING_HOST, Prefs.DEFAULT_PING_HOST))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(Prefs.PING_HOST, s?.toString()?.trim().orEmpty()).apply()
                }
            })
        })
        options.addView(
            text(
                "1.1.1.1 (Cloudflare) is a good general test. For Shadow PC or an online game, " +
                    "put in its server address instead.",
                12f, muted,
            ),
        )
        options.addView(space(6))
        options.addView(text("Tip: drag the overlay to move it. It remembers where you leave it.", 12f, muted))
        root.addView(options)
        root.addView(space(20))

        // ---- Every readable sensor ----
        root.addView(text("All readable temperature sensors", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        sensorList = text("", 12f, muted).apply { typeface = Typeface.MONOSPACE }
        root.addView(cardLayout().apply { addView(sensorList) })

        setContentView(scroll)
        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        ShizukuStats.start()
        Stats.subscribe(this, onStats)
        updateButtons()
    }

    override fun onPause() {
        Stats.unsubscribe(onStats)
        super.onPause()
    }

    private fun refresh(s: Snapshot) {
        val shizuku = ShizukuStats.ready()
        for (line in Format.lines(s, shizuku, OverlayService.startedAt)) {
            val tv = rows[line.key] ?: continue
            tv.text = line.value
            tv.setTextColor(line.colour)
        }
        statusValue.text = ThermalLabels.status(s.thermalStatus)
        blockedNote.visibility = if ((s.cpuC == null || s.gpuC == null) && !shizuku) View.VISIBLE else View.GONE
        sensorList.text = if (s.zones.isEmpty()) {
            "None are readable directly on this phone." + if (shizuku) " (Using Android's thermal service through Shizuku instead.)" else ""
        } else {
            s.zones.joinToString("\n") { z -> "%-24s %s".format(z.name.take(24), Format.deg(z.celsius)) }
        }
        updateButtons()
    }

    private fun updateButtons() {
        overlayButton.text = when {
            OverlayService.running -> "Hide overlay"
            !Settings.canDrawOverlays(this) -> "Allow overlay permission"
            else -> "Show overlay"
        }
        when (ShizukuStats.state(this)) {
            ShizukuStats.State.READY -> {
                shizukuStatus.text = "✓ Connected. Extra stats are on."
                shizukuStatus.setTextColor(Format.GREEN)
                shizukuButton.visibility = View.GONE
            }
            ShizukuStats.State.CONNECTING -> {
                shizukuStatus.text = "Connecting…"
                shizukuStatus.setTextColor(Format.AMBER)
                shizukuButton.visibility = View.GONE
                ShizukuStats.connect()
            }
            ShizukuStats.State.NO_PERMISSION -> {
                shizukuStatus.text = "Shizuku is running. Allow TempHUD to use it."
                shizukuStatus.setTextColor(Format.AMBER)
                shizukuButton.text = "Allow"
                shizukuButton.visibility = View.VISIBLE
            }
            ShizukuStats.State.NOT_RUNNING -> {
                shizukuStatus.text = "Shizuku isn't running. Start it with Wireless debugging."
                shizukuStatus.setTextColor(Format.AMBER)
                shizukuButton.text = "Open Shizuku"
                shizukuButton.visibility = View.VISIBLE
            }
            ShizukuStats.State.NOT_INSTALLED -> {
                shizukuStatus.text = "Shizuku isn't installed."
                shizukuStatus.setTextColor(muted)
                shizukuButton.text = "Get Shizuku"
                shizukuButton.visibility = View.VISIBLE
            }
        }
    }

    private fun shizukuAction() {
        when (ShizukuStats.state(this)) {
            ShizukuStats.State.NO_PERMISSION -> ShizukuStats.requestPermission()
            ShizukuStats.State.NOT_RUNNING -> {
                val launch = packageManager.getLaunchIntentForPackage(ShizukuStats.SHIZUKU_PACKAGE)
                if (launch != null) startActivity(launch) else openStore()
            }
            ShizukuStats.State.NOT_INSTALLED -> openStore()
            else -> Unit
        }
    }

    private fun openStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuStats.SHIZUKU_PACKAGE}")))
        } catch (e: Exception) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuStats.SHIZUKU_PACKAGE}")),
            )
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            handler.postDelayed({ updateButtons() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        handler.postDelayed({ updateButtons() }, 300)
    }

    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
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
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text(label, 15f, muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = text("…", 16f, Color.WHITE, bold = true)
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
