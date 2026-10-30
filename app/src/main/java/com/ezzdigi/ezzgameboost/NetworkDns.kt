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

// ----- NETWORK ----------------------------------------------------------

internal fun MainActivity.tcpPing(host: String, port: Int, timeoutMs: Int): Int {
    return try {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        ((System.nanoTime() - t0) / 1000000L).toInt()
    } catch (_: Exception) {
        -1
    }
}

internal fun MainActivity.parseHostPort(text: String): Pair<String, Int>? {
    val c = text.trim()
    if (c.isEmpty()) return null
    val parts = c.split(":")
    val host = parts[0].trim()
    if (host.isEmpty()) return null
    val port = if (parts.size > 1) (parts[1].trim().toIntOrNull() ?: 443) else 443
    return Pair(host, port.coerceIn(1, 65535))
}

internal fun MainActivity.customServers(): List<ServerItem> {
    val raw = prefs.getString("servers", "") ?: ""
    val out = ArrayList<ServerItem>()
    for (line in raw.split("\n")) {
        val parts = line.split("\t")
        if (parts.size != 3) continue
        val port = parts[2].toIntOrNull() ?: continue
        out.add(ServerItem(parts[0], parts[1], port))
    }
    return out
}

internal fun MainActivity.saveCustomServers(list: List<ServerItem>) {
    val text = list.joinToString("\n") {
        it.name.replace("\t", " ").replace("\n", " ") + "\t" + it.host + "\t" + it.port
    }
    prefs.edit().putString("servers", text).apply()
}

internal fun MainActivity.allServers(): List<ServerItem> {
    val out = ArrayList<ServerItem>()
    for (t in pingTargets) out.add(ServerItem(t.first, t.second, t.third))
    out.addAll(customServers())
    return out
}

internal fun MainActivity.resolveTarget(custom: String): Pair<String, Int> {
    val parsed = parseHostPort(custom)
    if (parsed != null) return parsed
    val servers = allServers()
    val s0 = servers[netTarget.coerceIn(0, servers.size - 1)]
    return Pair(s0.host, s0.port)
}

internal fun MainActivity.histKey(host: String, port: Int): String = "$host:$port"

internal fun MainActivity.loadHistory(): List<HistItem> {
    val raw = prefs.getString("hist", "") ?: ""
    val out = ArrayList<HistItem>()
    for (line in raw.split("\n")) {
        val p = line.split(",")
        if (p.size < 4) continue
        val ts = p[0].toLongOrNull() ?: continue
        val avg = p[2].toIntOrNull() ?: continue
        val loss = p[3].toIntOrNull() ?: continue
        out.add(HistItem(ts, p[1], avg, loss))
    }
    return out
}

internal fun MainActivity.addHistory(key: String, avg: Int, loss: Int) {
    val list = ArrayList<HistItem>(loadHistory())
    list.add(HistItem(System.currentTimeMillis(), key.replace(",", ""), avg, loss))
    val trimmed = if (list.size > 300) list.subList(list.size - 300, list.size) else list
    prefs.edit().putString("hist", trimmed.joinToString("\n") { "${it.ts},${it.key},${it.avg},${it.loss}" }).apply()
}

internal fun MainActivity.clearHistory() {
    prefs.edit().remove("hist").apply()
}

internal fun MainActivity.pingColor(ms: Int): Int = when {
    ms < 0 -> bad
    ms < 60 -> good
    ms < 120 -> warn
    else -> bad
}

internal fun MainActivity.updatePingUi(
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
        verdict.text = Tr.t("اتصال برقرار نشد")
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
    verdict.text = Tr.t("کیفیت اتصال: $q")
    verdict.setTextColor(pingColor(avg))
    stats.text = "Min $mn  •  Avg $avg  •  Max $mx  •  Jitter $jit  •  Loss $loss%"
}

internal fun MainActivity.buildNetwork() {
    val sub = prefs.getInt("netSub", 0)
    val names = arrayOf("DNS", "Private DNS (DoT)", "PING")
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    for (i in names.indices) {
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.marginEnd = dp(8)
        row.addView(chip(names[i], i == sub) {
            prefs.edit().putInt("netSub", i).apply()
            showTab(tab)
        }, lp)
    }
    content.addView(row, vlp(0, 10))
    when (sub) {
        1 -> buildPrivateDns()
        2 -> buildPingLab()
        else -> buildDnsChanger()
    }
}

internal fun MainActivity.buildPingLab() {
    val hCard = card()
    val hTitle = label("", 13f, white, true, false)
    val hChart = ChartView(this, accent)
    val hBest = label("", 12f, muted, false, false)
    hCard.addView(hTitle)
    val hcp = LinearLayout.LayoutParams(MATCH, dp(100))
    hcp.topMargin = dp(8)
    hCard.addView(hChart, hcp)
    hCard.addView(hBest, vlp(8, 8))

    section("PING LAB")
    val pingCard = card()
    pingCard.addView(label("تست تأخیر اتصال", 15f, white, true))
    pingCard.addView(label("زمان برقراری اتصال TCP تا سرور انتخابی؛ پینگ ICMP یا پینگ داخل بازی نیست.", 12f, muted))

    var onTargetChanged: () -> Unit = {}
    val hs = HorizontalScrollView(this)
    hs.isHorizontalScrollBarEnabled = false
    val chipRow = LinearLayout(this)
    chipRow.orientation = LinearLayout.HORIZONTAL
    hs.addView(chipRow)
    fun fillTargets() {
        chipRow.removeAllViews()
        val servers = allServers()
        if (netTarget >= servers.size) netTarget = 0
        for (i in servers.indices) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.marginEnd = dp(8)
            chipRow.addView(chip(servers[i].name, netTarget == i, false) {
                netTarget = i
                fillTargets()
                onTargetChanged()
            }, lp)
        }
    }
    pingCard.addView(hs, vlp(10, 8))

    val nameIn = input("نام سرور (برای ذخیره)")
    val custom = input("آدرس سرور  host:port  (مثلاً 1.2.3.4:443)")
    pingCard.addView(nameIn, vlp(0, 8))
    pingCard.addView(custom, vlp(0, 8))
    val saveRow = LinearLayout(this)
    saveRow.orientation = LinearLayout.HORIZONTAL
    val saveBtn = button("ذخیره‌ی سرور", false) {}
    val delBtn = button("حذف سرور انتخابی", false) {}
    val sp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    sp1.marginEnd = dp(4)
    val sp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    sp2.marginStart = dp(4)
    saveBtn.textSize = 12.5f
    delBtn.textSize = 12.5f
    saveRow.addView(saveBtn, sp1)
    saveRow.addView(delBtn, sp2)
    pingCard.addView(saveRow, vlp(0, 12))

    fun updateDelBtn() {
        delBtn.alpha = if (netTarget >= pingTargets.size) 1f else 0.4f
    }

    fun refreshHistory() {
        val tgt = resolveTarget(custom.text.toString())
        val key = histKey(tgt.first, tgt.second)
        val items = loadHistory().filter { it.key == key }
        hTitle.text = Tr.t("کیفیت شبکه برای") + " " + key
        if (items.isEmpty()) {
            hChart.setPoints(emptyList())
            hBest.text = Tr.t("هنوز تستی برای این سرور ثبت نشده؛ یک تست پینگ بگیر.")
            return
        }
        hChart.setPoints(items.takeLast(30).map { if (it.avg < 0) -1 else it.avg })
        val sums = HashMap<Int, ArrayList<Int>>()
        val cal = java.util.Calendar.getInstance()
        for (hi in items) {
            if (hi.avg < 0) continue
            cal.timeInMillis = hi.ts
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            var l = sums[hour]
            if (l == null) {
                l = ArrayList<Int>()
                sums[hour] = l
            }
            l.add(hi.avg)
        }
        var bestH = -1
        var bestAvg = Int.MAX_VALUE
        var worstH = -1
        var worstAvg = -1
        for ((hour, l) in sums) {
            val avg = l.average().toInt()
            if (avg < bestAvg) {
                bestAvg = avg
                bestH = hour
            }
            if (avg > worstAvg) {
                worstAvg = avg
                worstH = hour
            }
        }
        if (bestH < 0) {
            hBest.text = Tr.t("همه‌ی تست‌ها بدون پاسخ بوده‌اند.")
            return
        }
        val hhBest = (if (bestH < 10) "0" else "") + bestH + ":00"
        val hhWorst = (if (worstH < 10) "0" else "") + worstH + ":00"
        var txt = Tr.t("بهترین ساعت") + ": " + hhBest + " (" + bestAvg + " ms)"
        if (worstH != bestH) txt += "   •   " + Tr.t("بدترین ساعت") + ": " + hhWorst + " (" + worstAvg + " ms)"
        txt += "   •   " + items.size + " " + Tr.t("تست")
        hBest.text = txt
    }

    onTargetChanged = {
        updateDelBtn()
        refreshHistory()
    }

    saveBtn.setOnClickListener {
        val parsed = parseHostPort(custom.text.toString())
        if (parsed == null) {
            toast("آدرس سرور را به شکل host:port وارد کن.")
            return@setOnClickListener
        }
        val list = ArrayList<ServerItem>(customServers())
        if (list.size >= 15) {
            toast("حداکثر ۱۵ سرور قابل ذخیره است.")
            return@setOnClickListener
        }
        val nm = nameIn.text.toString().trim().ifEmpty { parsed.first }
        list.add(ServerItem(nm, parsed.first, parsed.second))
        saveCustomServers(list)
        netTarget = pingTargets.size + list.size - 1
        nameIn.setText("")
        custom.setText("")
        fillTargets()
        updateDelBtn()
        refreshHistory()
        toast("سرور ذخیره شد.")
    }
    delBtn.setOnClickListener {
        if (netTarget < pingTargets.size) {
            toast("فقط سرورهای ذخیره‌شده‌ی خودت حذف می‌شوند.")
            return@setOnClickListener
        }
        val list = ArrayList<ServerItem>(customServers())
        val idx = netTarget - pingTargets.size
        if (idx >= 0 && idx < list.size) {
            list.removeAt(idx)
            saveCustomServers(list)
        }
        netTarget = 0
        fillTargets()
        updateDelBtn()
        refreshHistory()
    }
    fillTargets()
    updateDelBtn()

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
            startBtn.text = Tr.t("شروع تست پینگ")
            return@setOnClickListener
        }
        val target = resolveTarget(custom.text.toString())
        val host = target.first
        val port = target.second
        running = true
        startBtn.text = Tr.t("توقف")
        pingToken++
        val token = pingToken
        val samples = ArrayList<Int>()
        val results = java.util.Collections.synchronizedList(ArrayList<Int>())
        chart.setPoints(emptyList())
        runBg {
            var n = 0
            while (n < 25 && token == pingToken) {
                val ms = tcpPing(host, port, 2000)
                results.add(ms)
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
            val snap = ArrayList<Int>(results)
            if (snap.size >= 5) {
                val okv = snap.filter { it >= 0 }
                val avg = if (okv.isEmpty()) -1 else okv.average().toInt()
                val loss = (snap.size - okv.size) * 100 / snap.size
                addHistory(histKey(host, port), avg, loss)
            }
            ui {
                if (token == pingToken) {
                    running = false
                    startBtn.text = Tr.t("شروع تست پینگ")
                }
                refreshHistory()
            }
        }
    }
    pingCard.addView(startBtn, LinearLayout.LayoutParams(MATCH, WRAP))
    content.addView(pingCard, vlp(0, 4))

    section("PING HISTORY")
    hCard.addView(button("پاک کردن تاریخچه", false) {
        clearHistory()
        refreshHistory()
    })
    content.addView(hCard, vlp(0, 4))
    refreshHistory()

}

internal fun MainActivity.buildPrivateDns() {
    section("PRIVATE DNS (DoT)")
    val dnsCard = card()
    dnsCard.addView(label("Private DNS در اندروید (DNS-over-TLS)", 15f, white, true))
    dnsCard.addView(
        label(
            "روش دوم: DNS-over-TLS داخل تنظیمات خود اندروید. با زدن «کپی» نام میزبان کپی می‌شود؛ آن را در Private DNS وارد کن. اگر اتصال قطع شد، گزینه را روی Automatic برگردان.",
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
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val cb = button("کپی", false) { copyText("Private DNS hostname", opt.second) }
        cb.textSize = 12.5f
        cb.setPadding(dp(16), dp(8), dp(16), dp(8))
        r.addView(cb)
        dnsCard.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    val openDns = button("باز کردن Private DNS") {
        try {
            startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
        } catch (_: Exception) {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }
    dnsCard.addView(openDns, vlp(8, 0))
    content.addView(dnsCard, vlp(0, 4))
}

internal fun MainActivity.selectedDns(): DnsProvider =
    dnsProviders[prefs.getInt("dnsSel", 0).coerceIn(0, dnsProviders.size - 1)]

internal fun MainActivity.buildDnsChanger() {
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
    val fastBtn = button("⚡ سریع‌ترین را پیدا کن و وصل شو", false) {}
    dCard.addView(fastBtn, vlp(0, 8))
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
        statusTitle.text = Tr.t(if (running) "🟢 متصل: $active" else "⚪ قطع (DNS پیش‌فرض سیستم)")
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
        statusSub.text = Tr.t(statusSub.text.toString())
        connectBtn.text = Tr.t(connectBtn.text.toString())
        val expanded = prefs.getBoolean("dnsExpand", false)
        val key = "$running|$active|${sel.name}|${dnsPing.size}|$dnsBenchRunning|$expanded|${System.currentTimeMillis() / 3600000L}"
        if (!force && key == lastKey) return
        lastKey = key
        listBox.removeAllViews()
        for (i in dnsProviders.indices) {
            val pr = dnsProviders[i]
            val isSel = pr.name == sel.name
            if (!expanded && !isSel) continue
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
            val title = (if (running && pr.name == active) "${pr.name}  ✓" else pr.name) + (if (isNewKey("dns", pr.name)) "  🆕 جدید" else "")
            col.addView(label(title, 13.5f, white, true, false))
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
        val others = dnsProviders.size - 1
        if (others > 0) {
            val ex = label(if (expanded) "▲ بستن سایر DNSها" else "▼ سایر DNSها ($others)", 13f, accent, true)
            ex.gravity = Gravity.CENTER
            ex.setPadding(0, dp(10), 0, dp(10))
            ex.setOnClickListener {
                prefs.edit().putBoolean("dnsExpand", !expanded).apply()
                render(true)
            }
            listBox.addView(ex, vlp(2, 2))
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
    benchBtn.setOnClickListener { runDnsBench(benchBtn, { render(true) }, null) }
    fastBtn.setOnClickListener {
        runDnsBench(benchBtn, { render(true) }, {
            val b = bestDnsIndex()
            if (b >= 0) {
                prefs.edit().putInt("dnsSel", b).apply()
                render(true)
                toast(Tr.t("سریع‌ترین:") + " " + dnsProviders[b].name)
                connectDns(dnsProviders[b])
            } else {
                toast("هیچ DNS ای پاسخ نداد؛ اینترنت را بررسی کن.")
            }
        })
    }

    val remoteLbl = label(remoteDnsStatus(), 11.5f, muted)
    dCard.addView(remoteLbl, dCard.childCount - 1, vlp(0, 6))
    val remoteBtn = button("🌐 به‌روزرسانی لیست DNS آنلاین", false) {}
    dCard.addView(remoteBtn, dCard.childCount - 1, vlp(0, 8))
    remoteBtn.setOnClickListener {
        remoteBtn.text = Tr.t("در حال دریافت…")
        refreshRemoteDns(true) { n ->
            remoteBtn.text = Tr.t("🌐 به‌روزرسانی لیست DNS آنلاین")
            remoteLbl.text = Tr.t(remoteDnsStatus())
            toast(if (n > 0) Tr.t("لیست به‌روز شد") + ": $n" else Tr.t("دریافت لیست ناموفق بود") + " (" + lastHttpErr + ")")
            render(true)
        }
    }

    netRefresh = { render(false) }
    render(true)
}

internal fun MainActivity.buildDnsQuery(name: String, id: Int): ByteArray {
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

internal fun MainActivity.dnsPingMs(ip: String, timeoutMs: Int): Int {
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

internal fun MainActivity.bestDnsIndex(): Int {
    var best = -1
    var bestMs = Int.MAX_VALUE
    for (i in dnsProviders.indices) {
        val ms = dnsPing[dnsProviders[i].name] ?: continue
        if (ms >= 0 && ms < bestMs) {
            best = i
            bestMs = ms
        }
    }
    return best
}

internal fun MainActivity.runDnsBench(btn: TextView, onUpdate: () -> Unit, onDone: (() -> Unit)?) {
    if (dnsBenchRunning) return
    dnsBenchRunning = true
    dnsPing.clear()
    btn.text = Tr.t("در حال تست…")
    onUpdate()
    val total = dnsProviders.size
    val finished = java.util.concurrent.atomic.AtomicInteger(0)
    for (pr in dnsProviders) {
        runBg {
            val vals = ArrayList<Int>()
            for (k in 0 until 3) {
                val ms = dnsPingMs(pr.primary, 1200)
                if (ms >= 0) vals.add(ms)
            }
            val avg = if (vals.isEmpty()) -1 else vals.average().toInt()
            val done = finished.incrementAndGet()
            ui {
                dnsPing[pr.name] = avg
                onUpdate()
                if (done >= total) {
                    dnsBenchRunning = false
                    btn.text = Tr.t("تست سرعت همه‌ی DNSها")
                    onUpdate()
                    if (onDone != null) onDone()
                }
            }
        }
    }
}

internal fun MainActivity.connectDns(pr: DnsProvider) {
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

internal fun MainActivity.startDnsService(pr: DnsProvider) {
    val i = Intent(this, DnsVpnService::class.java)
    i.action = DnsVpnService.ACTION_START
    i.putExtra("primary", pr.primary)
    i.putExtra("secondary", pr.secondary)
    i.putExtra("tertiary", pr.tertiary)
    i.putExtra("quaternary", pr.quaternary)
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
    launchPendingSoon(900)
}

internal fun MainActivity.stopDns() {
    DnsVpnService.requestStop(this)
    toast("DNS به حالت پیش‌فرض برگشت.")
    handler.postDelayed({
        netRefresh?.invoke()
        homeRefresh?.invoke()
    }, 500)
}
