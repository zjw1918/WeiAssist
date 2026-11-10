package com.lanqi.weiassist.gateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.content.res.Configuration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lanqi.weiassist.magpie.BuildConfig as MagpieBuild
import com.lanqi.weiassist.magpie.MagpieHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The gateway's one screen: on or off, where this phone's apps and the network's devices reach it, and its console
 * (providers, sign-ins, routing, usage, gateway keys), opened in the browser. Plain framework views keep this app to
 * little more than magpie itself.
 */
class GatewayActivity : Activity() {
    private val host by lazy { MagpieHost.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    private lateinit var status: TextView
    private lateinit var toggle: Switch
    private lateinit var addresses: TextView
    private lateinit var console: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn under transparent system bars on every Android version (15+ does so anyway), with the bars' icons dark
        // on the light theme and light on the dark one: else they stay white on the white page and can't be read.
        WindowCompat.enableEdgeToEdge(window)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Android 15+ draws the app under the status and navigation bars: keep the content (the switch first) clear of them.
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        status = text("", 24f)
        row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toggle = Switch(this).apply {
            setOnClickListener { if (isChecked) turnOn() else GatewayService.stop(this@GatewayActivity) }
        }
        row.addView(toggle)
        column.addView(row)
        column.addView(text(getString(R.string.why), 18f, muted = true))
        addresses = text("", 16f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(8))
        }
        column.addView(addresses)
        console = Button(this).apply {
            text = getString(R.string.open_console)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(host.consoleUrl))) }
            }
        }
        column.addView(console, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Many phones (realme, OPPO, Xiaomi, vivo…) don't let an app start by itself after a restart or an update
        // until the user allows it (on the realme, OplusAppStartupManager refused MY_PACKAGE_REPLACED's start).
        column.addView(text(getString(R.string.autostart_hint), 17f, muted = true).apply { setPadding(0, dp(24), 0, dp(4)) })
        column.addView(Button(this).apply {
            text = getString(R.string.autostart_settings)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setOnClickListener {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Which magpie and which app this is (to compare with magpie's releases, or to report a problem).
        column.addView(text(versions(), 14f, muted = true).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(32), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        GatewayService.resume(this) // on, but its process ended since: serve again
        watching = scope.launch { host.state.collect(::show) }
    }

    override fun onStop() {
        watching?.cancel()
        super.onStop()
    }

    private fun turnOn() {
        // Android 13+: without this the notification is hidden, though the gateway still serves.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        GatewayService.start(this)
    }

    private fun show(s: MagpieHost.State) {
        toggle.isEnabled = s != MagpieHost.State.Unavailable
        toggle.isChecked = s != MagpieHost.State.Off && s != MagpieHost.State.Unavailable
        status.text = when (s) {
            MagpieHost.State.Unavailable -> getString(R.string.status_unavailable)
            MagpieHost.State.Off -> getString(R.string.status_off)
            MagpieHost.State.Starting -> getString(R.string.status_starting)
            MagpieHost.State.Running -> getString(R.string.status_running)
            is MagpieHost.State.Failed -> getString(R.string.status_failed, s.message)
        }
        val running = s == MagpieHost.State.Running
        addresses.visibility = if (running) View.VISIBLE else View.GONE
        console.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val lan = host.lanBaseUrl()
            addresses.text = getString(R.string.this_phone, host.localBaseUrl) + "\n" +
                (if (lan != null) getString(R.string.network, lan) else getString(R.string.no_network))
        }
    }

    private fun text(s: String, sp: Float, muted: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        // A plain TextView takes the theme's grey default; these are the theme's real text colours, light and dark.
        val colors = theme.obtainStyledAttributes(intArrayOf(if (muted) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary))
        setTextColor(colors.getColorStateList(0))
        colors.recycle()
    }

    /** "magpie v0.1.3536 (03a800ac6) · Gateway 0.5.1": the tag when the build knew it, the commit always. */
    private fun versions(): String {
        val tag = MagpieBuild.MAGPIE_TAG
        val commit = MagpieBuild.MAGPIE_COMMIT
        val magpie = when {
            tag.isNotEmpty() && commit.isNotEmpty() -> "$tag ($commit)"
            tag.isNotEmpty() -> tag
            commit.isNotEmpty() -> commit
            else -> "?"
        }
        val app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        return getString(R.string.versions, magpie, app)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
