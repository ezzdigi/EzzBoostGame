package com.ezzdigi.ezzgameboost

import android.Manifest
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    internal lateinit var prefs: SharedPreferences
    internal val executor: ExecutorService = Executors.newFixedThreadPool(4)
    internal val handler = Handler(Looper.getMainLooper())

    internal val bg0 = Color.rgb(8, 9, 14)
    internal val panel = Color.rgb(20, 22, 31)
    internal val panel2 = Color.rgb(29, 32, 45)
    internal val stroke = Color.rgb(44, 48, 66)
    internal val white = Color.rgb(242, 243, 247)
    internal val muted = Color.rgb(141, 147, 167)
    internal val good = Color.rgb(45, 255, 138)
    internal val warn = Color.rgb(255, 176, 32)
    internal val bad = Color.rgb(255, 55, 70)

    internal val accents = intArrayOf(
        Color.rgb(255, 55, 70),
        Color.rgb(25, 227, 255),
        Color.rgb(168, 85, 247),
        Color.rgb(45, 255, 138),
        Color.rgb(255, 176, 32)
    )
    internal val accentNames = arrayOf("قرمز", "فیروزه‌ای", "بنفش", "سبز", "نارنجی")
    internal var accent = accents[0]

    internal val tabIcons = arrayOf("🏠", "🎮", "📡", "🔐", "🛠", "📊")
    internal val tabNames = arrayOf("خانه", "بازی‌ها", "شبکه", "VPN", "ابزار", "آمار")

    internal var dnsCache: List<DnsProvider>? = null
    internal val dnsProviders: List<DnsProvider>
        get() {
            var c = dnsCache
            if (c == null) {
                c = DnsList.all(this)
                dnsCache = c
            }
            return c
        }

    internal val pingTargets = arrayOf(
        Triple("Cloudflare", "1.1.1.1", 443),
        Triple("Google", "8.8.8.8", 443),
        Triple("Quad9", "9.9.9.9", 443),
        Triple("AdGuard", "94.140.14.14", 443),
        Triple("OpenDNS", "208.67.222.222", 443)
    )

    internal var tab = 0
    internal lateinit var content: LinearLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var tabBar: LinearLayout
    internal var homeRefresh: (() -> Unit)? = null
    internal var netRefresh: (() -> Unit)? = null
    internal var vpnRefresh: (() -> Unit)? = null
    internal var vpnRender: (() -> Unit)? = null
    internal var wgBackend: GoBackend? = null
    internal var wgTunnel: WgTun? = null
    internal var wgBusy = false
    internal var pendingWg: String? = null
    internal var importName = ""
    internal var pendingDns: DnsProvider? = null
    internal var pendingLaunch: String? = null
    internal val dnsPing = HashMap<String, Int>()
    internal var dnsBenchRunning = false

    @Volatile
    internal var pingToken = 0
    internal var netTarget = 0

    internal var appsCache: List<AppItem>? = null
    internal var appsLoading = false
    internal var gamesFilter = 0
    internal var gamesQuery = ""

    internal val ticker = object : Runnable {
        override fun run() {
            homeRefresh?.invoke()
            netRefresh?.invoke()
            vpnRefresh?.invoke()
            handler.postDelayed(this, 2000)
        }
    }

    // ----- lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
        Tr.load(this)
        val idx = prefs.getInt("accent", 0)
        accent = accents[idx.coerceIn(0, accents.size - 1)]
        if (!prefs.getBoolean("onboarded", false)) {
            showOnboarding()
        } else {
            startMain()
        }
    }

    internal fun startMain() {
        buildShell()
        applyWindowToggles()
        showTab(0)
        autoChecks()
    }

    override fun onResume() {
        super.onResume()
        val recorded = finalizeSession()
        if (recorded && (tab == 0 || tab == 1 || tab == 5) && ::content.isInitialized) showTab(tab)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        refreshWidget()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        refreshWidget()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    // ----- shell ----------------------------------------------------------

    @Suppress("DEPRECATION")
    internal fun buildShell() {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg0)
            layoutDirection = if (Tr.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        }
        rootView.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        val titleView = TextView(this).apply {
            val sb = SpannableStringBuilder("EzzGameBOOST")
            sb.setSpan(ForegroundColorSpan(accent), 7, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            letterSpacing = 0.04f
        }
        val logo = LogoView(this, accent, panel)
        val logoLp = LinearLayout.LayoutParams(dp(40), dp(40))
        logoLp.marginEnd = dp(10)
        header.addView(logo, logoLp)
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(titleView)
        titleCol.addView(label("ایزی دیجی • ezzdigi.com", 10.5f, muted))
        titleCol.setOnClickListener { openUrl("https://ezzdigi.com") }
        header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val ver = TextView(this).apply {
            text = "v2.1"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.TRANSPARENT, 10, accent)
        }
        header.addView(ver)

        val glow = View(this)
        glow.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accent, Color.TRANSPARENT)
        )

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(22))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))

        val divider = View(this)
        divider.setBackgroundColor(stroke)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panel)
        }

        rootView.addView(header)
        rootView.addView(glow, LinearLayout.LayoutParams(MATCH, dp(2)))
        rootView.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootView.addView(divider, LinearLayout.LayoutParams(MATCH, 1))
        rootView.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(rootView)
    }

    internal fun refreshTabBar() {
        tabBar.removeAllViews()
        for (i in tabNames.indices) {
            val selected = i == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
            }
            val ic = TextView(this).apply {
                text = tabIcons[i]
                textSize = 19f
                gravity = Gravity.CENTER
                alpha = if (selected) 1f else 0.45f
            }
            val tx = TextView(this).apply {
                text = Tr.t(tabNames[i])
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(if (selected) accent else muted)
                if (selected) typeface = Typeface.DEFAULT_BOLD
            }
            item.addView(ic)
            item.addView(tx)
            item.setOnClickListener { if (tab != i) showTab(i) }
            tabBar.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    internal fun showTab(i: Int) {
        tab = i
        homeRefresh = null
        netRefresh = null
        vpnRefresh = null
        vpnRender = null
        pingToken++
        content.removeAllViews()
        refreshTabBar()
        when (i) {
            0 -> buildHome()
            1 -> buildGames()
            2 -> buildNetwork()
            3 -> buildVpn()
            4 -> buildTools()
            else -> buildStats()
        }
        scroll.scrollTo(0, 0)
    }

    // ----- helpers ---------------------------------------------------------

    internal fun runBg(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun ui(block: () -> Unit) {
        if (!isDestroyed && !isFinishing) runOnUiThread { block() }
    }

    internal fun toast(msg: String) {
        Toast.makeText(this, Tr.t(msg), Toast.LENGTH_SHORT).show()
    }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    internal fun accentAlpha(a: Int): Int =
        Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))

    internal fun onAccent(): Int {
        val lum = 0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)
        return if (lum > 150) Color.rgb(8, 9, 14) else Color.WHITE
    }

    internal fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    internal fun heroBg(): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accentAlpha(70), panel)
        )
        d.cornerRadius = dp(18).toFloat()
        d.setStroke(dp(1), accentAlpha(140))
        return d
    }

    internal fun vlp(topDp: Int = 0, bottomDp: Int = 10): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(topDp)
        p.bottomMargin = dp(bottomDp)
        return p
    }

    internal fun label(text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false, tr: Boolean = true): TextView {
        val t = TextView(this)
        t.text = if (tr) Tr.t(text.toString()) else text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.setLineSpacing(dp(2).toFloat(), 1f)
        return t
    }

    internal fun section(text: String) {
        val t = label(text, 12f, accent, true)
        t.letterSpacing = 0.12f
        t.setPadding(dp(4), dp(14), 0, dp(8))
        content.addView(t)
    }

    internal fun card(padDp: Int = 14): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        c.background = rounded(panel, 16, stroke)
        return c
    }

    internal fun button(text: String, filled: Boolean = true, action: () -> Unit): TextView {
        val b = TextView(this)
        b.text = Tr.t(text)
        b.gravity = Gravity.CENTER
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (filled) onAccent() else white)
        b.setPadding(dp(14), dp(12), dp(14), dp(12))
        b.background = if (filled) rounded(accent, 12) else rounded(panel2, 12, stroke)
        b.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
        return b
    }

    internal fun chip(text: String, selected: Boolean, tr: Boolean = true, action: () -> Unit): TextView {
        val c = TextView(this)
        c.text = if (tr) Tr.t(text) else text
        c.textSize = 12.5f
        c.gravity = Gravity.CENTER
        c.setPadding(dp(14), dp(8), dp(14), dp(8))
        c.setTextColor(if (selected) onAccent() else muted)
        if (selected) c.typeface = Typeface.DEFAULT_BOLD
        c.background = if (selected) rounded(accent, 20) else rounded(panel2, 20, stroke)
        c.setOnClickListener { action() }
        return c
    }

    internal fun input(hintText: String): EditText {
        val e = EditText(this)
        e.hint = Tr.t(hintText)
        e.setHintTextColor(muted)
        e.setTextColor(white)
        e.textSize = 14f
        e.setSingleLine(true)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.background = rounded(panel2, 12, stroke)
        return e
    }

    internal fun infoRow(parent: LinearLayout, key: String): TextView {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(6), 0, dp(6))
        val k = label(key, 13f, muted)
        val v = label("-", 13f, white, true)
        v.gravity = Gravity.END
        r.addView(k, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(v, LinearLayout.LayoutParams(WRAP, WRAP))
        parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return v
    }

    internal fun barRow(name: String, valueText: String, frac: Float, color: Int, strong: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val n = label(name, 13f, if (strong) white else muted, strong, false)
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.END
        row.addView(n, LinearLayout.LayoutParams(dp(92), WRAP))
        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        track.background = rounded(panel2, 6)
        val f = frac.coerceIn(0.04f, 1f)
        val bar = View(this)
        bar.background = rounded(color, 6)
        track.addView(bar, LinearLayout.LayoutParams(0, dp(10), f))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f - f))
        val tp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tp.marginStart = dp(8)
        tp.marginEnd = dp(8)
        row.addView(track, tp)
        val vt = label(valueText, 12.5f, white, true)
        vt.gravity = Gravity.END
        vt.minWidth = dp(70)
        row.addView(vt, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    internal fun fmtDur(ms: Long): String {
        val m = ms / 60000L
        val h = m / 60L
        return if (h > 0) "${h} ساعت و ${m % 60} دقیقه" else "${m} دقیقه"
    }

    internal fun mb(bytes: Long): Long = bytes / (1024L * 1024L)

    internal fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1073741824.0)

    internal fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    internal fun copyText(name: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(name, text))
        toast("کپی شد: $text")
    }

    internal fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // ----- system data ------------------------------------------------------

    internal fun ramInfo(): Pair<Long, Long> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Pair(mi.availMem, mi.totalMem)
    }

    internal fun storageInfo(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return Pair(st.availableBlocksLong * st.blockSizeLong, st.blockCountLong * st.blockSizeLong)
    }

    internal fun battery(): BatteryState {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return BatteryState(pct, temp, charging)
    }

    internal fun thermalText(): String {
        if (Build.VERSION.SDK_INT < 29) return "نامشخص"
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                0 -> "عادی ✅"
                1 -> "کمی گرم"
                2 -> "گرم"
                3 -> "خیلی گرم ⚠️"
                4 -> "بحرانی 🔥"
                else -> "اضطراری 🔥"
            }
        } catch (_: Exception) {
            "نامشخص"
        }
    }

    @Suppress("DEPRECATION")
    internal fun refreshRateText(): String = try {
        "${windowManager.defaultDisplay.refreshRate.toInt()} Hz"
    } catch (_: Exception) {
        "نامشخص"
    }

    internal fun networkText(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "بدون اتصال"
            val caps = cm.getNetworkCapabilities(net) ?: return "نامشخص"
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "موبایل"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "شبکه"
            }
            val down = caps.linkDownstreamBandwidthKbps / 1000
            if (down > 0) "$type • ~${down} Mbps" else type
        } catch (_: Exception) {
            "نامشخص"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4711) {
            val pr = pendingDns
            if (resultCode == Activity.RESULT_OK && pr != null) {
                startDnsService(pr)
            } else {
                toast("اجازه‌ی VPN داده نشد؛ بدون آن DNS تغییر نمی‌کند.")
                launchPendingSoon(0)
            }
        } else if (requestCode == 4720) {
            val n = pendingWg
            if (resultCode == Activity.RESULT_OK && n != null) {
                doConnectWg(n)
            } else {
                toast("اجازه‌ی VPN داده نشد؛ بدون آن اتصال ممکن نیست.")
                launchPendingSoon(0)
            }
        } else if (requestCode == 4730) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) importFromUri(uri)
        } else if (requestCode == 4740) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) importV2FromUri(uri)
        }
    }
}
