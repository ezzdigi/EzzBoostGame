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
import android.system.StructStat
import android.util.Base64
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- V2Ray share link -> Xray JSON config (TUN inbound) ----------------------

class V2Full(
    val proto: String,
    val host: String,
    val port: Int,
    val user: String,
    val params: Map<String, String>,
    val name: String
)

internal fun v2Supported(proto: String): Boolean =
    proto == "vmess" || proto == "vless" || proto == "trojan" || proto == "ss"

private fun urlDec(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: Exception) {
    s
}

private fun qParams(q: String): Map<String, String> {
    val m = HashMap<String, String>()
    if (q.isEmpty()) return m
    for (part in q.split("&")) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val k = if (eq >= 0) part.substring(0, eq) else part
        val v = if (eq >= 0) part.substring(eq + 1) else ""
        m[urlDec(k)] = urlDec(v)
    }
    return m
}

internal fun parseV2Full(rawIn: String): V2Full? {
    val raw = rawIn.trim()
    val si = raw.indexOf("://")
    if (si <= 0) return null
    val scheme = raw.substring(0, si).lowercase()
    val rest = raw.substring(si + 3)

    if (scheme == "vmess") {
        val js = v2Decode(rest.substringBefore('#')) ?: return null
        return try {
            val o = JSONObject(js)
            val host = o.optString("add", "")
            var port = o.optString("port", "").trim().toIntOrNull() ?: 0
            if (port == 0) port = o.optInt("port", 0)
            if (host.isEmpty() || port < 1 || port > 65535) {
                null
            } else {
                val m = HashMap<String, String>()
                val keys = arrayOf("net", "type", "host", "path", "tls", "sni", "alpn", "fp", "aid", "scy")
                for (k in keys) {
                    val v = o.optString(k, "")
                    if (v.isNotEmpty()) m[k] = v
                }
                V2Full("vmess", host, port, o.optString("id", ""), m, o.optString("ps", host))
            }
        } catch (_: Exception) {
            null
        }
    }

    if (scheme != "vless" && scheme != "trojan" && scheme != "ss") return null
    var frag = ""
    var body = rest
    val hash = rest.indexOf('#')
    if (hash >= 0) {
        frag = rest.substring(hash + 1)
        body = rest.substring(0, hash)
    }
    val qIdx = body.indexOf('?')
    val noQuery = if (qIdx >= 0) body.substring(0, qIdx) else body
    val query = if (qIdx >= 0) body.substring(qIdx + 1) else ""
    var user = ""
    var hp = ""
    val at = noQuery.lastIndexOf('@')
    if (at >= 0) {
        val ui = noQuery.substring(0, at)
        hp = noQuery.substring(at + 1).substringBefore('/')
        user = if (scheme == "ss") {
            val plain = urlDec(ui)
            if (plain.contains(":")) plain else (v2Decode(ui) ?: return null)
        } else {
            urlDec(ui)
        }
    } else if (scheme == "ss") {
        val d = v2Decode(noQuery.trimEnd('/')) ?: return null
        val a2 = d.lastIndexOf('@')
        if (a2 < 0) return null
        user = d.substring(0, a2)
        hp = d.substring(a2 + 1)
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
    val nm = urlDec(frag).trim()
    return V2Full(scheme, host, port, user, qParams(query), if (nm.isEmpty()) host else nm)
}

private fun streamSettings(f: V2Full): JSONObject {
    val p = f.params
    var net = (p["type"] ?: p["net"] ?: "tcp").lowercase()
    if (f.proto == "vmess") net = (p["net"] ?: "tcp").lowercase()
    if (net == "splithttp") net = "xhttp"
    if (net == "http") net = "h2"
    if (net == "kcp" || net == "mkcp" || net == "quic") {
        throw IllegalArgumentException("انتقال $net پشتیبانی نمی‌شود")
    }
    val hostHdr = p["host"] ?: ""
    val path = p["path"] ?: "/"
    val s = JSONObject()
    s.put("network", net)

    var sec = (p["security"] ?: "none").lowercase()
    if (f.proto == "vmess") sec = if ((p["tls"] ?: "").lowercase() == "tls") "tls" else "none"
    if (sec == "tls") {
        s.put("security", "tls")
        val t = JSONObject()
        val sni = (p["sni"] ?: p["peer"] ?: "").ifBlank { hostHdr }
        if (sni.isNotBlank()) t.put("serverName", sni)
        val fp = p["fp"] ?: ""
        if (fp.isNotBlank()) t.put("fingerprint", fp)
        val alpn = p["alpn"] ?: ""
        if (alpn.isNotBlank()) {
            val a = JSONArray()
            for (x in alpn.split(",")) if (x.isNotBlank()) a.put(x.trim())
            t.put("alpn", a)
        }
        s.put("tlsSettings", t)
    } else if (sec == "reality") {
        s.put("security", "reality")
        val r = JSONObject()
        r.put("serverName", p["sni"] ?: "")
        r.put("fingerprint", (p["fp"] ?: "").ifBlank { "chrome" })
        r.put("publicKey", p["pbk"] ?: "")
        r.put("shortId", p["sid"] ?: "")
        r.put("spiderX", p["spx"] ?: "")
        s.put("realitySettings", r)
    }

    when (net) {
        "ws" -> {
            val w = JSONObject()
            w.put("path", path)
            if (hostHdr.isNotBlank()) w.put("headers", JSONObject().put("Host", hostHdr))
            s.put("wsSettings", w)
        }
        "grpc" -> {
            val g = JSONObject()
            g.put("serviceName", p["serviceName"] ?: (p["path"] ?: ""))
            g.put("multiMode", (p["mode"] ?: "") == "multi")
            s.put("grpcSettings", g)
        }
        "httpupgrade" -> {
            val h = JSONObject()
            h.put("path", path)
            if (hostHdr.isNotBlank()) h.put("host", hostHdr)
            s.put("httpupgradeSettings", h)
        }
        "xhttp" -> {
            val x = JSONObject()
            x.put("path", path)
            if (hostHdr.isNotBlank()) x.put("host", hostHdr)
            x.put("mode", (p["mode"] ?: "").ifBlank { "auto" })
            val extra = p["extra"] ?: ""
            if (extra.isNotBlank()) {
                try {
                    x.put("extra", JSONObject(extra))
                } catch (_: Exception) {
                }
            }
            s.put("xhttpSettings", x)
        }
        "h2" -> {
            val h = JSONObject()
            h.put("path", path)
            if (hostHdr.isNotBlank()) h.put("host", JSONArray().put(hostHdr))
            s.put("httpSettings", h)
        }
        else -> {
            if ((p["headerType"] ?: p["type2"] ?: "") == "http" || (f.proto == "vmess" && (p["type"] ?: "") == "http")) {
                val req = JSONObject()
                req.put("path", JSONArray().put(path))
                if (hostHdr.isNotBlank()) req.put("headers", JSONObject().put("Host", JSONArray().put(hostHdr)))
                val hd = JSONObject()
                hd.put("type", "http")
                hd.put("request", req)
                s.put("tcpSettings", JSONObject().put("header", hd))
            }
        }
    }
    return s
}

// Returns the full Xray JSON. Throws IllegalArgumentException (Persian message) on problems.
internal fun buildXrayConfig(raw: String): String {
    val f = parseV2Full(raw) ?: throw IllegalArgumentException("لینک نامعتبر یا پشتیبانی‌نشده")
    val p = f.params
    val ob = JSONObject()
    ob.put("tag", "proxy")
    when (f.proto) {
        "vmess" -> {
            ob.put("protocol", "vmess")
            val u = JSONObject()
            u.put("id", f.user)
            u.put("alterId", (p["aid"] ?: "0").toIntOrNull() ?: 0)
            u.put("security", (p["scy"] ?: "").ifBlank { "auto" })
            val vn = JSONObject()
            vn.put("address", f.host)
            vn.put("port", f.port)
            vn.put("users", JSONArray().put(u))
            ob.put("settings", JSONObject().put("vnext", JSONArray().put(vn)))
        }
        "vless" -> {
            ob.put("protocol", "vless")
            val u = JSONObject()
            u.put("id", f.user)
            u.put("encryption", (p["encryption"] ?: "").ifBlank { "none" })
            val flow = p["flow"] ?: ""
            if (flow.isNotBlank()) u.put("flow", flow)
            val vn = JSONObject()
            vn.put("address", f.host)
            vn.put("port", f.port)
            vn.put("users", JSONArray().put(u))
            ob.put("settings", JSONObject().put("vnext", JSONArray().put(vn)))
        }
        "trojan" -> {
            ob.put("protocol", "trojan")
            val sv = JSONObject()
            sv.put("address", f.host)
            sv.put("port", f.port)
            sv.put("password", f.user)
            ob.put("settings", JSONObject().put("servers", JSONArray().put(sv)))
        }
        "ss" -> {
            if ((p["plugin"] ?: "").isNotBlank()) throw IllegalArgumentException("پلاگین Shadowsocks پشتیبانی نمی‌شود")
            ob.put("protocol", "shadowsocks")
            val c = f.user.indexOf(':')
            if (c <= 0) throw IllegalArgumentException("روش یا رمز Shadowsocks نامعتبر است")
            val sv = JSONObject()
            sv.put("address", f.host)
            sv.put("port", f.port)
            sv.put("method", f.user.substring(0, c))
            sv.put("password", f.user.substring(c + 1))
            ob.put("settings", JSONObject().put("servers", JSONArray().put(sv)))
        }
        else -> throw IllegalArgumentException("این پروتکل داخل اپ پشتیبانی نمی‌شود")
    }
    ob.put("streamSettings", streamSettings(f))

    val tun = JSONObject()
    tun.put("tag", "tun-in")
    tun.put("protocol", "tun")
    tun.put("listen", "127.0.0.1")
    tun.put("port", 10808)
    val ts = JSONObject()
    ts.put("name", "xray0")
    ts.put("mtu", 1500)
    ts.put("userLevel", 0)
    tun.put("settings", ts)
    val sn = JSONObject()
    sn.put("enabled", true)
    sn.put("destOverride", JSONArray().put("http").put("tls"))
    tun.put("sniffing", sn)

    val direct = JSONObject()
    direct.put("tag", "direct")
    direct.put("protocol", "freedom")
    direct.put("settings", JSONObject())
    val block = JSONObject()
    block.put("tag", "block")
    block.put("protocol", "blackhole")
    block.put("settings", JSONObject())

    val priv = JSONArray()
    for (c in arrayOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4"
    )) priv.put(c)
    val rule = JSONObject()
    rule.put("type", "field")
    rule.put("ip", priv)
    rule.put("outboundTag", "direct")
    val routing = JSONObject()
    routing.put("domainStrategy", "AsIs")
    routing.put("rules", JSONArray().put(rule))

    val root = JSONObject()
    root.put("log", JSONObject().put("loglevel", "warning"))
    root.put("inbounds", JSONArray().put(tun))
    root.put("outbounds", JSONArray().put(ob).put(direct).put(block))
    root.put("routing", routing)
    return root.toString()
}
