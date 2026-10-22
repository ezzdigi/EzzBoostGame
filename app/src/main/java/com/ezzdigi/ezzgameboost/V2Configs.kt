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

internal fun MainActivity.refreshRemoteV2(force: Boolean, onDone: ((Int) -> Unit)?) {
    val last = prefs.getLong("remoteV2Try", 0L)
    if (!force && System.currentTimeMillis() - last < 12L * 3600L * 1000L) return
    prefs.edit().putLong("remoteV2Try", System.currentTimeMillis()).apply()
    runBg {
        var got: List<Pair<String, String>>? = null
        for (u in remoteUrls(ghRepo(), "v2ray.json")) {
            val b = httpGet(u, 6000, 600000)
            if (b != null && b.trimStart().startsWith("{")) {
                val p = parseV2Json(b)
                if (p != null) {
                    got = p
                    break
                }
            }
            if (b != null) lastHttpErr = "فایل JSON نیست"
        }
        val res = got
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

internal fun MainActivity.v2Status(): String {
    val c = installedV2Clients()
    val s1 = if (c.isEmpty()) {
        "کلاینت V2Ray نصب نیست"
    } else {
        "کلاینت نصب‌شده: " + c.joinToString(", ") { it.first }
    }
    val ts = prefs.getLong("remoteV2Ts", 0L)
    val s2 = if (ts == 0L) {
        "لیست آنلاین: هنوز به‌روز نشده"
    } else {
        val n = v2Lines(prefs.getString("remoteV2", "") ?: "", true).size
        val d = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ts))
        "لیست آنلاین: $n V2 • آخرین به‌روزرسانی: $d"
    }
    val core = if (XrayCore.available()) {
        val v = XrayCore.version()
        "هسته‌ی Xray: " + (if (v.isBlank()) "OK" else v)
    } else {
        "هسته‌ی Xray داخل این نسخه نیست؛ برای اتصال مستقیم، نسخه‌ی Release را نصب کن."
    }
    var st = ""
    if (V2VpnService.busy) {
        st = "\n⏳ ..."
    } else if (V2VpnService.running) {
        st = "\n🟢 V2Ray متصل: " + V2VpnService.activeName
    } else if (V2VpnService.lastError.isNotBlank()) {
        st = "\n⚠️ خطای V2Ray: " + V2VpnService.lastError
    }
    return core + st + "\n" + s1 + "\n" + s2
}

internal fun MainActivity.connectV2(item: V2Item) {
    if (!XrayCore.available()) {
        toast(Tr.t("هسته‌ی Xray در این نسخه نیست. نسخه‌ی Release را نصب کن."))
        return
    }
    val cfg = try {
        buildXrayConfig(item.raw)
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
    section("V2RAY / XRAY")
    val vCard = card()
    vCard.addView(
        label(
            "کانفیگ‌های V2Ray/Xray را از کلیپ‌بورد، فایل (لینک‌ها یا لینک اشتراک) یا لیست آنلاین اضافه کن، تست کن و مستقیم با دکمه‌ی «اتصال» وصل شو (vmess، vless، trojan، ss). پروتکل‌های UDP مثل hysteria2 و tuic از طریق کلاینت V2Ray باز می‌شوند. این اتصال فقط برای بازی‌ها کار می‌کند و بقیه‌ی برنامه‌ها مستقیم می‌روند.",
            12f, muted
        )
    )
    val statusLbl = label("", 11.5f, accent, false, false)
    vCard.addView(statusLbl, vlp(6, 8))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL

    var key = ""
    fun render(force: Boolean) {
        val items = v2Items()
        val k = items.joinToString("|") { it.raw.hashCode().toString() } + "|" +
            prefs.getLong("remoteV2Ts", 0L) + "|" + V2VpnService.running + "|" +
            V2VpnService.busy + "|" + V2VpnService.activeId + "|" + V2VpnService.lastError + "|" +
            (System.currentTimeMillis() / 3600000L)
        statusLbl.text = Tr.t(v2Status())
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
            box.addView(label(item.proto.uppercase() + " • " + item.host + ":" + item.port, 11.5f, muted, false, false))
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
                            resLbl.text = if (ms >= 0) "✅ $ms ms" else "✕ " + Tr.t("پاسخ نداد")
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
    vCard.addView(
        label(
            "فقط لینک‌های اشتراکی (vmess، vless، trojan، ss، hysteria2، tuic) یا لینک اشتراک پشتیبانی می‌شوند. فایل JSON کامل Xray را مستقیم داخل کلاینت وارد کن.",
            11.5f, muted
        ), vlp(8, 0)
    )
    content.addView(vCard, vlp(0, 4))
    render(true)
    return { force -> render(force) }
}
