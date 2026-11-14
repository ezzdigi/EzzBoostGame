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

// ----- "DNS گیمینگ پرو" card (inside the Pro Gaming tab) -----------------------------
//
// The DNS list lives in the project: dns-gaming-pro.json (repo root), synced at most hourly.
// One tap does everything:  1) detect the phone's public IP and register it automatically,
// 2) test every DNS in the list, 3) connect to the fastest one.
//
// IP registration: the app POSTs its token to api.php?a=ip on the subscription server, which records the
// phone's public IP itself. If registration fails, nothing is tested or connected.

internal class ProDnsItem(val name: String, val servers: List<String>, val registerUrl: String)

private fun MainActivity.proDnsParse(): Pair<String, List<ProDnsItem>> {
    val out = ArrayList<ProDnsItem>()
    var globalUrl = ""
    try {
        val o = org.json.JSONObject(prefs.getString("proDnsJson", "") ?: "")
        globalUrl = o.optJSONObject("register")?.optString("url", "") ?: ""
        val arr = o.optJSONArray("providers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val sv = ArrayList<String>()
                val sa = p.optJSONArray("servers")
                if (sa != null) for (j in 0 until sa.length()) {
                    val v = sa.optString(j).trim()
                    if (v.isNotEmpty() && isIpv4(v)) sv.add(v)
                }
                val nm = p.optString("name").trim()
                if (nm.isNotEmpty() && sv.isNotEmpty()) out.add(ProDnsItem(nm, sv.take(4), p.optString("registerUrl", "").trim()))
            }
        }
    } catch (_: Throwable) {
    }
    return Pair(globalUrl.trim(), out)
}

internal fun MainActivity.proDnsItems(): List<ProDnsItem> = proDnsParse().second

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

// Asks the subscription server (api.php?a=ip) to record this phone's public IP. The server reads the IP
// from the connection itself, so it cannot be faked, and the request leaves over the real network.
// Returns Pair(ok, ip or error text).
internal fun MainActivity.proRegisterIp(): Pair<Boolean, String> {
    val token = subToken()
    if (token.isEmpty()) return Pair(false, "no token")
    val net = proPhysicalNetwork() ?: return Pair(false, "no network")
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
            val ip = org.json.JSONObject(conn.inputStream.bufferedReader().readText()).getString("ip")
            prefs.edit().putString("proDnsRegIp", ip).putLong("proDnsRegTs", System.currentTimeMillis()).apply()
            Pair(true, ip)
        } else {
            Pair(false, "HTTP $code")
        }
    } catch (e: Exception) {
        Pair(false, e.message ?: "error")
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

// Pings every provider in parallel; returns name -> best ms (-1 when unreachable). Blocking.
private fun MainActivity.proDnsPingAll(items: List<ProDnsItem>): Map<String, Int> {
    val res = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val threads = ArrayList<Thread>()
    for (it in items) {
        val th = Thread {
            var best = -1
            for (s in it.servers.take(2)) {
                val ms = dnsPingMs(s, 1000)
                if (ms >= 0 && (best < 0 || ms < best)) best = ms
            }
            res[it.name] = best
        }
        th.isDaemon = true
        th.start()
        threads.add(th)
    }
    for (th in threads) {
        try {
            th.join(2500)
        } catch (_: Throwable) {
        }
    }
    return res
}

private class ProScore(val item: ProDnsItem, val ms: Int, val rank: Int)

// Two ping rounds per DNS (a single sample is too noisy). ms = value shown (-1 = no answer);
// rank = value used to choose: a DNS that answered only once is penalised.
private fun MainActivity.proScores(items: List<ProDnsItem>): List<ProScore> {
    val r1 = proDnsPingAll(items)
    val r2 = proDnsPingAll(items)
    return items.map { p ->
        val a = r1[p.name] ?: -1
        val b = r2[p.name] ?: -1
        when {
            a >= 0 && b >= 0 -> ProScore(p, (a + b) / 2, (a + b) / 2)
            a >= 0 || b >= 0 -> ProScore(p, maxOf(a, b), maxOf(a, b) + 200)
            else -> ProScore(p, -1, -1)
        }
    }
}

// Gives the server a moment to apply the new IP, then tests; retries once if nothing answered. Blocking.
private fun MainActivity.proFindBest(items: List<ProDnsItem>): List<ProScore> {
    var res: List<ProScore> = emptyList()
    for (attempt in 0..1) {
        try {
            Thread.sleep(if (attempt == 0) 1500L else 3000L)
        } catch (_: Throwable) {
        }
        res = proScores(items)
        if (res.any { it.rank >= 0 }) break
    }
    return res
}

@Volatile
private var proFlowBusy = false

// Runs on every network change (see ProNetWatch): registers the new IP; if the Pro DNS is connected,
// re-picks the best DNS for that IP and reconnects. Never asks for VPN permission from the background.
internal fun MainActivity.proAutoFlow() {
    if (proFlowBusy || subToken().isEmpty()) return
    val items = proDnsItems()
    proFlowBusy = true
    runBg {
        try {
            val prevIp = prefs.getString("proDnsRegIp", "") ?: ""
            val reg = proRegisterIp()
            if (!reg.first) return@runBg
            if (reg.second == prevIp || !DnsVpnService.running || items.isEmpty()) return@runBg
            val best = proFindBest(items).filter { it.rank >= 0 }.minByOrNull { it.rank } ?: return@runBg
            ui {
                if (VpnService.prepare(this) == null) {
                    connectDns(proToProvider(best.item))
                    handler.postDelayed({ vpnRefresh?.invoke() }, 900)
                }
            }
        } finally {
            proFlowBusy = false
        }
    }
}

private const val PRO_DNS_NAME = "DNS گیمینگ پرو"

private fun proToProvider(it: ProDnsItem): DnsProvider = DnsProvider(
    PRO_DNS_NAME,
    it.servers[0],
    it.servers.getOrElse(1) { "" },
    it.servers.getOrElse(2) { "" },
    it.servers.getOrElse(3) { "" }
)

// Builds the card and returns its render function (called with force = false on refresh ticks).
internal fun MainActivity.buildProDnsCard(): (Boolean) -> Unit {
    section("🛡️ DNS گیمینگ پرو")
    val cardV = subGlass(14)
    val status = label("", 15f, white, true, false)
    val msg = label("", 12.5f, white, false, false)
    cardV.addView(status)
    cardV.addView(msg, vlp(2, 6))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL

    var busy = false
    var key = ""
    var render: (Boolean) -> Unit = {}

    fun setMsg(t: String) {
        msg.text = t
    }


    fun connectBest() {
        if (busy) return
        val items = proDnsItems()
        if (items.isEmpty()) {
            toast(Tr.t("لیست DNS خالی است؛ بعداً دوباره امتحان کن."))
            return
        }
        busy = true
        setMsg("⏳ " + Tr.t("۱/۳ در حال ثبت IP…"))
        runBg {
            val reg = proRegisterIp()
            if (!reg.first) {
                ui {
                    busy = false
                    setMsg("❌ " + Tr.t("ثبت IP ناموفق بود؛ اینترنت و ورود به حساب را چک کن و دوباره امتحان کن.") + " (" + reg.second + ")")
                    render(true)
                }
                return@runBg
            }
            ui { setMsg("⏳ " + Tr.t("۲/۳ در حال تست DNSها…")) }
            val res = proFindBest(items)
            val best = res.filter { it.rank >= 0 }.minByOrNull { it.rank }
            ui {
                busy = false
                if (best == null) {
                    setMsg("❌ " + Tr.t("هیچ DNS ای جواب نداد. اینترنت را چک کن."))
                } else {
                    setMsg("✅ " + Tr.t("به بهترین سرور وصل شدی."))
                    connectDns(proToProvider(best.item))
                    handler.postDelayed({ vpnRefresh?.invoke() }, 900)
                }
                render(true)
            }
        }
    }

    val btn = button("⚡ " + Tr.t("اتصال به بهترین DNS"), true) { connectBest() }
    cardV.addView(btn, vlp(4, 6))
    cardV.addView(list)
    content.addView(cardV, vlp(0, 4))

    render = fun(force: Boolean) {
        val running = DnsVpnService.running
        val active = DnsVpnService.activeName
        status.text = if (running) Tr.t("🟢 DNS متصل: ") + active else Tr.t("⚪ DNS گیمینگ قطع")
        status.setTextColor(if (running) good else white)
        btn.text = if (busy) "⏳ ..." else if (running) Tr.t("🔄 اتصال دوباره به بهترین DNS") else "⚡ " + Tr.t("اتصال به بهترین DNS")
        val k = "$running|$active"
        if (!force && k == key) return
        key = k
        list.removeAllViews()
        if (running) {
            list.addView(smallBtn("قطع اتصال DNS", false) { stopDns() }, vlp(4, 6))
        } else if (proDnsItems().isEmpty()) {
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
