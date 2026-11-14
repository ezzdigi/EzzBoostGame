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

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// "DNGaming" tab: free custom DNS. The user types IPv4 and/or IPv6 addresses, tests them, and
// connects. Saved entries share the app's custom-DNS list ("★ name"), so connect / stop work
// exactly like every other DNS in the app.
internal fun MainActivity.buildDnsGaming() {
    section("DNGaming • DNS رایگان IPv4 / IPv6")
    val cardV = card()
    val status = label("", 15f, white, true, false)
    val statusSub = label("", 12f, muted)
    cardV.addView(status)
    cardV.addView(statusSub, vlp(2, 8))
    val nameIn = input("نام (اختیاری)")
    val c4a = input("IPv4 اصلی  (مثل 1.1.1.1)")
    val c4b = input("IPv4 دوم  (مثل 1.0.0.1)")
    val c6a = input("IPv6 اصلی  (مثل 2606:4700:4700::1111)")
    val c6b = input("IPv6 دوم  (مثل 2606:4700:4700::1001)")
    for (e in arrayOf(c4a, c4b, c6a, c6b)) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        e.textDirection = View.TEXT_DIRECTION_LTR
    }
    cardV.addView(nameIn, vlp(0, 0))
    cardV.addView(c4a, vlp(8, 0))
    cardV.addView(c4b, vlp(8, 0))
    cardV.addView(c6a, vlp(8, 0))
    cardV.addView(c6b, vlp(8, 0))

    fun collect(): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        val fields = arrayOf(
            Pair("IPv4 #1", c4a), Pair("IPv4 #2", c4b),
            Pair("IPv6 #1", c6a), Pair("IPv6 #2", c6b)
        )
        for ((i, f) in fields.withIndex()) {
            val v = f.second.text.toString().trim()
            if (v.isEmpty()) continue
            val ok = if (i < 2) isIpv4(v) else isIpv6(v)
            if (!ok) {
                toast(f.first + ": " + Tr.t("آدرس نامعتبر است"))
                return null
            }
            out.add(Pair(f.first, v))
        }
        if (out.isEmpty()) {
            toast(Tr.t("حداقل یک آدرس DNS وارد کن"))
            return null
        }
        return out
    }

    val res = label("", 12.5f, white, false, false)

    // Saves the typed addresses as a custom DNS and returns its provider name ("★ name").
    fun saveEntry(): String? {
        if (collect() == null) return null
        val lines = ArrayList<List<String>>(customDnsLines())
        if (lines.size >= 20) {
            toast(Tr.t("حداکثر ۲۰ DNS دلخواه قابل ذخیره است."))
            return null
        }
        var nm = nameIn.text.toString().trim().replace("\t", " ").replace("\n", " ").take(24)
        if (nm.isEmpty()) nm = "DNGaming " + (lines.size + 1)
        val taken = lines.map { it[0] }.toSet()
        var unique = nm
        var n = 2
        while (taken.contains(unique)) {
            unique = "$nm $n"
            n++
        }
        lines.add(
            listOf(
                unique,
                c4a.text.toString().trim(), c4b.text.toString().trim(),
                c6a.text.toString().trim(), c6b.text.toString().trim()
            )
        )
        writeCustomDns(lines)
        return "★ " + unique
    }

    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val bTest = button("🧪 تست آدرس‌ها", false) {
        val a = collect()
        if (a != null) {
            res.text = Tr.t("در حال تست…")
            testDnsAddrs(a) { res.text = it }
        }
    }
    val bSave = button("💾 ذخیره", false) {
        if (saveEntry() != null) {
            toast(Tr.t("DNS ذخیره شد"))
            nameIn.setText("")
            netRefresh?.invoke()
        }
    }
    val lpT = LinearLayout.LayoutParams(0, WRAP, 1f)
    lpT.marginEnd = dp(4)
    val lpS = LinearLayout.LayoutParams(0, WRAP, 1f)
    lpS.marginStart = dp(4)
    row.addView(bTest, lpT)
    row.addView(bSave, lpS)
    cardV.addView(row, vlp(10, 8))
    cardV.addView(button("⚡ ذخیره و اتصال", true) {
        val pn = saveEntry()
        if (pn != null) {
            nameIn.setText("")
            val pr = dnsProviders.firstOrNull { it.name == pn }
            if (pr != null) connectDns(pr)
            handler.postDelayed({ netRefresh?.invoke() }, 800)
        }
    }, vlp(0, 8))
    cardV.addView(res, vlp(0, 6))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    cardV.addView(list)
    content.addView(cardV, vlp(0, 4))

    var key = ""
    fun render(force: Boolean) {
        val running = DnsVpnService.running
        val active = DnsVpnService.activeName
        status.text = Tr.t(if (running) "🟢 متصل: $active" else "⚪ قطع (DNS پیش‌فرض سیستم)")
        status.setTextColor(if (running) good else white)
        statusSub.text = if (running) {
            Tr.t("درخواست پاسخ‌داده‌شده: ${DnsVpnService.queryCount.get()} • ناموفق: ${DnsVpnService.failCount.get()}")
        } else {
            Tr.t("آدرس‌های DNS را وارد کن، تست کن و وصل شو.")
        }
        val lines = customDnsLines()
        val k = lines.joinToString("|") { it.joinToString(",") } + "|$running|$active"
        if (!force && k == key) return
        key = k
        list.removeAllViews()
        if (lines.isEmpty()) {
            list.addView(label("هنوز DNS دلخواهی ذخیره نشده.", 12.5f, muted))
            return
        }
        for ((idx, ln) in lines.withIndex()) {
            val nm = ln[0]
            val addrs = ArrayList<String>()
            for (j in 1..4) if (ln[j].isNotBlank()) addrs.add(ln[j])
            val provName = "★ " + nm
            val isActive = running && active == provName
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(12), dp(10), dp(12), dp(10))
            box.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
            box.addView(label(if (isActive) "$nm  ✓" else nm, 13.5f, white, true, false))
            box.addView(label(addrs.joinToString("\n"), 11.5f, muted, false, false))
            val br = LinearLayout(this)
            br.orientation = LinearLayout.HORIZONTAL
            val b1 = smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
                if (isActive) {
                    stopDns()
                } else {
                    val pr = dnsProviders.firstOrNull { it.name == provName }
                    if (pr != null) connectDns(pr)
                }
                handler.postDelayed({ netRefresh?.invoke() }, 800)
            }
            val b2 = smallBtn("🧪 تست", false) {
                val pairs = ArrayList<Pair<String, String>>()
                for (a in addrs) pairs.add(Pair(if (a.contains(":")) "IPv6" else "IPv4", a))
                res.text = Tr.t("در حال تست…")
                testDnsAddrs(pairs) { res.text = it }
            }
            val b3 = smallBtn("🗑", false) {
                if (isActive) stopDns()
                val l = ArrayList<List<String>>(customDnsLines())
                if (idx < l.size) l.removeAt(idx)
                writeCustomDns(l)
                render(true)
            }
            val m1 = LinearLayout.LayoutParams(WRAP, WRAP)
            m1.marginEnd = dp(6)
            val m2 = LinearLayout.LayoutParams(WRAP, WRAP)
            m2.marginEnd = dp(6)
            br.addView(b1, m1)
            br.addView(b2, m2)
            br.addView(b3)
            box.addView(br, vlp(8, 0))
            list.addView(box, vlp(0, 6))
        }
    }
    netRefresh = { render(false) }
    vpnRender = { render(true) }
    vpnRefresh = { render(false) }
    render(true)
}
