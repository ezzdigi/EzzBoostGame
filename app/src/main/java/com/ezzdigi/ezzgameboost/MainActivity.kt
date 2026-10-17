package com.ezzdigi.ezzgameboost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ResolveInfo
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(13, 14, 18)
    private val panel = Color.rgb(27, 28, 35)
    private val red = Color.rgb(255, 55, 70)
    private val white = Color.rgb(245, 245, 248)
    private val muted = Color.rgb(165, 168, 180)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var gamesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadGames()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        label("EzzGame", 30, white, true)
        label("BOOST", 24, red, true)
        label("GAMING CONTROL CENTER", 11, muted, true).setPadding(0, dp(3), 0, dp(18))

        val statusCard = card()
        statusText = label("آماده‌ای؟ بازی‌ات را از لانچر اجرا کن.", 14, white, false)
        statusCard.addView(statusText)
        root.addView(statusCard, matchWrap())

        section("BOOST CENTER")
        val boost = button("اجرای حالت گیمینگ", red)
        root.addView(boost, matchWrap())
        boost.setOnClickListener {
            statusText.text = "حالت گیمینگ فعال شد. برای بهترین نتیجه، مزاحم‌ها را دستی ببند و بازی را اجرا کن."
            Toast.makeText(this, "بهینه‌سازی امن انجام شد؛ اندروید اجازه بستن اجباری برنامه‌های دیگر را نمی‌دهد.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            } catch (_: Exception) { }
        }

        section("NETWORK / PING")
        val pingCard = card()
        pingCard.addView(label("تست اتصال به Cloudflare (1.1.1.1:443)", 13, muted, false))
        val pingResult = label("هنوز تست نشده", 22, white, true)
        pingCard.addView(pingResult)
        val pingButton = button("شروع تست پینگ", red)
        pingCard.addView(pingButton, matchWrap())
        pingButton.setOnClickListener {
            pingButton.isEnabled = false
            pingResult.text = "در حال تست…"
            executor.execute {
                val result = try {
                    val start = System.nanoTime()
                    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2500) }
                    val ms = (System.nanoTime() - start) / 1_000_000
                    "اتصال موفق • ${ms} ms"
                } catch (_: Exception) {
                    "اتصال ناموفق • اینترنت یا مسیر شبکه را بررسی کن"
                }
                runOnUiThread {
                    pingResult.text = result
                    pingButton.isEnabled = true
                }
            }
        }
        root.addView(pingCard, matchWrap())

        section("GAME LAUNCHER")
        label("بازی‌های نصب‌شده‌ای که اندروید اجازه نمایش آن‌ها را می‌دهد.", 12, muted, false)
        gamesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gamesContainer, matchWrap())
        val refresh = button("به‌روزرسانی فهرست بازی‌ها", panel)
        root.addView(refresh, matchWrap())
        refresh.setOnClickListener { loadGames() }

        section("DNS / NETWORK")
        val dnsCard = card()
        dnsCard.addView(label("DNS خصوصی اندروید", 16, white, true))
        dnsCard.addView(label("این گزینه‌ها DNS-over-TLS رسمی‌اند. برای فعال‌سازی، نام میزبان را کپی کن و در تنظیمات Private DNS اندروید وارد کن. اپ بدون اجازهٔ سیستمی نمی‌تواند DNS کل گوشی را خودش تغییر دهد.", 12, muted, false))
        val dnsOptions = listOf(
            "Cloudflare • سریع و عمومی" to "one.one.one.one",
            "Google Public DNS • عمومی" to "dns.google",
            "Quad9 • تمرکز بر مسدودسازی دامنه‌های مخرب" to "dns.quad9.net",
            "AdGuard DNS • فیلتر تبلیغات در سطح DNS" to "dns.adguard-dns.com"
        )
        dnsOptions.forEach { (title, hostname) ->
            dnsCard.addView(label(title, 13, white, true))
            dnsCard.addView(label(hostname, 13, muted, false))
            val copyDns = button("کپی آدرس DNS", panel)
            dnsCard.addView(copyDns, matchWrap())
            copyDns.setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Private DNS hostname", hostname))
                Toast.makeText(this, "آدرس DNS کپی شد: $hostname", Toast.LENGTH_SHORT).show()
            }
        }
        val openDns = button("باز کردن تنظیمات Private DNS", red)
        dnsCard.addView(openDns, matchWrap())
        openDns.setOnClickListener {
            try { startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS")) }
            catch (_: Exception) { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
        }
        root.addView(dnsCard, matchWrap())

        section("GAMING SETTINGS")
        val settingsCard = card()
        settingsCard.addView(label("تنظیمات نمایشگر", 15, white, true))
        settingsCard.addView(label("برای نرخ نوسازی، روشنایی و تنظیمات قابل‌دسترسی سیستم از گزینه‌های زیر استفاده کن.", 12, muted, false))
        val display = button("باز کردن تنظیمات نمایشگر", panel)
        val battery = button("باز کردن تنظیمات باتری", panel)
        settingsCard.addView(display, matchWrap())
        settingsCard.addView(battery, matchWrap())
        display.setOnClickListener { openSettings(Settings.ACTION_DISPLAY_SETTINGS) }
        battery.setOnClickListener { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) }
        root.addView(settingsCard, matchWrap())

        section("ABOUT")
        label("EzzGameBOOST v1.0 • نسخه اولیه", 12, muted, false)
        label("نکته: اپ‌های اندرویدی معمولی نمی‌توانند تضمینی FPS را افزایش دهند، RAM گوشی را به‌صورت جادویی آزاد کنند یا پینگ اینترنت را کم کنند. این نسخه ابزار لانچر، تست اتصال و میان‌برهای تنظیمات سیستم را فراهم می‌کند.", 12, muted, false)
    }

    private fun loadGames() {
        if (!::gamesContainer.isInitialized) return
        gamesContainer.removeAllViews()
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (list.isEmpty()) {
            gamesContainer.addView(label("برنامه‌ای برای نمایش پیدا نشد.", 13, muted, false))
            return
        }
        // Limit the list so the launcher remains readable; the Android package manager may include ordinary apps too.
        list.take(40).forEach { info ->
            val appName = info.loadLabel(packageManager).toString()
            val appPackage = info.activityInfo.packageName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(panel)
            }
            val name = TextView(this).apply {
                text = appName
                textSize = 14f
                setTextColor(white)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val launch = button("اجرا", red).apply {
                textSize = 12f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    val launchIntent = packageManager.getLaunchIntentForPackage(appPackage)
                    if (launchIntent != null) startActivity(launchIntent)
                    else Toast.makeText(this@MainActivity, "اجرای این برنامه ممکن نیست.", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(name)
            row.addView(launch)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            gamesContainer.addView(row, lp)
        }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun section(text: String) {
        val t = label(text, 13, red, true)
        t.setPadding(0, dp(22), 0, dp(10))
        root.addView(t)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(panel)
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun button(text: String, color: Int): Button = Button(this).apply {
        this.text = text
        setTextColor(white)
        textSize = 14f
        isAllCaps = false
        setBackgroundColor(color)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
