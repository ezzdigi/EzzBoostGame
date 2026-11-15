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
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

// ----- "DNS گیمینگ پرو" card (inside the Pro Gaming tab) -----------------------------
//
// The DNS list lives in the project: dns-gaming-pro.json (repo root), synced at most hourly.
// One tap does everything:  1) detect the phone's public IP and register it automatically,
// 2) test every DNS in the list, 3) connect to the fastest one.
//
// IP registration: the app POSTs its token to api.php?a=ip on the subscription server, which records the
// phone's public IP itself. If registration fails, nothing is tested or connected.

// A DNS of the list: its IPv4 and IPv6 addresses (up to 4 of each). The list file may mix both kinds in "servers".
internal class ProDnsItem(val name: String, val v4: List<String>, val v6: List<String>) {
    val servers: List<String> get() = v4 + v6
}

private fun MainActivity.proDnsParse(): List<ProDnsItem> {
    val out = ArrayList<ProDnsItem>()
    try {
        val o = org.json.JSONObject(prefs.getString("proDnsJson", "") ?: "")
        val arr = o.optJSONArray("providers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val v4 = ArrayList<String>()
                val v6 = ArrayList<String>()
                val sa = p.optJSONArray("servers")
                if (sa != null) for (j in 0 until sa.length()) {
                    val v = sa.optString(j).trim()
                    if (v.isEmpty()) continue
                    if (isIpv4(v)) v4.add(v) else if (isIpv6(v)) v6.add(v)
                }
                val nm = p.optString("name").trim()
                if (nm.isNotEmpty() && (v4.isNotEmpty() || v6.isNotEmpty())) out.add(ProDnsItem(nm, v4.take(4), v6.take(4)))
            }
        }
    } catch (_: Throwable) {
    }
    return out
}

internal fun MainActivity.proDnsItems(): List<ProDnsItem> = proDnsParse()

// Downloads dns-gaming-pro.json and keeps it on the phone; onDone(true) when the list changed/was read.
internal fun MainActivity.syncProDns(onDone: (Boolean) -> Unit) {
    runBg {
        var body: String? = null
        for (u in listUrls("dns-gaming-pro.json")) {
            val b = httpGet(u, 6000, 600000)
            if (b != null && b.trimStart().startsWith("{")) {
                try {
                    org.json.JSONObject(b).getJSONArray("providers")
                    body = b
                    break
                } catch (_: Throwable) {
                }
            }
        }
        ui {
            if (body != null) {
                prefs.edit().putString("proDnsJson", body).putLong("proDnsSyncTs", System.currentTimeMillis()).apply()
            }
            onDone(body != null)
        }
    }
}

// The physical (non-VPN) network, so the IP we register is the phone's real one even while a VPN is on.
@Suppress("DEPRECATION")
private fun MainActivity.proPhysicalNetwork(): android.net.Network? {
    val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    var best: android.net.Network? = null
    var bestScore = -1
    for (n in cm.allNetworks) {
        val c = cm.getNetworkCapabilities(n) ?: continue
        if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
        if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
        var sc = 0
        if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) sc += 2
        if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) sc += 1
        if (sc > bestScore) {
            bestScore = sc
            best = n
        }
    }
    return best
}

// Answer of the server to an IP registration. status: approved / pending / rejected.
// An IP only works after the admin has approved it (a new IP starts as "pending").
internal class ProReg(val ok: Boolean, val ip: String, val status: String, val err: String)

// Asks the subscription server (api.php?a=ip) to record this phone's public IP. The server reads the IP
// from the connection itself, so it cannot be faked, and the request leaves over the real network.
internal fun MainActivity.proRegisterIp(): ProReg {
    val token = subToken()
    if (token.isEmpty()) return ProReg(false, "", "", "no token")
    val net = proPhysicalNetwork() ?: return ProReg(false, "", "", "no network")
    var holder: java.net.HttpURLConnection? = null
    return try {
        val conn = net.openConnection(java.net.URL(SUB_API + "?a=ip")) as java.net.HttpURLConnection
        holder = conn
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", "EzzGameBOOST")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("t=" + android.net.Uri.encode(token)).toByteArray()) }
        val code = conn.responseCode
        if (code in 200..299) {
            val o = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            val ip = o.getString("ip")
            // An older api.php has no approval step; treat its answer as approved.
            val st = o.optString("status", "approved")
            prefs.edit()
                .putString("proDnsRegIp", ip)
                .putString("proIpState", st)
                .putLong("proDnsRegTs", System.currentTimeMillis())
                .apply()
            ProReg(true, ip, st, "")
        } else {
            ProReg(false, "", "", "HTTP $code")
        }
    } catch (e: Exception) {
        ProReg(false, "", "", e.message ?: "error")
    } finally {
        try {
            holder?.disconnect()
        } catch (_: Throwable) {
        }
    }
}

// Watches for the phone changing network (Wi-Fi <-> mobile data) and calls onNet (debounced).
// It also fires once right after start() for the current network.
internal object ProNetWatch {
    private var cm: android.net.ConnectivityManager? = null
    private var cb: android.net.ConnectivityManager.NetworkCallback? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null

    fun start(ctx: android.content.Context, onNet: () -> Unit) {
        stop()
        try {
            val m = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val c = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    pending?.let { h.removeCallbacks(it) }
                    val r = Runnable { onNet() }
                    pending = r
                    h.postDelayed(r, 1500)
                }
            }
            m.registerNetworkCallback(req, c)
            cm = m
            cb = c
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try {
            val c = cb
            if (c != null) cm?.unregisterNetworkCallback(c)
        } catch (_: Throwable) {
        }
        pending?.let { h.removeCallbacks(it) }
        pending = null
        cb = null
        cm = null
    }
}

// One address of one DNS with its measured speed: ms = average of the answered probes (-1 = none answered),
// fails = how many of the 3 probes got no answer.
private class ProProbe(val item: ProDnsItem, val addr: String, val ms: Int, val fails: Int)

// The DNS that was chosen: its answering addresses (IPv4 and IPv6), fastest first, and the score of the fastest.
private class ProPick(val item: ProDnsItem, val addrs: List<String>, val score: Int)

// Tests the given (DNS, address) pairs at the same time: `probes` tries each, `timeoutMs` per try. Blocking, at most ~25 s.
private fun MainActivity.proProbeJobs(jobs: List<Pair<ProDnsItem, String>>, probes: Int, timeoutMs: Int, threads: Int): List<ProProbe> {
    if (jobs.isEmpty()) return emptyList()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(threads, jobs.size))
    try {
        val futures = ArrayList<java.util.concurrent.Future<ProProbe>>()
        for (job in jobs) {
            futures.add(pool.submit(java.util.concurrent.Callable {
                var sum = 0
                var ok = 0
                for (n in 0 until probes) {
                    val ms = dnsPingMs(job.second, timeoutMs)
                    if (ms >= 0) {
                        sum += ms
                        ok++
                    }
                }
                ProProbe(job.first, job.second, if (ok > 0) sum / ok else -1, probes - ok)
            }))
        }
        val out = ArrayList<ProProbe>()
        val deadline = System.currentTimeMillis() + 25000L
        for (f in futures) {
            val left = deadline - System.currentTimeMillis()
            try {
                out.add(f.get(maxOf(left, 1L), java.util.concurrent.TimeUnit.MILLISECONDS))
            } catch (_: Throwable) {
            }
        }
        return out
    } finally {
        pool.shutdownNow()
    }
}

// Tests EVERY address of EVERY DNS (IPv4 and IPv6) from this phone's own connection.
// A short list is tested carefully at once (3 probes per address). A long list (hundreds of addresses) is tested in
// two steps: one quick probe of every address, then the careful test of the 12 fastest DNS.
private fun MainActivity.proProbeAll(items: List<ProDnsItem>): List<ProProbe> {
    val all = ArrayList<Pair<ProDnsItem, String>>()
    for (p in items) for (a in p.servers) all.add(Pair(p, a))
    if (all.size <= 48) return proProbeJobs(all, 3, 1000, 24)
    val quick = proProbeJobs(all, 1, 600, 60)
    val bestMs = HashMap<ProDnsItem, Int>()
    for (q in quick) {
        if (q.ms < 0) continue
        val cur = bestMs[q.item]
        if (cur == null || q.ms < cur) bestMs[q.item] = q.ms
    }
    val top = bestMs.entries.sortedBy { it.value }.take(12).map { it.key }
    val jobs = ArrayList<Pair<ProDnsItem, String>>()
    for (p in top) for (a in p.servers) jobs.add(Pair(p, a))
    return proProbeJobs(jobs, 3, 1000, 24)
}

// Best DNS first. A lost probe costs 250 ms of score, so a fast but flaky address does not win.
private fun proChoose(probes: List<ProProbe>): List<ProPick> {
    val picks = ArrayList<ProPick>()
    for (item in probes.map { it.item }.distinct()) {
        val ok = probes.filter { it.item === item && it.ms >= 0 }.sortedBy { it.ms + it.fails * 250 }
        if (ok.isEmpty()) continue
        picks.add(ProPick(item, ok.map { it.addr }.take(4), ok[0].ms + ok[0].fails * 250))
    }
    return picks.sortedBy { it.score }
}

// Gives the server a moment to apply a new IP, then tests everything; retries once if nothing answered. Blocking.
private fun MainActivity.proFindBest(items: List<ProDnsItem>): ProPick? {
    for (attempt in 0..1) {
        try {
            Thread.sleep(if (attempt == 0) 1200L else 3000L)
        } catch (_: Throwable) {
        }
        val picks = proChoose(proProbeAll(items))
        if (picks.isNotEmpty()) return picks[0]
    }
    return null
}

private fun proRegMsg(r: ProReg): String = when (r.status) {
    "approved" -> "✅ " + Tr.t("با موفقیت ثبت شد")
    "pending" -> "✅ " + Tr.t("با موفقیت ثبت شد") + " — " + Tr.t("منتظر تایید مدیر")
    else -> "⛔ " + Tr.t("این IP تایید نشده است؛ با پشتیبانی تماس بگیر.")
}

private fun proFailMsg(r: ProReg): String =
    "❌ " + Tr.t("ثبت IP ناموفق بود؛ اینترنت و ورود به حساب را چک کن و دوباره امتحان کن.") + " (" + r.err + ")"

@Volatile
private var proPipeBusy = false

// The whole job in one go: 1) register the IP (it must be approved), 2) test all DNS addresses (IPv4 + IPv6) from this
// phone's operator, 3) connect the best one through the game tunnel (only the games go through it).
// onStep reports progress; onDone(best, message) is called once, on the UI thread (best = null when it stopped early).
private fun MainActivity.proRunPipeline(onStep: (String) -> Unit, onDone: (ProPick?, String) -> Unit) {
    if (proPipeBusy) {
        onDone(null, "⏳ " + Tr.t("در حال انجام است…"))
        return
    }
    val items = proDnsItems()
    if (items.isEmpty()) {
        onDone(null, "❌ " + Tr.t("لیست DNS خالی است؛ بعداً دوباره امتحان کن."))
        return
    }
    proPipeBusy = true
    onStep("⏳ " + Tr.t("۱/۳ در حال ثبت IP…"))
    runBg {
        var best: ProPick? = null
        var msg = ""
        try {
            val r = proRegisterIp()
            if (!r.ok) {
                msg = proFailMsg(r)
            } else if (r.status != "approved") {
                // Not approved by the admin yet: nothing is tested or connected.
                msg = proRegMsg(r)
            } else if (dnsGamePackages(this).isEmpty()) {
                msg = "🎮 " + Tr.t("بازی‌ای روی گوشی پیدا نشد؛ در تب بازی‌ها یک بازی را علاقه‌مند کن تا DNS از تونل بازی رد شود.")
            } else {
                ui { onStep("⏳ " + Tr.t("۲/۳ در حال تست همه‌ی DNSها (IPv4 و IPv6)…")) }
                best = proFindBest(items)
                msg = if (best == null) "❌ " + Tr.t("هیچ DNS ای جواب نداد. اینترنت را چک کن.") else ""
            }
        } catch (e: Throwable) {
            msg = "❌ " + (e.message ?: "error")
        }
        val pick = best
        if (pick == null) {
            proPipeBusy = false
            ui { onDone(null, msg) }
            return@runBg
        }
        prefs.edit().putString("proBestIp", pick.addrs[0]).apply()
        ui {
            onStep("⏳ " + Tr.t("۳/۳ در حال اتصال از تونل بازی…"))
            connectDns(proToProvider(pick))
        }
        // The VPN permission dialog may be on screen, so give it a moment before judging.
        try {
            Thread.sleep(2500)
        } catch (_: Throwable) {
        }
        proPipeBusy = false
        ui {
            handler.postDelayed({ vpnRefresh?.invoke() }, 300)
            onDone(
                pick,
                if (DnsVpnService.running) "✅ " + Tr.t("بهترین سرور برای اپراتور تو پیدا شد و وصل شد.")
                else "⚠️ " + Tr.t("هنوز وصل نشده؛ اگر پنجره‌ی اجازه‌ی VPN آمد تایید کن، وگرنه دوباره امتحان کن.")
            )
        }
    }
}

@Volatile
private var proFlowBusy = false

// Runs on every network change (see ProNetWatch): registers the new IP. If the IP is not approved the Pro DNS
// is disconnected; if it is approved and the Pro DNS is connected, the best DNS for that IP is picked again.
// Never asks for VPN permission from the background.
internal fun MainActivity.proAutoFlow() {
    if (proFlowBusy || proPipeBusy || subToken().isEmpty()) return
    val items = proDnsItems()
    proFlowBusy = true
    runBg {
        try {
            val prevIp = prefs.getString("proDnsRegIp", "") ?: ""
            val r = proRegisterIp()
            if (!r.ok) return@runBg
            if (r.status != "approved") {
                ui {
                    if (DnsVpnService.running && DnsVpnService.activeName == PRO_DNS_NAME) DnsVpnService.requestStop(this)
                    handler.postDelayed({ vpnRefresh?.invoke() }, 600)
                }
                return@runBg
            }
            if (r.ip == prevIp || !DnsVpnService.running || items.isEmpty()) {
                ui { vpnRefresh?.invoke() }
                return@runBg
            }
            val best = proFindBest(items) ?: return@runBg
            ui {
                if (VpnService.prepare(this) == null) {
                    prefs.edit().putString("proBestIp", best.addrs[0]).apply()
                    connectDns(proToProvider(best))
                    handler.postDelayed({ vpnRefresh?.invoke() }, 900)
                }
            }
        } finally {
            proFlowBusy = false
        }
    }
}

private const val PRO_DNS_NAME = "DNS گیمینگ پرو"

// primary / secondary / tertiary / quaternary = the answering addresses, fastest first (IPv4 and IPv6 mixed).
private fun proToProvider(p: ProPick): DnsProvider = DnsProvider(
    PRO_DNS_NAME,
    p.addrs[0],
    p.addrs.getOrElse(1) { "" },
    p.addrs.getOrElse(2) { "" },
    p.addrs.getOrElse(3) { "" }
)

// ----- PRO DNS tab -------------------------------------------------------------------------

internal fun MainActivity.buildProDnsTab() {
    if (subToken().isEmpty()) {
        subNeedLogin("PRO DNS")
        return
    }
    val render = buildProDnsCard()
    buildProTestCard()
    vpnRefresh = { render(false) }
    vpnRender = { render(true) }
}

// Connection card: register the IP, connect to the best DNS. The DNS list and the IP are never shown.
// Returns its render function (called with force = false on refresh ticks).
private fun MainActivity.buildProDnsCard(): (Boolean) -> Unit {
    section("PRO DNS")
    val cardV = subGlass(14)
    val status = label("", 15f, white, true, false)
    val ipState = label("", 12.5f, muted, false, false)
    val msg = label("", 12.5f, white, false, false)
    cardV.addView(status)
    cardV.addView(ipState, vlp(2, 0))
    cardV.addView(msg, vlp(2, 6))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL

    var busy = false
    var key = ""
    var render: (Boolean) -> Unit = {}

    fun setMsg(t: String) {
        msg.text = t
    }

    // Manual IP registration (it also happens automatically on connect and on every network change).
    fun registerNow() {
        if (busy) return
        busy = true
        setMsg("⏳ " + Tr.t("در حال ثبت IP…"))
        render(true)
        runBg {
            val r = proRegisterIp()
            ui {
                busy = false
                setMsg(if (r.ok) proRegMsg(r) else proFailMsg(r))
                render(true)
            }
        }
    }

    fun connectBest() {
        if (busy) return
        busy = true
        render(true)
        proRunPipeline({ st -> setMsg(st) }) { _, m ->
            busy = false
            setMsg(m)
            render(true)
        }
    }

    val regBtn = button("📍 ثبت IP", false) { registerNow() }
    cardV.addView(regBtn, vlp(4, 6))
    val btn = button("⚡ اتصال به بهترین DNS", true) { connectBest() }
    cardV.addView(btn, vlp(0, 6))
    cardV.addView(list)
    content.addView(cardV, vlp(0, 4))

    render = fun(force: Boolean) {
        val running = DnsVpnService.running
        val active = DnsVpnService.activeName
        status.text = if (running) {
            if (active == PRO_DNS_NAME) "🟢 " + Tr.t("DNS گیمینگ پرو متصل") else Tr.t("🟢 DNS متصل: ") + active
        } else {
            Tr.t("⚪ DNS گیمینگ قطع")
        }
        status.setTextColor(if (running) good else white)
        val st = prefs.getString("proIpState", "") ?: ""
        ipState.text = when (st) {
            "approved" -> "🔒 " + Tr.t("وضعیت IP: تایید شده")
            "pending" -> "⏳ " + Tr.t("وضعیت IP: منتظر تایید مدیر")
            "rejected" -> "⛔ " + Tr.t("وضعیت IP: رد شده")
            else -> Tr.t("وضعیت IP: ثبت نشده")
        }
        ipState.setTextColor(
            when (st) {
                "approved" -> good
                "pending" -> warn
                "rejected" -> bad
                else -> muted
            }
        )
        regBtn.alpha = if (busy) 0.5f else 1f
        btn.text = if (busy) "⏳ ..." else if (running) Tr.t("🔄 اتصال دوباره به بهترین DNS") else "⚡ " + Tr.t("اتصال به بهترین DNS")
        val k = "$running|$active|$st|$busy"
        if (!force && k == key) return
        key = k
        list.removeAllViews()
        if (running) {
            list.addView(smallBtn("قطع اتصال DNS", false) { stopDns() }, vlp(4, 6))
        } else if (!(prefs.getString("proDnsJson", "") ?: "").contains("\"providers\"")) {
            list.addView(label("در حال آماده‌سازی…", 12f, muted), vlp(4, 0))
        }
    }
    render(true)

    // Auto: refresh the list (hourly) and make sure the IP is registered when the tab opens
    // (network changes are handled by ProNetWatch).
    fun autoRegister() {
        if (System.currentTimeMillis() - prefs.getLong("proDnsRegTs", 0L) < 600000L) return
        runBg {
            proRegisterIp()
            ui { render(true) }
        }
    }
    if (System.currentTimeMillis() - prefs.getLong("proDnsSyncTs", 0L) > 3600000L || proDnsItems().isEmpty()) {
        syncProDns { render(true); autoRegister() }
    } else {
        autoRegister()
    }
    return render
}

// Ping (live chart) and speed test of the Pro DNS, like the ping lab of the DNS tab.
// It tests ONLY the DNS the user is connected to right now (it never tests the whole list): live ping, then the
// line speed. When the Pro DNS is not connected it asks the user to connect first.
// Names and addresses of the DNS servers are never shown.
private fun MainActivity.buildProTestCard() {
    section("PING & SPEED")
    val c = card()
    c.addView(label("تست پینگ و سرعت", 15f, white, true))
    val big = label("— ms", 38f, accent, true)
    big.gravity = Gravity.CENTER
    val verdict = label("آماده‌ی تست", 13f, muted)
    verdict.gravity = Gravity.CENTER
    val chart = ChartView(this, accent)
    val stats = label("", 12f, white)
    stats.gravity = Gravity.CENTER
    val speed = label("", 13f, white, true, false)
    speed.gravity = Gravity.CENTER
    c.addView(big, LinearLayout.LayoutParams(MATCH, WRAP))
    c.addView(verdict, LinearLayout.LayoutParams(MATCH, WRAP))
    val cp = LinearLayout.LayoutParams(MATCH, dp(110))
    cp.topMargin = dp(10)
    cp.bottomMargin = dp(8)
    c.addView(chart, cp)
    c.addView(stats, vlp(0, 6))
    c.addView(speed, vlp(0, 10))

    fun fmt(v: Double): String = if (v < 0) "—" else String.format(java.util.Locale.US, "%.1f Mbps", v)

    var running = false
    val startBtn = button("شروع تست پینگ و سرعت") {}
    fun idle() {
        running = false
        startBtn.text = Tr.t("شروع تست پینگ و سرعت")
    }

    // Live ping to the chosen DNS, then the speed test. Runs on a background thread.
    fun measure(target: String, token: Int) {
        val samples = ArrayList<Int>()
        var n = 0
        while (n < 20 && token == pingToken) {
            val ms = dnsPingMs(target, 1500)
            n++
            ui {
                if (token == pingToken) {
                    samples.add(ms)
                    updatePingUi(samples, big, verdict, chart, stats)
                }
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (token == pingToken) {
            ui { if (token == pingToken) speed.text = "⬇️ " + Tr.t("در حال تست دانلود…") }
            val down = wgMeasureDownMbps()
            if (token == pingToken) {
                ui { if (token == pingToken) speed.text = "⬇️ " + fmt(down) + "    ⬆️ " + Tr.t("در حال تست آپلود…") }
            }
            val up = if (token == pingToken) wgMeasureUpMbps() else -1.0
            ui { if (token == pingToken) speed.text = "⬇️ " + fmt(down) + "    ⬆️ " + fmt(up) }
        }
        ui { if (token == pingToken) idle() }
    }

    startBtn.setOnClickListener {
        if (running) {
            pingToken++
            idle()
            return@setOnClickListener
        }
        // Only the DNS that is connected right now (chosen when the user connected; never shown).
        val connected = DnsVpnService.running && DnsVpnService.activeName == PRO_DNS_NAME
        val target = if (connected) prefs.getString("proBestIp", "") ?: "" else ""
        if (target.isEmpty()) {
            verdict.text = "⚠️ " + Tr.t("اول به DNS گیمینگ پرو وصل شو (دکمه‌ی «اتصال به بهترین DNS»)، بعد تست کن.")
            return@setOnClickListener
        }
        running = true
        startBtn.text = Tr.t("توقف")
        pingToken++
        val token = pingToken
        chart.setPoints(emptyList())
        speed.text = ""
        stats.text = ""
        big.text = "— ms"
        big.setTextColor(accent)
        verdict.text = Tr.t("در حال تست DNS متصل…")
        runBg { measure(target, token) }
    }
    c.addView(startBtn, LinearLayout.LayoutParams(MATCH, WRAP))
    content.addView(c, vlp(0, 4))
}
