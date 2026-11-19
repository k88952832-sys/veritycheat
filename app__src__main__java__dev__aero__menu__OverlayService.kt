package dev.aero.menu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams
    private var panelVisible = false

    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var profileDot: View
    private lateinit var profileValue: TextView
    private lateinit var menuDot: View
    private lateinit var menuValue: TextView
    private val tabViews = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()

        bubbleLp = newParams(dp(54), dp(54)).apply { x = dp(16); y = dp(120) }
        panelLp = newParams(dp(304), WindowManager.LayoutParams.WRAP_CONTENT)

        bubble = buildBubble().also { wm.addView(it, bubbleLp) }
        panel = buildPanel()
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        if (panelVisible) panel?.let { runCatching { wm.removeView(it) } }
        bubble = null
        panel = null
        super.onDestroy()
    }

    // ---- window plumbing -------------------------------------------------------------------

    private fun newParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun togglePanel() {
        val p = panel ?: return
        if (panelVisible) {
            wm.removeView(p)
        } else {
            panelLp.x = bubbleLp.x
            panelLp.y = bubbleLp.y + dp(62)
            wm.addView(p, panelLp)
            refreshRemoteStatus()
        }
        panelVisible = !panelVisible
    }

    private fun makeDraggable(
        handle: View,
        target: View,
        lp: WindowManager.LayoutParams,
        onClick: (() -> Unit)? = null,
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        if (target.isAttachedToWindow) wm.updateViewLayout(target, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onClick?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAsForeground() {
        val channelId = "aero_overlay"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Aero Menu")
            .setContentText("Floating menu is running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---- remote calls, off the main thread ---------------------------------------------------

    private fun runInBackground(block: () -> Unit) {
        Thread(block).start()
    }

    private fun setStatus(text: String) {
        handler.post { statusText.text = text }
    }

    /** Queries the game process for what it can actually report (menu open state,
     * offset profile readiness) and reflects that in the status dots. ESP has no
     * exported getter in entry.cpp, only a setter, so its switch cannot be synced
     * this way; it always starts unchecked and only reflects what Aero itself last
     * sent, which the "ESP" row's helper text says explicitly. */
    private fun refreshRemoteStatus() {
        setDotState(profileDot, profileValue, null, "checking\u2026")
        setDotState(menuDot, menuValue, null, "checking\u2026")
        runInBackground {
            val pkg = AeroState.targetPackage
            val ready = RootInjector.isOffsetProfileReady(this, pkg)
            val menuOpen = RootInjector.isMenuOpen(this, pkg)
            handler.post {
                setDotState(profileDot, profileValue, ready, if (ready == true) "ready" else if (ready == false) "not ready" else "unknown")
                setDotState(menuDot, menuValue, menuOpen, if (menuOpen == true) "open" else if (menuOpen == false) "closed" else "unknown")
            }
            if (ready == null && menuOpen == null) {
                setStatus("Both checks came back unknown \u2014 $pkg likely isn't injected yet.")
            }
        }
    }

    private fun setDotState(dot: View, valueText: TextView, state: Boolean?, label: String) {
        val color = when (state) { true -> GOOD; false -> BAD; null -> UNKNOWN }
        (dot.background as GradientDrawable).setColor(color)
        valueText.text = label
        valueText.setTextColor(color)
    }

    // ---- views -----------------------------------------------------------------------------

    private fun buildBubble(): View {
        val v = TextView(this).apply {
            text = "A"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }
        makeDraggable(v, v, bubbleLp) { togglePanel() }
        return v
    }

    private fun buildPanel(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, 20).also { it.setStroke(dp(1), Color.parseColor("#3A3A4C")) }
            elevation = dp(10).toFloat()
            alpha = PanelSettings.opacityPercent / 100f
        }

        // header with a thin accent strip along the top edge
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT, ACCENT2, Color.parseColor("#00000000"))
            )
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "AERO"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "v" + RootInjector.VERSION
            textSize = 10f
            setTextColor(MUTED)
            background = rounded(Color.parseColor("#1F1F2C"), 8)
            setPadding(dp(7), dp(3), dp(7), dp(3))
        })
        header.addView(TextView(this).apply {
            text = "\u2715"
            textSize = 16f
            setTextColor(MUTED)
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { togglePanel() }
        })
        makeDraggable(header, root, panelLp)
        root.addView(header)

        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        listOf("Control", "Settings").forEachIndexed { i, title ->
            val t = TextView(this).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(t)
            tabs.addView(t)
        }
        root.addView(tabs)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)),
        )

        selectTab(0)
        return root
    }

    private fun selectTab(index: Int) {
        tabViews.forEachIndexed { i, t ->
            val active = i == index
            t.setTextColor(if (active) Color.WHITE else MUTED)
            t.background = if (active)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2))
                    .apply { cornerRadius = dp(10).toFloat() }
            else
                rounded(Color.parseColor("#1A1A24"), 10)
        }
        content.removeAllViews()
        if (index == 0) buildControlTab() else buildSettingsTab()
    }

    /** The real, wired panel: every control here calls one of RootInjector's
     * functions, which in turn runs su and the staged injector. Nothing here is
     * decorative; [statusText] always shows the exact outcome of the last action. */
    private fun buildControlTab() {
        content.addView(sectionLabel("LIVE STATUS"))
        val statusCard = card()
        profileDot = statusDot()
        profileValue = TextView(this).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        statusCard.addView(statusRow("Offset profile", profileDot, profileValue))
        statusCard.addView(divider())
        menuDot = statusDot()
        menuValue = TextView(this).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        statusCard.addView(statusRow("Ximira menu", menuDot, menuValue))
        content.addView(statusCard)
        setDotState(profileDot, profileValue, null, "unknown")
        setDotState(menuDot, menuValue, null, "unknown")

        content.addView(sectionLabel("CONTROLS"))
        content.addView(toggleRow("ESP", "Write-only \u2014 this switch shows what Aero last sent, not a live readback") { checked ->
            setStatus("Setting ESP = $checked\u2026")
            runInBackground {
                val r = RootInjector.setEsp(this, AeroState.targetPackage, checked)
                setStatus(if (r.success) "ESP set to $checked (library accepted the call)" else "ESP call failed:\n${r.log}")
            }
        })
        content.addView(toggleRow("Ximira menu", "Shows/hides Ximira's own in-game panel") { checked ->
            setStatus("Setting Ximira menu open = $checked\u2026")
            runInBackground {
                val r = RootInjector.setMenuOpen(this, AeroState.targetPackage, checked)
                setStatus(if (r.success) "Ximira menu set to open=$checked" else "Menu call failed:\n${r.log}")
            }
        })

        content.addView(sectionLabel("ACTIONS"))
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        actionsRow.addView(secondaryButton("Refresh") { refreshRemoteStatus() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        })
        actionsRow.addView(secondaryButton("Dump log") {
            setStatus("Requesting diagnostics dump\u2026")
            runInBackground {
                val r = RootInjector.dumpDiagnostics(this, AeroState.targetPackage)
                setStatus(if (r.success) "Diagnostics dumped; check logcat (grep lemming) on the device"
                          else "Dump call failed:\n${r.log}")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        })
        content.addView(actionsRow)

        statusText = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            text = "Open this panel to check status, or use the buttons above."
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }
        content.addView(statusText)
    }

    private fun buildSettingsTab() {
        content.addView(sectionLabel("PANEL"))
        val col = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val value = TextView(this).apply {
            text = "${PanelSettings.opacityPercent}%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT2)
        }
        top.addView(TextView(this).apply {
            text = "Opacity"
            textSize = 14f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(value)
        col.addView(top)
        col.addView(SeekBar(this).apply {
            max = 60
            progress = PanelSettings.opacityPercent - 40
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 40 + p
                    value.text = "$v%"
                    if (fromUser) {
                        PanelSettings.opacityPercent = v
                        panel?.alpha = v / 100f
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        content.addView(col)
    }

    // ---- small view builders ----------------------------------------------------------------

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(MUTED)
        setPadding(dp(2), dp(12), dp(2), dp(6))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(CARD, 12).also { it.setStroke(dp(1), CARD_BORDER) }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }
    }

    private fun statusDot(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) }
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UNKNOWN) }
    }

    private fun statusRow(label: String, dot: View, valueText: TextView): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dot)
        row.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(valueText)
        return row
    }

    private fun toggleRow(label: String, hint: String, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = rounded(CARD, 12).also { it.setStroke(dp(1), CARD_BORDER) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
        })
        textCol.addView(TextView(this).apply {
            text = hint
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(2), dp(8), 0)
        })
        row.addView(textCol)
        row.addView(Switch(this).apply {
            isChecked = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#8A8AA0")))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT, Color.parseColor("#33334A")))
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        })
        return row
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ACCENT2)
            background = rounded(Color.parseColor("#1A1A24"), 10).also { it.setStroke(dp(1), Color.parseColor("#3A3A4C")) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val ACCENT: Int = Color.parseColor("#7C5CFF")
        val ACCENT2: Int = Color.parseColor("#5B8DFF")
        val BG: Int = Color.parseColor("#F013131B")
        val CARD: Int = Color.parseColor("#1A1A24")
        val CARD_BORDER: Int = Color.parseColor("#2A2A38")
        val TEXT: Int = Color.parseColor("#F1F1F6")
        val MUTED: Int = Color.parseColor("#8E8EA6")
        val GOOD: Int = Color.parseColor("#22C55E")
        val BAD: Int = Color.parseColor("#EF4444")
        val UNKNOWN: Int = Color.parseColor("#8E8EA6")
    }
}
