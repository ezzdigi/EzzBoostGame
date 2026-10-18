package com.ezzdigi.ezzgameboost

import android.Manifest
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

class AppItem(val label: String, val pkg: String, val isGame: Boolean)
class DnsProvider(val name: String, val primary: String, val secondary: String)
class BatteryState(val pct: Int, val temp: Float, val charging: Boolean)

// ---------------------------------------------------------------------------
// Custom views
// ---------------------------------------------------------------------------

class GaugeView(ctx: Context, private val title: String, private val mainColor: Int) : View(ctx) {
    private var shown = 0f
    private var target = 0f
    private var centerText = "0%"
    private var subText = ""
    private var arcColor = Color.WHITE
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(40, 43, 58)
    }
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = mainColor
    }
    private val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(141, 147, 167)
    }
    private val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.rgb(141, 147, 167)
    }

    fun setTarget(frac: Float, center: String, sub: String, c: Int) {
        centerText = center
        subText = sub
        arcColor = c
        target = frac.coerceIn(0f, 1f)
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, target)
        a.duration = 700
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h * 0.95f)
        val sw = size * 0.085f
        trackP.strokeWidth = sw
        arcP.strokeWidth = sw
        arcP.color = arcColor
        val cx = w / 2f
        val cy = h * 0.46f
        val r = size / 2f - sw
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 135f, 270f, false, trackP)
        if (shown > 0.005f) canvas.drawArc(rect, 135f, 270f * shown, false, arcP)
        valP.textSize = size * 0.21f
        val baseY = cy + valP.textSize * 0.15f
        canvas.drawText(centerText, cx, baseY, valP)
        subP.textSize = size * 0.105f
        canvas.drawText(subText, cx, baseY + subP.textSize * 1.4f, subP)
        titleP.textSize = size * 0.1f
        canvas.drawText(title, cx, cy + r * 0.95f, titleP)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

class ChartView(ctx: Context, private val lineColor: Int) : View(ctx) {
    private var pts: List<Int> = emptyList()
    private val gridP = Paint().apply {
        color = Color.rgb(36, 39, 54)
        strokeWidth = 2f
    }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = lineColor
    }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = lineColor
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lossP = Paint().apply {
        color = Color.argb(110, 255, 55, 70)
        strokeWidth = 6f
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(141, 147, 167)
        textSize = 26f
    }

    fun setPoints(p: List<Int>) {
        pts = ArrayList(p)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = Color.red(lineColor)
        val g = Color.green(lineColor)
        val b = Color.blue(lineColor)
        fillP.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(90, r, g, b), Color.argb(0, r, g, b), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridP)
        }
        if (pts.isEmpty()) {
            canvas.drawText("—", w / 2f - 8f, h / 2f, txtP)
            return
        }
        val maxV = maxOf(60, pts.maxOrNull() ?: 60).toFloat() * 1.15f
        val n = pts.size
        val stepX = if (n > 1) (w - 2 * pad) / (n - 1) else 0f
        val path = Path()
        val area = Path()
        var started = false
        var lastX = pad
        var lastY = h
        for (i in 0 until n) {
            val x = pad + stepX * i
            val v = pts[i]
            if (v < 0) {
                canvas.drawLine(x, 0f, x, h, lossP)
                continue
            }
            val y = h - pad - (v / maxV) * (h - 2 * pad)
            if (!started) {
                path.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
        if (started) {
            area.lineTo(lastX, h)
            area.close()
            canvas.drawPath(area, fillP)
            canvas.drawPath(path, lineP)
            canvas.drawCircle(lastX, lastY, 9f, dotP)
        }
        canvas.drawText("${maxV.toInt()} ms", 8f, 28f, txtP)
    }
}

class BoostView(
    ctx: Context,
    private val accentColor: Int,
    private val panelColor: Int,
    private val textColor: Int
) : View(ctx) {
    var running = false
        private set
    private var angle = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val dimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
    }
    private val faceP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
    }
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = textColor
    }

    fun start() {
        running = true
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 360f)
        a.duration = 900
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    fun stop() {
        running = false
        anim?.cancel()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = minOf(w, h) / 2f
        faceP.shader = RadialGradient(
            w / 2f, h / 2f, r,
            Color.argb(110, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            panelColor, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val sw = minOf(width, height) * 0.05f
        val r = minOf(width, height) / 2f - sw
        ringP.strokeWidth = sw
        dimP.strokeWidth = sw
        canvas.drawCircle(cx, cy, r - sw, faceP)
        canvas.drawCircle(cx, cy, r, dimP)
        if (running) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, angle, 110f, false, ringP)
        } else {
            canvas.drawCircle(cx, cy, r, ringP)
        }
        iconP.textSize = r * 0.62f
        canvas.drawText("⚡", cx, cy + r * 0.05f, iconP)
        capP.textSize = r * 0.27f
        canvas.drawText(if (running) "CLEANING" else "BOOST", cx, cy + r * 0.52f, capP)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

class LogoView(ctx: Context, private val ringColor: Int, private val bgColor: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val dpad = Path()
    private val bolt = Path()

    init {
        body.moveTo(36f, 46f)
        body.lineTo(72f, 46f)
        body.quadTo(80f, 46f, 82f, 58f)
        body.lineTo(83.5f, 68f)
        body.quadTo(84.5f, 76f, 78f, 76f)
        body.quadTo(73f, 76f, 70f, 71f)
        body.lineTo(67f, 66f)
        body.lineTo(41f, 66f)
        body.lineTo(38f, 71f)
        body.quadTo(35f, 76f, 30f, 76f)
        body.quadTo(23.5f, 76f, 24.5f, 68f)
        body.lineTo(26f, 58f)
        body.quadTo(28f, 46f, 36f, 46f)
        body.close()

        dpad.moveTo(37f, 52f)
        dpad.lineTo(40f, 52f)
        dpad.lineTo(40f, 56f)
        dpad.lineTo(44f, 56f)
        dpad.lineTo(44f, 59f)
        dpad.lineTo(40f, 59f)
        dpad.lineTo(40f, 63f)
        dpad.lineTo(37f, 63f)
        dpad.lineTo(37f, 59f)
        dpad.lineTo(33f, 59f)
        dpad.lineTo(33f, 56f)
        dpad.lineTo(37f, 56f)
        dpad.close()

        bolt.moveTo(57f, 27f)
        bolt.lineTo(48f, 39f)
        bolt.lineTo(53f, 39f)
        bolt.lineTo(50f, 47f)
        bolt.lineTo(60f, 34f)
        bolt.lineTo(55f, 34f)
        bolt.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 108f
        canvas.save()
        canvas.scale(s, s)
        p.style = Paint.Style.FILL
        p.color = bgColor
        canvas.drawRoundRect(0f, 0f, 108f, 108f, 26f, 26f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.5f
        p.color = ringColor
        canvas.drawCircle(54f, 54f, 38f, p)
        p.style = Paint.Style.FILL
        canvas.save()
        canvas.scale(0.88f, 0.88f, 54f, 61f)
        p.color = Color.rgb(242, 243, 247)
        canvas.drawPath(body, p)
        p.color = bgColor
        canvas.drawPath(dpad, p)
        p.color = ringColor
        canvas.drawCircle(68f, 54f, 2.6f, p)
        canvas.drawCircle(74f, 59.5f, 2.6f, p)
        canvas.restore()
        p.color = Color.rgb(255, 176, 32)
        canvas.drawPath(bolt, p)
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())

    private val bg0 = Color.rgb(8, 9, 14)
    private val panel = Color.rgb(20, 22, 31)
    private val panel2 = Color.rgb(29, 32, 45)
    private val stroke = Color.rgb(44, 48, 66)
    private val white = Color.rgb(242, 243, 247)
    private val muted = Color.rgb(141, 147, 167)
    private val good = Color.rgb(45, 255, 138)
    private val warn = Color.rgb(255, 176, 32)
    private val bad = Color.rgb(255, 55, 70)

    private val accents = intArrayOf(
        Color.rgb(255, 55, 70),
        Color.rgb(25, 227, 255),
        Color.rgb(168, 85, 247),
        Color.rgb(45, 255, 138),
        Color.rgb(255, 176, 32)
    )
    private val accentNames = arrayOf("قرمز", "فیروزه‌ای", "بنفش", "سبز", "نارنجی")
    private var accent = accents[0]

    private val tabIcons = arrayOf("🏠", "🎮", "📡", "🛠", "📊")
    private val tabNames = arrayOf("خانه", "بازی‌ها", "شبکه", "ابزار", "آمار")

    private val dnsProviders = listOf(
        DnsProvider("Radar Game • رادار گیم", "10.202.10.10", "10.202.10.11"),
        DnsProvider("Electro • الکترو", "78.157.42.100", "78.157.42.101"),
        DnsProvider("Shecan • شکن", "178.22.122.100", "185.51.200.2"),
        DnsProvider("Begzar • بگذر", "185.55.226.26", "185.55.225.25"),
        DnsProvider("Shelter • شلتر", "94.156.65.130", "91.92.249.227"),
        DnsProvider("Cloudflare", "1.1.1.1", "1.0.0.1"),
        DnsProvider("Google", "8.8.8.8", "8.8.4.4"),
        DnsProvider("Quad9", "9.9.9.9", "149.112.112.112"),
        DnsProvider("AdGuard", "94.140.14.14", "94.140.15.15")
    )

    private val pingTargets = arrayOf(
        Triple("Cloudflare", "1.1.1.1", 443),
        Triple("Google", "8.8.8.8", 443),
        Triple("Quad9", "9.9.9.9", 443),
        Triple("AdGuard", "94.140.14.14", 443),
        Triple("OpenDNS", "208.67.222.222", 443)
    )

    private var tab = 0
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabBar: LinearLayout
    private var homeRefresh: (() -> Unit)? = null
    private var netRefresh: (() -> Unit)? = null
    private var pendingDns: DnsProvider? = null
    private val dnsPing = HashMap<String, Int>()
    private var dnsBenchRunning = false

    @Volatile
    private var pingToken = 0
    private var netTarget = 0

    private var appsCache: List<AppItem>? = null
    private var appsLoading = false
    private var gamesFilter = 0
    private var gamesQuery = ""

    private val ticker = object : Runnable {
        override fun run() {
            homeRefresh?.invoke()
            netRefresh?.invoke()
            handler.postDelayed(this, 2000)
        }
    }

    // ----- lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
        val idx = prefs.getInt("accent", 0)
        accent = accents[idx.coerceIn(0, accents.size - 1)]
        buildShell()
        applyWindowToggles()
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        val recorded = finalizeSession()
        if (recorded && (tab == 0 || tab == 1 || tab == 4)) showTab(tab)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    // ----- shell ----------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun buildShell() {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        val ver = TextView(this).apply {
            text = "v2.0"
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

    private fun refreshTabBar() {
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
                text = tabNames[i]
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

    private fun showTab(i: Int) {
        tab = i
        homeRefresh = null
        netRefresh = null
        pingToken++
        content.removeAllViews()
        refreshTabBar()
        when (i) {
            0 -> buildHome()
            1 -> buildGames()
            2 -> buildNetwork()
            3 -> buildTools()
            else -> buildStats()
        }
        scroll.scrollTo(0, 0)
    }

    // ----- helpers ---------------------------------------------------------

    private fun runBg(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    private fun ui(block: () -> Unit) {
        if (!isDestroyed && !isFinishing) runOnUiThread { block() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun accentAlpha(a: Int): Int =
        Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))

    private fun onAccent(): Int {
        val lum = 0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)
        return if (lum > 150) Color.rgb(8, 9, 14) else Color.WHITE
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    private fun heroBg(): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accentAlpha(70), panel)
        )
        d.cornerRadius = dp(18).toFloat()
        d.setStroke(dp(1), accentAlpha(140))
        return d
    }

    private fun vlp(topDp: Int = 0, bottomDp: Int = 10): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(topDp)
        p.bottomMargin = dp(bottomDp)
        return p
    }

    private fun label(text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.setLineSpacing(dp(2).toFloat(), 1f)
        return t
    }

    private fun section(text: String) {
        val t = label(text, 12f, accent, true)
        t.letterSpacing = 0.12f
        t.setPadding(dp(4), dp(14), 0, dp(8))
        content.addView(t)
    }

    private fun card(padDp: Int = 14): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        c.background = rounded(panel, 16, stroke)
        return c
    }

    private fun button(text: String, filled: Boolean = true, action: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
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

    private fun chip(text: String, selected: Boolean, action: () -> Unit): TextView {
        val c = TextView(this)
        c.text = text
        c.textSize = 12.5f
        c.gravity = Gravity.CENTER
        c.setPadding(dp(14), dp(8), dp(14), dp(8))
        c.setTextColor(if (selected) onAccent() else muted)
        if (selected) c.typeface = Typeface.DEFAULT_BOLD
        c.background = if (selected) rounded(accent, 20) else rounded(panel2, 20, stroke)
        c.setOnClickListener { action() }
        return c
    }

    private fun input(hintText: String): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setHintTextColor(muted)
        e.setTextColor(white)
        e.textSize = 14f
        e.setSingleLine(true)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.background = rounded(panel2, 12, stroke)
        return e
    }

    private fun infoRow(parent: LinearLayout, key: String): TextView {
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

    private fun barRow(name: String, valueText: String, frac: Float, color: Int, strong: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val n = label(name, 13f, if (strong) white else muted, strong)
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

    private fun fmtDur(ms: Long): String {
        val m = ms / 60000L
        val h = m / 60L
        return if (h > 0) "${h} ساعت و ${m % 60} دقیقه" else "${m} دقیقه"
    }

    private fun mb(bytes: Long): Long = bytes / (1024L * 1024L)

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1073741824.0)

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun copyText(name: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(name, text))
        toast("کپی شد: $text")
    }

    private fun openSettings(action: String) {
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

    private fun ramInfo(): Pair<Long, Long> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Pair(mi.availMem, mi.totalMem)
    }

    private fun storageInfo(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return Pair(st.availableBlocksLong * st.blockSizeLong, st.blockCountLong * st.blockSizeLong)
    }

    private fun battery(): BatteryState {
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

    private fun thermalText(): String {
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
    private fun refreshRateText(): String = try {
        "${windowManager.defaultDisplay.refreshRate.toInt()} Hz"
    } catch (_: Exception) {
        "نامشخص"
    }

    private fun networkText(): String {
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

    // ----- HOME -------------------------------------------------------------

    private fun buildHome() {
        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(dp(16), dp(16), dp(16), dp(16))
        hero.background = heroBg()
        hero.addView(label("آماده نبرد؟ 🎮", 19f, white, true))
        val heroSub = label("", 12.5f, muted)
        hero.addView(heroSub, vlp(4, 0))
        content.addView(hero, vlp(4, 4))

        section("LIVE STATS")
        val gCard = card(8)
        val gRow = LinearLayout(this)
        gRow.orientation = LinearLayout.HORIZONTAL
        val ramG = GaugeView(this, "RAM", white)
        val batG = GaugeView(this, "BATTERY", white)
        val stoG = GaugeView(this, "STORAGE", white)
        for (g in arrayOf(ramG, batG, stoG)) {
            gRow.addView(g, LinearLayout.LayoutParams(0, dp(132), 1f))
        }
        gCard.addView(gRow)
        content.addView(gCard, vlp(0, 4))

        section("BOOST CENTER")
        val bCard = card()
        bCard.gravity = Gravity.CENTER_HORIZONTAL
        val boost = BoostView(this, accent, panel, white)
        val bp = LinearLayout.LayoutParams(dp(170), dp(170))
        bp.gravity = Gravity.CENTER_HORIZONTAL
        bCard.addView(boost, bp)
        val boostMsg = label(
            "با یک لمس، پردازش‌های پس‌زمینه‌ی برنامه‌ها بسته می‌شوند تا RAM آزاد بیشتری برای بازی بماند.",
            12.5f, muted
        )
        boostMsg.gravity = Gravity.CENTER
        val mp = LinearLayout.LayoutParams(MATCH, WRAP)
        mp.topMargin = dp(10)
        bCard.addView(boostMsg, mp)
        boost.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runBoost(boost, boostMsg)
        }
        content.addView(bCard, vlp(0, 4))

        section("QUICK LAUNCH")
        val favs = favoriteSet()
        if (favs.isEmpty()) {
            val hint = card()
            hint.addView(label("هنوز بازی مورد علاقه‌ای نداری. در تب «بازی‌ها» روی ☆ بزن تا اینجا میان‌بر بسازی.", 12.5f, muted))
            content.addView(hint, vlp(0, 4))
        } else {
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (p in favs) {
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.marginEnd = dp(8)
                row.addView(appTile(p), lp)
            }
            hs.addView(row)
            content.addView(hs, vlp(0, 4))
        }

        section("SYSTEM")
        val info = card()
        val vDevice = infoRow(info, "دستگاه")
        val vAndroid = infoRow(info, "اندروید")
        val vCpu = infoRow(info, "هسته‌های CPU")
        val vRefresh = infoRow(info, "نرخ نوسازی صفحه")
        val vNet = infoRow(info, "شبکه")
        val vDns = infoRow(info, "DNS")
        val vThermal = infoRow(info, "وضعیت دمایی")
        val vTemp = infoRow(info, "دمای باتری")
        vDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        vAndroid.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        vCpu.text = Runtime.getRuntime().availableProcessors().toString()
        content.addView(info, vlp(0, 4))

        val refresh: () -> Unit = {
            val ram = ramInfo()
            val used = ram.second - ram.first
            val rf = used.toFloat() / ram.second.toFloat()
            val rc = if (rf > 0.85f) bad else if (rf > 0.7f) warn else accent
            ramG.setTarget(rf, "${(rf * 100).toInt()}%", "${gb(used)}/${gb(ram.second)}G", rc)

            val bat = battery()
            val bc = if (bat.charging) good else if (bat.pct <= 20) bad else if (bat.pct <= 40) warn else accent
            batG.setTarget(
                bat.pct / 100f, "${bat.pct}%",
                if (bat.charging) "⚡ شارژ" else "${bat.temp.toInt()}°C", bc
            )

            val st = storageInfo()
            val su = st.second - st.first
            val sf = su.toFloat() / st.second.toFloat()
            val sc = if (sf > 0.92f) bad else if (sf > 0.8f) warn else accent
            stoG.setTarget(sf, "${(sf * 100).toInt()}%", "${gb(st.first)}G آزاد", sc)

            val net = networkText()
            val thermal = thermalText()
            val hz = refreshRateText()
            vRefresh.text = hz
            vNet.text = net
            vDns.text = if (DnsVpnService.running) DnsVpnService.activeName else "پیش‌فرض سیستم"
            vThermal.text = thermal
            vTemp.text = String.format(Locale.US, "%.1f °C", bat.temp)
            heroSub.text = "$net  •  $hz  •  $thermal"
        }
        refresh()
        homeRefresh = refresh
    }

    private fun appTile(pkg: String): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setPadding(dp(6), dp(8), dp(6), dp(8))
        t.background = rounded(panel, 14, stroke)
        val iv = ImageView(this)
        t.addView(iv, LinearLayout.LayoutParams(dp(50), dp(50)))
        runBg {
            try {
                val d = packageManager.getApplicationIcon(pkg)
                ui { iv.setImageDrawable(d) }
            } catch (_: Exception) {
            }
        }
        val name = label(appLabel(pkg), 11f, white)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.gravity = Gravity.CENTER
        val np = LinearLayout.LayoutParams(dp(64), WRAP)
        np.topMargin = dp(4)
        t.addView(name, np)
        t.setOnClickListener { launchGame(pkg) }
        return t
    }

    private fun runBoost(view: BoostView, msg: TextView) {
        if (view.running) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = ramInfo().first
        view.start()
        msg.setTextColor(muted)
        msg.text = "در حال پاک‌سازی پردازش‌های پس‌زمینه…"
        runBg {
            var count = 0
            try {
                val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val pkgs = packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != packageName }
                for (p in pkgs) {
                    try {
                        am.killBackgroundProcesses(p)
                        count++
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(1800)
            } catch (_: InterruptedException) {
            }
            val after = ramInfo().first
            ui {
                view.stop()
                val diff = mb(after) - mb(before)
                msg.setTextColor(white)
                msg.text = if (diff > 0) {
                    "✅ ${diff} MB آزاد شد • ${count} برنامه بررسی شد.\nRAM آزاد: ${mb(after)} MB"
                } else {
                    "✅ ${count} برنامه بررسی شد؛ تغییر محسوسی در RAM آزاد دیده نشد.\nRAM آزاد: ${mb(after)} MB\n(اندروید معمولاً خودش RAM را خوب مدیریت می‌کند.)"
                }
                homeRefresh?.invoke()
            }
        }
    }

    // ----- GAMES ------------------------------------------------------------

    private fun buildGames() {
        section("GAME LIBRARY")
        val search = input("جستجوی بازی یا برنامه…")
        search.setText(gamesQuery)
        content.addView(search, vlp(0, 8))

        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL

        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("بازی‌ها", "علاقه‌مندی‌ها", "همه")
        fun fillChips() {
            chipRow.removeAllViews()
            for (i in names.indices) {
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.marginEnd = dp(8)
                chipRow.addView(chip(names[i], gamesFilter == i) {
                    gamesFilter = i
                    fillChips()
                    renderGames(listBox)
                }, lp)
            }
        }
        fillChips()
        content.addView(chipRow, vlp(0, 10))
        content.addView(listBox)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                gamesQuery = s?.toString() ?: ""
                renderGames(listBox)
            }
        })
        renderGames(listBox)
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = ArrayList<AppItem>()
        val seen = HashSet<String>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            val ai = ri.activityInfo.applicationInfo
            @Suppress("DEPRECATION")
            val game = ai.category == ApplicationInfo.CATEGORY_GAME ||
                (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            result.add(AppItem(ri.loadLabel(pm).toString(), pkg, game))
        }
        return result
    }

    private fun renderGames(box: LinearLayout) {
        val apps = appsCache
        if (apps == null) {
            box.removeAllViews()
            box.addView(label("در حال بارگذاری فهرست برنامه‌ها…", 13f, muted))
            if (!appsLoading) {
                appsLoading = true
                runBg {
                    val loaded = loadApps()
                    ui {
                        appsCache = loaded
                        appsLoading = false
                        renderGames(box)
                    }
                }
            }
            return
        }
        val favs = favoriteSet()
        val q = gamesQuery.trim().lowercase()
        val filtered = apps.filter {
            (q.isEmpty() || it.label.lowercase().contains(q)) &&
                when (gamesFilter) {
                    0 -> it.isGame
                    1 -> favs.contains(it.pkg)
                    else -> true
                }
        }
        val list = filtered.sortedWith(
            compareByDescending<AppItem> { favs.contains(it.pkg) }.thenBy { it.label.lowercase() }
        )
        box.removeAllViews()
        if (list.isEmpty()) {
            val msg = when {
                gamesFilter == 0 && q.isEmpty() ->
                    "بازی‌ای شناسایی نشد. روی «همه» بزن و بازی‌هایت را با ☆ به علاقه‌مندی‌ها اضافه کن."
                gamesFilter == 1 && q.isEmpty() -> "فهرست علاقه‌مندی‌ها خالی است."
                else -> "موردی پیدا نشد."
            }
            box.addView(label(msg, 13f, muted))
            return
        }
        val shown = list.take(60)
        for (item in shown) {
            box.addView(appRow(item, favs.contains(item.pkg), box), vlp(0, 8))
        }
        if (list.size > shown.size) {
            box.addView(label("${list.size - shown.size} مورد دیگر… برای پیداکردن، جستجو کن.", 12f, muted))
        }
    }

    private fun appRow(item: AppItem, fav: Boolean, box: LinearLayout): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        row.background = rounded(panel, 14, if (fav) accentAlpha(130) else stroke)

        val icon = ImageView(this)
        row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
        runBg {
            try {
                val d = packageManager.getApplicationIcon(item.pkg)
                ui { icon.setImageDrawable(d) }
            } catch (_: Exception) {
            }
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        val nm = label(item.label, 14.5f, white, true)
        nm.maxLines = 1
        nm.ellipsize = TextUtils.TruncateAt.END
        col.addView(nm)
        val pt = prefs.getLong("pt_" + item.pkg, 0L)
        val lc = prefs.getInt("lc_" + item.pkg, 0)
        col.addView(label(if (lc == 0) "هنوز اجرا نشده" else "${fmtDur(pt)} • ${lc} بار اجرا", 11.5f, muted))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val star = TextView(this)
        star.text = if (fav) "★" else "☆"
        star.textSize = 24f
        star.setTextColor(if (fav) warn else muted)
        star.setPadding(dp(6), 0, dp(10), 0)
        star.setOnClickListener {
            toggleFavorite(item.pkg)
            renderGames(box)
        }
        row.addView(star)

        val play = button("اجرا") { launchGame(item.pkg) }
        play.textSize = 13f
        play.setPadding(dp(16), dp(9), dp(16), dp(9))
        row.addView(play)
        return row
    }

    private fun favoriteSet(): Set<String> {
        val s = prefs.getStringSet("fav", null)
        val out = HashSet<String>()
        if (s != null) out.addAll(s)
        return out
    }

    private fun toggleFavorite(pkg: String) {
        val s = HashSet<String>(favoriteSet())
        if (!s.add(pkg)) s.remove(pkg)
        prefs.edit().putStringSet("fav", s).apply()
    }

    private fun launchGame(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast("اجرای این برنامه ممکن نیست.")
            return
        }
        prefs.edit()
            .putString("pendingPkg", pkg)
            .putLong("pendingStart", System.currentTimeMillis())
            .putInt("lc_$pkg", prefs.getInt("lc_$pkg", 0) + 1)
            .apply()
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("اجرای این برنامه ممکن نیست.")
        }
    }

    private fun finalizeSession(): Boolean {
        val pkg = prefs.getString("pendingPkg", null) ?: return false
        val start = prefs.getLong("pendingStart", 0L)
        val dur = System.currentTimeMillis() - start
        val e = prefs.edit().remove("pendingPkg").remove("pendingStart")
        var recorded = false
        if (start > 0L && dur >= 20000L && dur <= 43200000L) {
            e.putLong("pt_$pkg", prefs.getLong("pt_$pkg", 0L) + dur)
            recorded = true
        }
        e.apply()
        return recorded
    }

    // ----- NETWORK ----------------------------------------------------------

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Int {
        return try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - t0) / 1000000L).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private fun resolveTarget(custom: String): Pair<String, Int> {
        val c = custom.trim()
        val preset = pingTargets[netTarget]
        if (c.isEmpty()) return Pair(preset.second, preset.third)
        val parts = c.split(":")
        val host = parts[0].trim()
        if (host.isEmpty()) return Pair(preset.second, preset.third)
        val port = if (parts.size > 1) (parts[1].trim().toIntOrNull() ?: 443) else 443
        return Pair(host, port.coerceIn(1, 65535))
    }

    private fun pingColor(ms: Int): Int = when {
        ms < 0 -> bad
        ms < 60 -> good
        ms < 120 -> warn
        else -> bad
    }

    private fun updatePingUi(
        samples: List<Int>, big: TextView, verdict: TextView, chart: ChartView, stats: TextView
    ) {
        val ok = samples.filter { it >= 0 }
        val last = samples[samples.size - 1]
        if (last < 0) {
            big.text = "✕"
            big.setTextColor(bad)
        } else {
            big.text = "$last ms"
            big.setTextColor(pingColor(last))
        }
        chart.setPoints(samples.takeLast(30))
        if (ok.isEmpty()) {
            verdict.text = "اتصال برقرار نشد"
            verdict.setTextColor(bad)
            stats.text = "Loss 100%"
            return
        }
        val avg = ok.average().toInt()
        val mn = ok.minOrNull() ?: 0
        val mx = ok.maxOrNull() ?: 0
        val jit = if (ok.size > 1) ok.zipWithNext { a, b -> Math.abs(a - b) }.average().toInt() else 0
        val loss = (samples.size - ok.size) * 100 / samples.size
        val q = when {
            avg < 40 -> "عالی 🔥"
            avg < 80 -> "خوب 👍"
            avg < 150 -> "متوسط"
            else -> "ضعیف ⚠️"
        }
        verdict.text = "کیفیت اتصال: $q"
        verdict.setTextColor(pingColor(avg))
        stats.text = "Min $mn  •  Avg $avg  •  Max $mx  •  Jitter $jit  •  Loss $loss%"
    }

    private fun buildNetwork() {
        buildDnsChanger()
        section("PING LAB")
        val pingCard = card()
        pingCard.addView(label("تست تأخیر اتصال TCP", 15f, white, true))
        pingCard.addView(label("زمان برقراری اتصال TCP تا سرور انتخابی؛ پینگ ICMP یا پینگ داخل بازی نیست.", 12f, muted))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        hs.addView(chipRow)
        fun fillTargets() {
            chipRow.removeAllViews()
            for (i in pingTargets.indices) {
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.marginEnd = dp(8)
                chipRow.addView(chip(pingTargets[i].first, netTarget == i) {
                    netTarget = i
                    fillTargets()
                }, lp)
            }
        }
        fillTargets()
        pingCard.addView(hs, vlp(10, 8))

        val custom = input("سرور دلخواه (اختیاری)  host:port")
        pingCard.addView(custom, vlp(0, 10))

        val big = label("— ms", 38f, accent, true)
        big.gravity = Gravity.CENTER
        val verdict = label("آماده‌ی تست", 13f, muted)
        verdict.gravity = Gravity.CENTER
        val chart = ChartView(this, accent)
        val stats = label("", 12f, white)
        stats.gravity = Gravity.CENTER
        pingCard.addView(big, LinearLayout.LayoutParams(MATCH, WRAP))
        pingCard.addView(verdict, LinearLayout.LayoutParams(MATCH, WRAP))
        val cp = LinearLayout.LayoutParams(MATCH, dp(110))
        cp.topMargin = dp(10)
        cp.bottomMargin = dp(8)
        pingCard.addView(chart, cp)
        pingCard.addView(stats, vlp(0, 10))

        var running = false
        val startBtn = button("شروع تست پینگ") {}
        startBtn.setOnClickListener {
            if (running) {
                pingToken++
                running = false
                startBtn.text = "شروع تست پینگ"
                return@setOnClickListener
            }
            val target = resolveTarget(custom.text.toString())
            val host = target.first
            val port = target.second
            running = true
            startBtn.text = "توقف"
            pingToken++
            val token = pingToken
            val samples = ArrayList<Int>()
            chart.setPoints(emptyList())
            runBg {
                var n = 0
                while (n < 25 && token == pingToken) {
                    val ms = tcpPing(host, port, 2000)
                    n++
                    ui {
                        if (token == pingToken) {
                            samples.add(ms)
                            updatePingUi(samples, big, verdict, chart, stats)
                        }
                    }
                    try {
                        Thread.sleep(350)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                ui {
                    if (token == pingToken) {
                        running = false
                        startBtn.text = "شروع تست پینگ"
                    }
                }
            }
        }
        pingCard.addView(startBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(pingCard, vlp(0, 4))

        section("PRIVATE DNS (DoT)")
        val dnsCard = card()
        dnsCard.addView(label("DNS خصوصی اندروید (DNS-over-TLS)", 15f, white, true))
        dnsCard.addView(
            label(
                "روش دوم: DNS-over-TLS داخل تنظیمات خود اندروید. نام میزبان را کپی کن و در Private DNS وارد کن. اگر اتصال قطع شد، گزینه را روی Automatic برگردان.",
                12f, muted
            ), vlp(2, 6)
        )
        val dnsOptions = listOf(
            Pair("Cloudflare • سریع و عمومی", "one.one.one.one"),
            Pair("Google Public DNS • عمومی", "dns.google"),
            Pair("Quad9 • مسدودسازی دامنه‌های مخرب", "dns.quad9.net"),
            Pair("AdGuard DNS • فیلتر تبلیغات", "dns.adguard-dns.com")
        )
        for (opt in dnsOptions) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(6), 0, dp(6))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(label(opt.first, 13f, white, true))
            col.addView(label(opt.second, 12f, muted))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val cb = button("کپی", false) { copyText("Private DNS hostname", opt.second) }
            cb.textSize = 12.5f
            cb.setPadding(dp(16), dp(8), dp(16), dp(8))
            r.addView(cb)
            dnsCard.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val openDns = button("باز کردن تنظیمات Private DNS") {
            try {
                startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
            } catch (_: Exception) {
                openSettings(Settings.ACTION_WIRELESS_SETTINGS)
            }
        }
        dnsCard.addView(openDns, vlp(8, 0))
        content.addView(dnsCard, vlp(0, 4))
    }

    private fun selectedDns(): DnsProvider =
        dnsProviders[prefs.getInt("dnsSel", 0).coerceIn(0, dnsProviders.size - 1)]

    private fun buildDnsChanger() {
        section("DNS CHANGER")
        val dCard = card()
        val statusTitle = label("", 15f, white, true)
        val statusSub = label("", 12f, muted)
        dCard.addView(statusTitle)
        dCard.addView(statusSub, vlp(2, 10))
        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        dCard.addView(listBox)
        val connectBtn = button("اتصال") {}
        dCard.addView(connectBtn, vlp(10, 8))
        val benchBtn = button("تست سرعت همه‌ی DNSها", false) {}
        dCard.addView(benchBtn, vlp(0, 8))
        dCard.addView(
            label(
                "اتصال با یک VPN محلی انجام می‌شود که فقط درخواست‌های DNS را به سرور انتخابی می‌فرستد؛ بقیه‌ی ترافیک از مسیر عادی می‌رود. اندروید هم‌زمان فقط یک VPN را نگه می‌دارد، پس VPN دیگر قطع می‌شود. اگر Private DNS روی حالت hostname است، اول آن را Off یا Automatic کن.",
                11.5f, muted
            )
        )
        content.addView(dCard, vlp(0, 4))

        var lastKey = ""
        fun render(force: Boolean) {
            val running = DnsVpnService.running
            val active = DnsVpnService.activeName
            val sel = selectedDns()
            statusTitle.text = if (running) "🟢 متصل: $active" else "⚪ قطع (DNS پیش‌فرض سیستم)"
            statusTitle.setTextColor(if (running) good else white)
            statusSub.text = if (running) {
                "درخواست پاسخ‌داده‌شده: ${DnsVpnService.queryCount.get()} • ناموفق: ${DnsVpnService.failCount.get()}"
            } else {
                "یک DNS انتخاب کن و دکمه‌ی اتصال را بزن."
            }
            connectBtn.text = if (running && active == sel.name) {
                "قطع اتصال"
            } else if (running) {
                "تغییر به ${sel.name}"
            } else {
                "اتصال به ${sel.name}"
            }
            val key = "$running|$active|${sel.name}|${dnsPing.size}|$dnsBenchRunning"
            if (!force && key == lastKey) return
            lastKey = key
            listBox.removeAllViews()
            for (i in dnsProviders.indices) {
                val pr = dnsProviders[i]
                val isSel = pr.name == sel.name
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                row.background = rounded(if (isSel) accentAlpha(35) else panel2, 12, if (isSel) accent else stroke)
                val radio = label(if (isSel) "◉" else "○", 18f, if (isSel) accent else muted)
                radio.setPadding(0, 0, dp(10), 0)
                row.addView(radio)
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                val title = if (running && pr.name == active) "${pr.name}  ✓" else pr.name
                col.addView(label(title, 13.5f, white, true))
                col.addView(label("${pr.primary}  •  ${pr.secondary}", 11.5f, muted))
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                val ms = dnsPing[pr.name]
                val msText = if (ms == null) "" else if (ms < 0) "✕" else "$ms ms"
                val msColor = if (ms == null) muted else if (ms < 0) bad else if (ms < 60) good else if (ms < 120) warn else bad
                row.addView(label(msText, 12.5f, msColor, true))
                row.setOnClickListener {
                    prefs.edit().putInt("dnsSel", i).apply()
                    render(true)
                }
                listBox.addView(row, vlp(0, 6))
            }
        }

        connectBtn.setOnClickListener {
            val sel = selectedDns()
            if (DnsVpnService.running && DnsVpnService.activeName == sel.name) {
                stopDns()
            } else {
                connectDns(sel)
            }
            handler.postDelayed({ netRefresh?.invoke() }, 800)
        }
        benchBtn.setOnClickListener { runDnsBench(benchBtn) { render(true) } }

        netRefresh = { render(false) }
        render(true)
    }

    private fun buildDnsQuery(name: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8)
        out.write(id and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0)
        out.write(1)
        for (i in 0 until 6) out.write(0)
        for (part in name.split(".")) {
            val b = part.toByteArray()
            out.write(b.size)
            out.write(b, 0, b.size)
        }
        out.write(0)
        out.write(0)
        out.write(1)
        out.write(0)
        out.write(1)
        return out.toByteArray()
    }

    private fun dnsPingMs(ip: String, timeoutMs: Int): Int {
        val sock = try {
            DatagramSocket()
        } catch (_: Exception) {
            return -1
        }
        try {
            val q = buildDnsQuery("example.com", 0x1A2B)
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(ip)
            val t0 = System.nanoTime()
            sock.send(DatagramPacket(q, q.size, addr, 53))
            val buf = ByteArray(1500)
            val rp = DatagramPacket(buf, buf.size)
            sock.receive(rp)
            val ms = ((System.nanoTime() - t0) / 1000000L).toInt()
            val okId = (buf[0].toInt() and 0xFF) == 0x1A && (buf[1].toInt() and 0xFF) == 0x2B
            return if (rp.length >= 12 && okId) ms else -1
        } catch (_: Exception) {
            return -1
        } finally {
            sock.close()
        }
    }

    private fun runDnsBench(btn: TextView, onUpdate: () -> Unit) {
        if (dnsBenchRunning) return
        dnsBenchRunning = true
        dnsPing.clear()
        btn.text = "در حال تست…"
        onUpdate()
        runBg {
            for (pr in dnsProviders) {
                val vals = ArrayList<Int>()
                for (k in 0 until 3) {
                    val ms = dnsPingMs(pr.primary, 1500)
                    if (ms >= 0) vals.add(ms)
                }
                val avg = if (vals.isEmpty()) -1 else vals.average().toInt()
                ui {
                    dnsPing[pr.name] = avg
                    onUpdate()
                }
            }
            ui {
                dnsBenchRunning = false
                btn.text = "تست سرعت همه‌ی DNSها"
                onUpdate()
            }
        }
    }

    private fun connectDns(pr: DnsProvider) {
        pendingDns = pr
        val intent = VpnService.prepare(this)
        if (intent != null) {
            try {
                startActivityForResult(intent, 4711)
            } catch (_: Exception) {
                toast("این دستگاه از VPN محلی پشتیبانی نمی‌کند.")
            }
        } else {
            startDnsService(pr)
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
            }
        }
    }

    private fun startDnsService(pr: DnsProvider) {
        val i = Intent(this, DnsVpnService::class.java)
        i.action = DnsVpnService.ACTION_START
        i.putExtra("primary", pr.primary)
        i.putExtra("secondary", pr.secondary)
        i.putExtra("name", pr.name)
        try {
            startService(i)
        } catch (_: Exception) {
            toast("شروع سرویس DNS ناموفق بود.")
            return
        }
        val idx = dnsProviders.indexOf(pr)
        if (idx >= 0) prefs.edit().putInt("dnsSel", idx).apply()
        toast("در حال اتصال به ${pr.name}…")
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4712)
        }
        handler.postDelayed({
            netRefresh?.invoke()
            homeRefresh?.invoke()
        }, 900)
    }

    private fun stopDns() {
        try {
            stopService(Intent(this, DnsVpnService::class.java))
        } catch (_: Exception) {
        }
        DnsVpnService.running = false
        DnsVpnService.activeName = ""
        toast("DNS به حالت پیش‌فرض برگشت.")
    }

    // ----- TOOLS ------------------------------------------------------------

    private fun applyWindowToggles() {
        if (prefs.getBoolean("keepOn", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val wlp = window.attributes
        wlp.screenBrightness = if (prefs.getBoolean("maxBright", false)) {
            1f
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = wlp
    }

    private fun toggleRow(parent: LinearLayout, title: String, desc: String, key: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 14f, white, true))
        col.addView(label(desc, 11.5f, muted))
        val pill = TextView(this)
        pill.textSize = 12.5f
        pill.typeface = Typeface.DEFAULT_BOLD
        pill.gravity = Gravity.CENTER
        pill.setPadding(dp(16), dp(8), dp(16), dp(8))
        fun paint() {
            val on = prefs.getBoolean(key, false)
            pill.text = if (on) "روشن" else "خاموش"
            pill.setTextColor(if (on) onAccent() else muted)
            pill.background = if (on) rounded(accent, 20) else rounded(panel2, 20, stroke)
        }
        paint()
        pill.setOnClickListener {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply()
            applyWindowToggles()
            paint()
        }
        val cp = LinearLayout.LayoutParams(0, WRAP, 1f)
        cp.marginEnd = dp(10)
        row.addView(col, cp)
        row.addView(pill)
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildTools() {
        section("GAME MODE")
        val modeCard = card()
        toggleRow(modeCard, "روشن نگه‌داشتن صفحه", "تا وقتی این اپ بازه، صفحه خاموش نمی‌شود.", "keepOn")
        toggleRow(modeCard, "حداکثر روشنایی", "فقط داخل همین اپ؛ روشنایی کل سیستم تغییر نمی‌کند.", "maxBright")
        content.addView(modeCard, vlp(0, 4))

        section("SYSTEM SHORTCUTS")
        val sCard = card()
        sCard.addView(label("میان‌برهای مهم برای گیمینگ؛ تنظیمات سیستم را مستقیم باز می‌کنند.", 12f, muted), vlp(0, 8))
        val items = listOf(
            Pair("نمایشگر", Settings.ACTION_DISPLAY_SETTINGS),
            Pair("باتری", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Pair("مزاحم نشوید (DND)", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            Pair("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
            Pair("صدا", Settings.ACTION_SOUND_SETTINGS),
            Pair("حافظه", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Pair("گزینه‌های توسعه‌دهنده", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Pair("مدیریت برنامه‌ها", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        )
        for (rowItems in items.chunked(2)) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (it2 in rowItems) {
                val b = button(it2.first, false) { openSettings(it2.second) }
                b.textSize = 12.5f
                val bp = LinearLayout.LayoutParams(0, WRAP, 1f)
                bp.marginStart = dp(4)
                bp.marginEnd = dp(4)
                r.addView(b, bp)
            }
            sCard.addView(r, vlp(0, 8))
        }
        content.addView(sCard, vlp(0, 4))

        section("PRE-GAME CHECKLIST")
        val cCard = card()
        val checks = listOf(
            "حالت «مزاحم نشوید» را روشن کن",
            "باتری بالای ۳۰٪ یا گوشی روی شارژر",
            "Wi-Fi پایدار؛ دانلودهای پس‌زمینه را متوقف کن",
            "کاور ضخیم را بردار تا گوشی خنک‌تر بماند",
            "برنامه‌های سنگین را از Recent ببند"
        )
        for (c in checks) {
            val t = label("☐   $c", 13.5f, white)
            t.setPadding(0, dp(8), 0, dp(8))
            var done = false
            t.setOnClickListener {
                done = !done
                t.text = (if (done) "☑   " else "☐   ") + c
                t.setTextColor(if (done) good else white)
            }
            cCard.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        content.addView(cCard, vlp(0, 4))
    }

    // ----- STATS ------------------------------------------------------------

    private fun buildStats() {
        val times = ArrayList<Pair<String, Long>>()
        var launches = 0
        for (e in prefs.all.entries) {
            val v = e.value
            if (e.key.startsWith("pt_") && v is Long) times.add(Pair(e.key.removePrefix("pt_"), v))
            if (e.key.startsWith("lc_") && v is Int) launches += v
        }
        times.sortByDescending { it.second }
        var total = 0L
        for (t in times) total += t.second

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(dp(16), dp(16), dp(16), dp(16))
        hero.background = heroBg()
        hero.addView(label("زمان بازی ثبت‌شده", 12.5f, muted))
        hero.addView(label(if (total == 0L) "—" else fmtDur(total), 26f, white, true), vlp(2, 2))
        hero.addView(label("${launches} بار اجرا از طریق EzzGameBOOST", 12.5f, muted))
        content.addView(hero, vlp(4, 4))

        section("TOP GAMES")
        val topCard = card()
        if (times.isEmpty()) {
            topCard.addView(label("هنوز داده‌ای نیست. بازی‌ها را از تب «بازی‌ها» اجرا کن تا زمان بازی ثبت شود.", 12.5f, muted))
        } else {
            val top = maxOf(times[0].second, 1L)
            for (idx in 0 until minOf(5, times.size)) {
                val t = times[idx]
                topCard.addView(barRow(appLabel(t.first), fmtDur(t.second), t.second.toFloat() / top.toFloat(), accent, idx == 0))
            }
            topCard.addView(label("زمان‌ها تقریبی‌اند: از لحظه‌ی اجرا تا بازگشت به این اپ حساب می‌شود.", 11.5f, muted), vlp(8, 0))
        }
        content.addView(topCard, vlp(0, 4))

        section("THEME")
        val themeCard = card()
        themeCard.addView(label("رنگ تم", 14f, white, true), vlp(0, 10))
        val current = prefs.getInt("accent", 0).coerceIn(0, accents.size - 1)
        val dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        for (i in accents.indices) {
            val dot = TextView(this)
            dot.gravity = Gravity.CENTER
            dot.textSize = 16f
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(accents[i])
            if (i == current) {
                d.setStroke(dp(3), white)
                dot.text = "✓"
                dot.setTextColor(Color.rgb(8, 9, 14))
            }
            dot.background = d
            dot.setOnClickListener {
                prefs.edit().putInt("accent", i).apply()
                accent = accents[i]
                buildShell()
                showTab(tab)
            }
            val dp2 = LinearLayout.LayoutParams(dp(44), dp(44))
            dp2.marginEnd = dp(12)
            dots.addView(dot, dp2)
        }
        themeCard.addView(dots)
        themeCard.addView(label("رنگ فعلی: ${accentNames[current]}", 11.5f, muted), vlp(8, 0))
        content.addView(themeCard, vlp(0, 4))

        section("ABOUT")
        val about = card()
        about.addView(label("EzzGameBOOST v2.0", 14f, white, true))
        about.addView(
            label(
                "این اپ بدون روت کار می‌کند. دکمه‌ی BOOST فقط از API رسمی اندروید برای بستن پردازش‌های پس‌زمینه استفاده می‌کند و اندروید ممکن است بعضی برنامه‌ها را دوباره اجرا کند. هیچ اپ معمولی نمی‌تواند FPS یا پینگ داخل بازی را تضمین کند؛ تست شبکه فقط زمان اتصال TCP را اندازه می‌گیرد.",
                12f, muted
            ), vlp(6, 10)
        )
        about.addView(button("پاک کردن آمار بازی", false) {
            val keys = ArrayList<String>()
            for (k in prefs.all.keys) {
                if (k.startsWith("pt_") || k.startsWith("lc_")) keys.add(k)
            }
            val ed = prefs.edit()
            for (k in keys) ed.remove(k)
            ed.apply()
            showTab(4)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(about, vlp(0, 4))
    }
}


// ---------------------------------------------------------------------------
// DNS VPN service: local VPN that only captures DNS queries and forwards them
// to the chosen DNS server (primary, then secondary as fallback).
// ---------------------------------------------------------------------------

class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ezzdigi.ezzgameboost.DNS_START"
        private const val FAKE_DNS = "10.111.222.2"
        private const val TUN_ADDR = "10.111.222.1"

        @Volatile
        var running = false

        @Volatile
        var activeName = ""
        val queryCount = AtomicLong(0L)
        val failCount = AtomicLong(0L)
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var primaryDownUntil = 0L
    private var primary = ""
    private var secondary = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_START) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        val p = intent.getStringExtra("primary") ?: ""
        val s = intent.getStringExtra("secondary") ?: ""
        val n = intent.getStringExtra("name") ?: p
        if (p.isEmpty()) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startTunnel(p, s, n)
        return Service.START_NOT_STICKY
    }

    private fun startTunnel(p: String, s: String, name: String) {
        cleanup()
        primary = p
        secondary = s
        primaryDownUntil = 0L
        try {
            val b = Builder()
            b.setSession("EzzGameBOOST DNS")
            b.addAddress(TUN_ADDR, 24)
            b.addRoute("10.111.222.0", 24)
            b.addDnsServer(FAKE_DNS)
            b.setMtu(1500)
            b.setBlocking(true)
            val pfd = b.establish()
            if (pfd == null) {
                running = false
                stopSelf()
                return
            }
            tun = pfd
            stopping = false
            running = true
            activeName = name
            queryCount.set(0L)
            failCount.set(0L)
            pool = java.util.concurrent.Executors.newFixedThreadPool(12)
            goForeground(name)
            Thread { loop(pfd) }.start()
        } catch (_: Throwable) {
            cleanup()
            stopSelf()
        }
    }

    private fun goForeground(name: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("ezz_dns", "DNS", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "ezz_dns")
                .setContentTitle("EzzGameBOOST DNS")
                .setContentText("متصل: $name")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(1, n)
        } catch (_: Throwable) {
        }
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32768)
        try {
            while (!stopping) {
                val n = input.read(buf)
                if (n < 0) break
                if (n < 28) continue
                handle(buf, n, output)
            }
        } catch (_: Exception) {
        }
        if (!stopping) {
            running = false
            activeName = ""
            stopSelf()
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun handle(buf: ByteArray, n: Int, output: FileOutputStream) {
        val ver = (buf[0].toInt() shr 4) and 0xF
        if (ver != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17 || n < ihl + 8) return
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return
        val udpLen = u16(buf, ihl + 4)
        val plen = minOf(udpLen - 8, n - ihl - 8)
        if (plen <= 0) return
        val payload = ByteArray(plen)
        System.arraycopy(buf, ihl + 8, payload, 0, plen)
        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        System.arraycopy(buf, 12, srcIp, 0, 4)
        System.arraycopy(buf, 16, dstIp, 0, 4)
        val pl = pool ?: return
        try {
            pl.execute {
                try {
                    val resp = forward(payload)
                    if (resp != null) {
                        queryCount.incrementAndGet()
                        val pkt = buildPacket(dstIp, srcIp, 53, srcPort, resp)
                        synchronized(output) { output.write(pkt) }
                    } else {
                        failCount.incrementAndGet()
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun forward(payload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val order: List<String> = if (secondary.isEmpty()) {
            listOf(primary)
        } else if (now < primaryDownUntil) {
            listOf(secondary, primary)
        } else {
            listOf(primary, secondary)
        }
        for ((idx, server) in order.withIndex()) {
            val r = query(server, payload, if (idx == 0) 1800 else 2500)
            if (r != null) return r
            if (server == primary) primaryDownUntil = System.currentTimeMillis() + 30000L
        }
        return null
    }

    private fun query(server: String, payload: ByteArray, timeoutMs: Int): ByteArray? {
        val sock = try {
            DatagramSocket()
        } catch (_: Exception) {
            return null
        }
        try {
            protect(sock)
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(4096)
            val rp = DatagramPacket(buf, buf.size)
            sock.receive(rp)
            if (rp.length < 12 || buf[0] != payload[0] || buf[1] != payload[1]) return null
            return buf.copyOf(rp.length)
        } catch (_: Exception) {
            return null
        } finally {
            sock.close()
        }
    }

    private fun buildPacket(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, data: ByteArray): ByteArray {
        val total = 28 + data.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = (total and 0xFF).toByte()
        p[6] = 0x40
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        p[10] = (ck shr 8).toByte()
        p[11] = (ck and 0xFF).toByte()
        p[20] = (sport shr 8).toByte()
        p[21] = (sport and 0xFF).toByte()
        p[22] = (dport shr 8).toByte()
        p[23] = (dport and 0xFF).toByte()
        val ul = 8 + data.size
        p[24] = (ul shr 8).toByte()
        p[25] = (ul and 0xFF).toByte()
        System.arraycopy(data, 0, p, 28, data.size)
        return p
    }

    private fun cleanup() {
        stopping = true
        running = false
        activeName = ""
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        try {
            pool?.shutdownNow()
        } catch (_: Exception) {
        }
        pool = null
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    override fun onRevoke() {
        cleanup()
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
