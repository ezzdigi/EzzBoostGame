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
import android.util.Base64
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- V2Ray / Xray share links: import, test, open in a client ---------------

class V2Item(
    val name: String,
    val proto: String,
    val host: String,
    val port: Int,
    val raw: String,
    val remote: Boolean
)

private val V2_SCHEMES = setOf("vmess", "vless", "trojan", "ss", "hysteria2", "hy2", "tuic")

internal fun v2Decode(s: String): String? {
    var t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    if (t.isEmpty()) return null
    t = t.replace('-', '+').replace('_', '/')
    while (t.length % 4 != 0) t += "="
    return try {
        String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

internal fun parseV2Link(rawIn: String, nameOverride: String = ""): V2Item? {
    val raw = rawIn.trim()
    val si = raw.indexOf("://")
    if (si <= 0) return null
    val scheme = raw.substring(0, si).lowercase()
    if (!V2_SCHEMES.contains(scheme)) return null
    val rest = raw.substring(si + 3)

    if (scheme == "vmess") {
        val js = v2Decode(rest.substringBefore('#')) ?: return null
        return try {
            val o = JSONObject(js)
            val host = o.optString("add", "")
            var port = o.optString("port", "").trim().toIntOrNull() ?: 0
            if (port == 0) port = o.optInt("port", 0)
            val nm = o.optString("ps", "")
            if (host.isEmpty() || port < 1 || port > 65535) {
                null
            } else {
                val shown = if (nameOverride.isNotEmpty()) nameOverride else if (nm.isNotEmpty()) nm else host
                V2Item(shown, "vmess", host, port, raw, false)
            }
        } catch (_: Exception) {
            null
        }
    }

    var frag = ""
    var body = rest
    val hash = rest.indexOf('#')
    if (hash >= 0) {
        frag = rest.substring(hash + 1)
        body = rest.substring(0, hash)
    }
    val noQuery = body.substringBefore('?')
    var hp = ""
    val at = noQuery.lastIndexOf('@')
    if (at >= 0) {
        hp = noQuery.substring(at + 1).substringBefore('/')
    } else if (scheme == "ss") {
        val dec = v2Decode(noQuery.trimEnd('/')) ?: return null
        val a2 = dec.lastIndexOf('@')
        if (a2 < 0) return null
        hp = dec.substring(a2 + 1)
    } else {
        return null
    }
    var host = ""
    var portStr = ""
    if (hp.startsWith("[")) {
        val e = hp.indexOf("]")
        if (e < 0) return null
        host = hp.substring(1, e)
        portStr = hp.substring(e + 1).removePrefix(":")
    } else {
        val c = hp.lastIndexOf(':')
        if (c < 0) return null
        host = hp.substring(0, c)
        portStr = hp.substring(c + 1)
    }
    val port = portStr.trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port < 1 || port > 65535) return null
    val nm = try {
        URLDecoder.decode(frag, "UTF-8")
    } catch (_: Exception) {
        frag
    }
    val shown = if (nameOverride.isNotEmpty()) nameOverride else if (nm.isNotBlank()) nm.trim() else host
    return V2Item(shown, scheme, host, port, raw, false)
}

internal fun extractV2Links(text: String, depth: Int = 0): List<String> {
    val out = ArrayList<String>()
    for (t0 in text.split(Regex("\\s+"))) {
        val t = t0.trim().trim('"', ',', '\'')
        if (t.contains("://") && parseV2Link(t) != null) out.add(t)
    }
    if (out.isEmpty() && depth == 0) {
        val dec = v2Decode(text)
        if (dec != null && dec.contains("://")) return extractV2Links(dec, 1)
    }
    return out
}

// JSON format: {"configs":[{"name":"...","link":"vless://..."}]} or {"links":["vless://...", ...]}
internal fun parseV2Json(json: String): List<Pair<String, String>>? {
    return try {
        val o = JSONObject(json)
        val out = ArrayList<Pair<String, String>>()
        val arr = o.optJSONArray("configs")
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 100)) {
                val c = arr.optJSONObject(i) ?: continue
                val link = c.optString("link", "").trim()
                if (parseV2Link(link) == null) continue
                out.add(Pair(c.optString("name", "").trim().take(40), link))
            }
        }
        val ls = o.optJSONArray("links")
        if (ls != null) {
            for (i in 0 until minOf(ls.length(), 100)) {
                val link = ls.optString(i, "").trim()
                if (parseV2Link(link) != null) out.add(Pair("", link))
            }
        }
        out
    } catch (_: Exception) {
        null
    }
}

private fun v2Lines(raw: String, remote: Boolean): List<V2Item> {
    val out = ArrayList<V2Item>()
    for (line in raw.split("\n")) {
        if (line.isBlank()) continue
        val p = line.split("\t", limit = 2)
        val name = if (p.size == 2) p[0].trim() else ""
        val link = if (p.size == 2) p[1] else p[0]
        val it0 = parseV2Link(link, name) ?: continue
        out.add(V2Item(it0.name, it0.proto, it0.host, it0.port, it0.raw, remote))
    }
    return out
}

internal fun MainActivity.v2LocalItems(): List<V2Item> =
    v2Lines(prefs.getString("v2local", "") ?: "", false)

internal fun MainActivity.v2Items(): List<V2Item> {
    val out = ArrayList<V2Item>()
    out.addAll(v2Lines(prefs.getString("remoteV2", "") ?: "", true))
    out.addAll(v2LocalItems())
    return out
}

internal fun MainActivity.v2AddLocal(pairs: List<Pair<String, String>>): Int {
    val cur = (prefs.getString("v2local", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
    val have = HashSet<String>()
    for (l in cur) have.add(l.split("\t", limit = 2).last().trim())
    var added = 0
    for (p in pairs) {
        if (cur.size >= 200) break
        val link = p.second.trim()
        if (have.contains(link)) continue
        have.add(link)
        cur.add(p.first.replace("\t", " ").replace("\n", " ").trim() + "\t" + link)
        added++
    }
    prefs.edit().putString("v2local", cur.joinToString("\n")).apply()
    return added
}

internal fun MainActivity.v2RemoveLocal(raw: String) {
    val cur = (prefs.getString("v2local", "") ?: "").split("\n").filter { it.isNotBlank() }
    val kept = cur.filter { it.split("\t", limit = 2).last().trim() != raw.trim() }
    prefs.edit().putString("v2local", kept.joinToString("\n")).apply()
}

// text can be: share links (one or more), a base64 subscription blob, a JSON list,
// or an https subscription URL.
internal fun MainActivity.v2Import(text: String, onDone: (Int) -> Unit) {
    val t = text.trim()
    if (t.isEmpty()) {
        onDone(0)
        return
    }
    val isUrl = (t.startsWith("http://") || t.startsWith("https://")) &&
        !t.contains(" ") && !t.contains("\n")
    runBg {
        var source = t
        if (isUrl) source = httpGet(t, 10000, 400000) ?: ""
        val trimmed = source.trim()
        var pairs: List<Pair<String, String>> = emptyList()
        if (trimmed.startsWith("{")) {
            val parsed = parseV2Json(trimmed)
            if (parsed != null) pairs = parsed
        }
        if (pairs.isEmpty()) {
            pairs = extractV2Links(source).map { Pair("", it) }
        }
        val result = pairs
        ui { onDone(v2AddLocal(result)) }
    }
}

internal fun MainActivity.importV2FromUri(uri: Uri) {
    runBg {
        val text = readUriText(uri)
        ui {
            if (text == null || text.isBlank()) {
                toast(Tr.t("فایل خوانده نشد یا خیلی بزرگ است."))
            } else {
                v2Import(text) { n ->
                    if (n > 0) toast(Tr.t("کانفیگ اضافه شد:") + " $n") else toast(Tr.t("هیچ کانفیگ معتبری پیدا نشد"))
                    vpnRender?.invoke()
                }
            }
        }
    }
}

// Extracts usable links from a plain-text list (or a base64 subscription): keeps encrypted
// links only (tls / reality / vmess / ss), drops duplicates and unusable entries.
internal fun githubTextLinks(body: String, maxCandidates: Int): List<String> {
    val out = ArrayList<String>()
    val seenLinks = HashSet<String>()
    var text = body
    if (!text.contains("://")) {
        try {
            val d = v2Decode(text.trim()) ?: ""
            if (d.contains("://")) text = d
        } catch (_: Throwable) {
        }
    }
    for (raw in text.lines()) {
        val link = raw.trim()
        val lower = link.lowercase()
        val known = lower.startsWith("vless://") || lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") || lower.startsWith("ss://")
        if (!known) continue
        if (!seenLinks.add(link)) continue
        val encrypted = lower.startsWith("vmess://") || lower.startsWith("ss://") || lower.startsWith("trojan://") ||
            lower.contains("security=tls") || lower.contains("security=reality")
        if (!encrypted) continue
        if (link.contains("@0.0.0.0")) continue
        if (parseV2Link(link) == null) continue
        out.add(link)
        if (out.size >= maxCandidates) break
    }
    return out
}

// Keeps (in order) the first `keep` links whose server accepts a TCP connection.
// Dead public servers are dropped here instead of showing up as "connected but not working".
internal fun v2LiveLinks(links: List<String>, keep: Int): List<String> {
    if (links.isEmpty()) return links
    val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
    try {
        val futures = links.map { l ->
            pool.submit(java.util.concurrent.Callable<Boolean> {
                var ok = false
                try {
                    val f = parseV2Full(l)
                    if (f != null) {
                        val sock = java.net.Socket()
                        try {
                            sock.connect(java.net.InetSocketAddress(f.host, f.port), 3000)
                            ok = true
                        } finally {
                            try {
                                sock.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                ok
            })
        }
        val out = ArrayList<String>()
        for (i in links.indices) {
            val ok = try {
                futures[i].get(8, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {
                false
            }
            if (ok) out.add(links[i])
            if (out.size >= keep) break
        }
        return out
    } finally {
        pool.shutdownNow()
    }
}

internal fun MainActivity.refreshRemoteV2(force: Boolean, onDone: ((Int) -> Unit)?) {
    val last = prefs.getLong("remoteV2Try", 0L)
    if (!force && System.currentTimeMillis() - last < V2_REFRESH_HOURS * 3600L * 1000L) return
    prefs.edit().putLong("remoteV2Try", System.currentTimeMillis()).apply()
    runBg {
        var got: List<Pair<String, String>>? = null
        val sources = ArrayList<String>(V2_SOURCES)
        for (u in listUrls("v2ray.json")) {
            val b = httpGet(u, 6000, 600000)
            if (b != null && b.trimStart().startsWith("{")) {
                val p = parseV2Json(b)
                if (p != null) {
                    got = p
                    try {
                        val sa = JSONObject(b).optJSONArray("sources")
                        if (sa != null) {
                            for (i in 0 until minOf(sa.length(), 5)) {
                                val su = sa.optString(i, "").trim()
                                if (su.isNotEmpty() && !sources.contains(su)) sources.add(su)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    break
                }
            }
            if (b != null) lastHttpErr = "فایل JSON نیست"
        }
        // Public text lists: pulled in after the curated list.
        val extra = ArrayList<Pair<String, String>>()
        for (su in sources) {
            val b = httpGet(githubRawUrl(su), 10000, 1500000)
            if (b != null) {
                val live = v2LiveLinks(githubTextLinks(b, V2_SOURCE_CANDIDATES), V2_SOURCE_MAX)
                for (l in live) extra.add(Pair(V2_AUTO_NAME + " " + (extra.size + 1), l))
            }
        }
        var merged: List<Pair<String, String>>? = got
        if (extra.isNotEmpty()) {
            val all = ArrayList<Pair<String, String>>(got ?: emptyList())
            val links = HashSet<String>()
            for (x in all) links.add(x.second)
            for (x in extra) {
                if (all.size >= 100) break
                if (links.add(x.second)) all.add(x)
            }
            merged = all
        }
        val res = merged
        ui {
            var n = -1
            if (res != null) {
                val lines = res.joinToString("\n") {
                    it.first.replace("\t", " ").replace("\n", " ") + "\t" + it.second
                }
                markNewKeys("v2", res.map { it.first })
                prefs.edit()
                    .putString("remoteV2", lines)
                    .putLong("remoteV2Ts", System.currentTimeMillis())
                    .apply()
                n = res.size
            }
            vpnRender?.invoke()
            onDone?.invoke(n)
        }
    }
}

internal fun MainActivity.installedV2Clients(): List<Pair<String, String>> {
    val known = listOf(
        Pair("v2rayNG", "com.v2ray.ang"),
        Pair("Hiddify", "app.hiddify.com"),
        Pair("NekoBox", "moe.nb4a"),
        Pair("sing-box", "io.nekohasekai.sfa")
    )
    val out = ArrayList<Pair<String, String>>()
    for (k in known) {
        try {
            if (packageManager.getLaunchIntentForPackage(k.second) != null) out.add(k)
        } catch (_: Exception) {
        }
    }
    return out
}

// Short Persian message for core errors (no technical text, no addresses).
internal fun v2FriendlyError(raw: String): String {
    if (raw.any { it in '\u0600'..'\u06FF' }) return hideAddr(raw)
    val l = raw.lowercase()
    return when {
        l.contains("invalid key") || l.contains("bad key") -> "کلید این سرور نامعتبر است؛ لینک را دوباره از پنل بگیر."
        l.contains("timeout") || l.contains("refused") || l.contains("unreachable") || l.contains("i/o") || l.contains("dial") ->
            "سرور جواب نمی‌دهد؛ سرور دیگری را امتحان کن."
        else -> "اتصال برقرار نشد؛ سرور دیگری را امتحان کن."
    }
}

// Removes IPs and host:port pairs from text that is shown to the user.
internal fun hideAddr(s: String): String {
    var t = s
    t = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""").replace(t, "•••")
    t = Regex("""\[[0-9a-fA-F:]+\](?::\d{1,5})?""").replace(t, "•••")
    t = Regex("""\b[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+:\d{2,5}\b""").replace(t, "•••")
    return t
}

internal fun MainActivity.v2Status(): String {
    var out = ""
    if (!XrayCore.available()) {
        out = "هسته‌ی Xray داخل این نسخه نیست؛ برای اتصال مستقیم، نسخه‌ی Release را نصب کن."
    }
    if (V2VpnService.busy) {
        out += "\n⏳ ..."
    } else if (V2VpnService.running) {
        out += "\n🟢 V2Gaming متصل: " + V2VpnService.activeName
        if (V2VpnService.probe.isNotBlank()) out += "\n" + hideAddr(V2VpnService.probe)
    } else if (V2VpnService.lastError.isNotBlank()) {
        out += "\n⚠️ " + v2FriendlyError(V2VpnService.lastError)
    }
    return out.trim()
}

internal fun MainActivity.connectV2(item: V2Item) {
    if (!XrayCore.available()) {
        toast(Tr.t("هسته‌ی Xray در این نسخه نیست. نسخه‌ی Release را نصب کن."))
        return
    }
    val cfg = try {
        buildXrayConfig(item.raw, V2VpnService.pickProbePort())
    } catch (e: Exception) {
        toast(Tr.t("ساخت پیکربندی ناموفق:") + " " + (e.message ?: ""))
        return
    }
    pendingV2 = Triple(item.name, item.raw.hashCode().toString(), cfg)
    val prep = VpnService.prepare(this)
    if (prep != null) {
        try {
            startActivityForResult(prep, 4750)
        } catch (_: Exception) {
            toast(Tr.t("این دستگاه از VPN محلی پشتیبانی نمی‌کند."))
        }
    } else {
        startV2Service()
    }
}

internal fun MainActivity.startV2Service() {
    val p = pendingV2 ?: return
    val i = Intent(this, V2VpnService::class.java)
    i.action = V2VpnService.ACTION_START
    i.putExtra("name", p.first)
    i.putExtra("id", p.second)
    i.putExtra("config", p.third)
    i.putExtra("pkgs", scopedPackages(null).toTypedArray())
    try {
        startService(i)
        toast(Tr.t("در حال اتصال V2Ray…"))
    } catch (_: Exception) {
        toast(Tr.t("شروع سرویس ناموفق بود."))
    }
    handler.postDelayed({ vpnRender?.invoke() }, 1200)
    handler.postDelayed({ vpnRender?.invoke() }, 3500)
}

internal fun MainActivity.openInV2Client(item: V2Item) {
    try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("v2ray", item.raw))
    } catch (_: Exception) {
    }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(item.raw))
    if (view.resolveActivity(packageManager) != null) {
        try {
            startActivity(view)
            toast(Tr.t("لینک کپی شد و در کلاینت باز شد"))
            return
        } catch (_: Exception) {
        }
    }
    val clients = installedV2Clients()
    if (clients.isNotEmpty()) {
        val i = packageManager.getLaunchIntentForPackage(clients[0].second)
        if (i != null) {
            toast(Tr.t("لینک کپی شد؛ داخل کلاینت از کلیپ‌بورد وارد کن"))
            try {
                startActivity(i)
            } catch (_: Exception) {
            }
            return
        }
    }
    toast(Tr.t("کلاینت V2Ray نصب نیست؛ لینک کپی شد."))
}

internal fun MainActivity.buildV2Section(): (Boolean) -> Unit {
    section("V2Gaming")
    val vCard = card()
    val statusLbl = label("", 11.5f, accent, false, false)
    vCard.addView(statusLbl, vlp(6, 8))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL

    var key = ""
    fun render(force: Boolean) {
        val items = v2Items()
        val k = items.joinToString("|") { it.raw.hashCode().toString() } + "|" +
            prefs.getLong("remoteV2Ts", 0L) + "|" + V2VpnService.running + "|" +
            V2VpnService.busy + "|" + V2VpnService.activeId + "|" + V2VpnService.lastError + "|" + V2VpnService.probe + "|" +
            (System.currentTimeMillis() / 3600000L)
        val stText = v2Status()
        statusLbl.text = if (stText.isEmpty()) "" else Tr.t(stText)
        statusLbl.visibility = if (stText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (!force && k == key) return
        key = k
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(label("هنوز کانفیگی اضافه نشده.", 12.5f, muted))
            return
        }
        for (item in items) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(12), dp(10), dp(12), dp(10))
            box.background = rounded(panel2, 12, stroke)
            box.addView(label((if (item.remote) "🌐 " else "") + item.name + (if (item.remote && isNewKey("v2", item.name)) "  🆕 جدید" else ""), 13.5f, white, true, false))
            box.addView(label(item.proto.uppercase(), 11.5f, muted, false, false))
            val resLbl = label("", 12f, white, false, false)
            box.addView(resLbl)
            val br = LinearLayout(this)
            br.orientation = LinearLayout.HORIZONTAL
            val b1 = smallBtn("🧪 تست", false) {
                if (item.proto == "hysteria2" || item.proto == "hy2" || item.proto == "tuic") {
                    resLbl.text = Tr.t("این پروتکل UDP است؛ تست TCP معنی ندارد.")
                } else {
                    resLbl.text = Tr.t("در حال تست…")
                    runBg {
                        val ms = tcpPing(item.host, item.port, 3000)
                        ui {
                            resLbl.text = if (ms >= 0) "✅ " + Tr.t("وصل است") else "❌ " + Tr.t("وصل نیست")
                        }
                    }
                }
            }
            val myId = item.raw.hashCode().toString()
            val isActive = V2VpnService.running && V2VpnService.activeId == myId
            val b2 = if (!v2Supported(item.proto)) {
                smallBtn("اتصال با کلاینت", true) { openInV2Client(item) }
            } else if (isActive) {
                smallBtn("قطع اتصال", false) {
                    V2VpnService.requestStop(this)
                    handler.postDelayed({ vpnRender?.invoke() }, 700)
                }
            } else {
                smallBtn("اتصال", true) { connectV2(item) }
            }
            val m1 = LinearLayout.LayoutParams(WRAP, WRAP)
            m1.marginEnd = dp(6)
            val m2 = LinearLayout.LayoutParams(WRAP, WRAP)
            m2.marginEnd = dp(6)
            br.addView(b1, m1)
            br.addView(b2, m2)
            if (!item.remote) {
                val b4 = smallBtn("🗑", false) {
                    v2RemoveLocal(item.raw)
                    render(true)
                }
                val m4 = LinearLayout.LayoutParams(WRAP, WRAP)
                m4.marginStart = dp(6)
                br.addView(b4, m4)
            }
            box.addView(br, vlp(8, 0))
            list.addView(box, vlp(0, 6))
        }
    }

    fun importAndReport(txt: String) {
        toast(Tr.t("در حال بررسی…"))
        v2Import(txt) { n ->
            if (n > 0) {
                toast(Tr.t("کانفیگ اضافه شد:") + " $n")
                render(true)
            } else {
                toast(Tr.t("هیچ کانفیگ معتبری پیدا نشد"))
            }
        }
    }

    val bClip = smallBtn("📋 کلیپ‌بورد", false) {
        val txt = clipText().trim()
        if (txt.isEmpty()) toast(Tr.t("کلیپ‌بورد خالی است")) else importAndReport(txt)
    }
    val bFile = smallBtn("📁 فایل", false) { pickFile(4740) }
    val bOnline = smallBtn("🌐 لیست آنلاین", false) {
        refreshRemoteV2(true) { n ->
            toast(if (n >= 0) Tr.t("لیست آنلاین:") + " $n" else Tr.t("دریافت لیست ناموفق بود") + " (" + lastHttpErr + ")")
        }
    }
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    l1.marginEnd = dp(4)
    val l2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    l2.marginStart = dp(2)
    l2.marginEnd = dp(2)
    val l3 = LinearLayout.LayoutParams(0, WRAP, 1f)
    l3.marginStart = dp(4)
    row.addView(bClip, l1)
    row.addView(bFile, l2)
    row.addView(bOnline, l3)
    vCard.addView(row, vlp(0, 10))
    vCard.addView(list)
    content.addView(vCard, vlp(0, 4))
    render(true)
    return { force -> render(force) }
}
