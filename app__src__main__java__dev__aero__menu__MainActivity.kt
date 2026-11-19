package dev.aero.menu

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var injectLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E0E14"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "AERO"
            textSize = 40f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "floating menu  \u00B7  v" + RootInjector.VERSION
            textSize = 14f
            setTextColor(Color.parseColor("#8A8AA0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E6E6F0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(status)

        root.addView(button("Grant overlay permission", true) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("Start menu", false) {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, OverlayService::class.java))
            } else {
                refresh()
            }
        })
        root.addView(button("Stop menu", false) {
            stopService(Intent(this, OverlayService::class.java))
        })

        root.addView(TextView(this).apply {
            text = "ROOT INJECTOR  \u2014  unverified, read the log below"
            textSize = 11f
            setTextColor(Color.parseColor("#8A8AA0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        })

        val packageField = EditText(this).apply {
            setText("com.axlebolt.standoff2")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A8AA0"))
            hint = "target package name"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C28"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(packageField)

        injectLog = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8A8AA0"))
            text = "Not run yet."
        }
        val logScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(injectLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C28"))
                cornerRadius = dp(10).toFloat()
            }
        }

        val injectButton = button("Inject (root)", true) {}
        injectButton.setOnClickListener {
            injectButton.isEnabled = false
            injectLog.text = "Requesting root and running injector\u2026"
            val pkg = packageField.text.toString().trim()
            AeroState.targetPackage = pkg
            Thread {
                val result = RootInjector.inject(this, pkg)
                runOnUiThread {
                    injectLog.text = result.log
                    injectButton.isEnabled = true
                }
            }.start()
        }
        root.addView(injectButton)
        root.addView(logScroll)
        root.addView(button("Copy log", false) {
            val text = injectLog.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Aero injector log", text))
            Toast.makeText(this, "Log copied (${text.length} chars)", Toast.LENGTH_SHORT).show()
        })

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        status.text = if (ok) "Overlay permission: granted" else "Overlay permission: not granted"
    }

    private fun button(label: String, primary: Boolean, onClick: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) ACCENT else Color.parseColor("#22222E"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener(onClick)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val ACCENT: Int = Color.parseColor("#7C5CFF")
    }
}
