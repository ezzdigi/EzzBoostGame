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
import android.app.AlertDialog
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- Online DNS list, update check, WireGuard scope --------------------

internal fun httpGet(url: String, timeoutMs: Int = 8000, maxBytes: Int = 300000): String? {
    var holder: HttpURLConnection? = null
    try {
        val c = URL(url).openConnection() as HttpURLConnection
        holder = c
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "EzzGameBOOST")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) return null
        val input = c.inputStream
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        input.close()
        return out.toString("UTF-8")
    } catch (_: Exception) {
        return null
    } finally {
        try {
            holder?.disconnect()
        } catch (_: Exception) {
        }
    }
}

// ----- 1) Online DNS list ----------------------------------------------------

internal fun MainActivity.applyRemoteDns(json: String): Int {
    val obj = JSONObject(json)
    val arr = obj.optJSONArray("providers") ?: return -1
    val lines = ArrayList<String>()
    for (i in 0 until minOf(arr.length(), 60)) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").replace("\t", " ").replace("\n", " ").trim().take(40)
        if (name.isEmpty()) continue
        val sv = o.optJSONArray("servers") ?: continue
        val ips = ArrayList<String>()
        for (k in 0 until minOf(sv.length(), 4)) {
            val ip = sv.optString(k, "").trim()
            if (isIpv4(ip) || isIpv6(ip)) ips.add(ip)
        }
        if (ips.isEmpty()) continue
        lines.add(name + "\t" + ips.joinToString("\t"))
    }
    if (lines.isEmpty()) return -1
    val oldSel = selectedDns().name
    prefs.edit()
        .putString("remoteDns", lines.joinToString("\n"))
        .putLong("remoteDnsTs", System.currentTimeMillis())
        .apply()
    dnsCache = null
    val idx = dnsProviders.indexOfFirst { it.name == oldSel }
    prefs.edit().putInt("dnsSel", if (idx >= 0) idx else 0).apply()
    return lines.size
}

internal fun MainActivity.refreshRemoteDns(force: Boolean, onDone: ((Int) -> Unit)?) {
    val last = prefs.getLong("remoteDnsTry", 0L)
    if (!force && System.currentTimeMillis() - last < 12L * 3600L * 1000L) return
    prefs.edit().putLong("remoteDnsTry", System.currentTimeMillis()).apply()
    runBg {
        val body = httpGet(REMOTE_DNS_URL)
        ui {
            var n = -1
            if (body != null) {
                n = try {
                    applyRemoteDns(body)
                } catch (_: Exception) {
                    -1
                }
            }
            netRefresh?.invoke()
            onDone?.invoke(n)
        }
    }
}

internal fun MainActivity.remoteDnsStatus(): String {
    val ts = prefs.getLong("remoteDnsTs", 0L)
    if (ts == 0L) return "لیست آنلاین: هنوز به‌روز نشده"
    val n = DnsList.remote(this).size
    val d = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ts))
    return "لیست آنلاین: $n DNS • آخرین به‌روزرسانی: $d"
}

// ----- 7) Update check -------------------------------------------------------

internal fun MainActivity.currentVersion(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
} catch (_: Exception) {
    "0"
}

internal fun versionParts(v: String): List<Int>? {
    val m = Regex("^[vV]?(\\d+(?:\\.\\d+)*)").find(v.trim()) ?: return null
    return m.groupValues[1].split(".").map { it.toIntOrNull() ?: 0 }
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote) ?: return false
    val b = versionParts(local) ?: return false
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

internal fun MainActivity.checkUpdate(manual: Boolean, onDone: ((String) -> Unit)?) {
    val last = prefs.getLong("updTry", 0L)
    if (!manual && System.currentTimeMillis() - last < 12L * 3600L * 1000L) return
    prefs.edit().putLong("updTry", System.currentTimeMillis()).apply()
    runBg {
        val body = httpGet(RELEASES_API)
        var msg = "fail"
        if (body != null) {
            try {
                val o = JSONObject(body)
                val tag = o.optString("tag_name", "")
                var url = o.optString("html_url", RELEASES_PAGE)
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val u = a.optString("browser_download_url", "")
                        if (u.endsWith(".apk")) {
                            url = u
                            break
                        }
                    }
                }
                val notes = o.optString("body", "").trim().take(500)
                if (tag.isNotEmpty()) {
                    prefs.edit()
                        .putString("updTag", tag)
                        .putString("updUrl", url)
                        .putString("updNotes", notes)
                        .apply()
                    msg = if (isNewerVersion(tag, currentVersion())) "new" else "latest"
                }
            } catch (_: Exception) {
                msg = "fail"
            }
        }
        ui {
            onDone?.invoke(msg)
            if (msg == "new") homeRefresh?.invoke()
        }
    }
}

internal fun MainActivity.addUpdateBanner() {
    val tag = prefs.getString("updTag", "") ?: ""
    if (tag.isEmpty() || !isNewerVersion(tag, currentVersion())) return
    val url = prefs.getString("updUrl", RELEASES_PAGE) ?: RELEASES_PAGE
    val c = card()
    c.background = rounded(accentAlpha(40), 16, accent)
    c.addView(label(Tr.t("🆕 نسخه جدید موجود است:") + " " + tag, 14f, white, true, false))
    c.addView(button("⬇️ دانلود آپدیت") { openUrl(url) }, vlp(8, 0))
    content.addView(c, vlp(0, 6))
}

internal fun MainActivity.autoChecks() {
    refreshRemoteDns(false, null)
    checkUpdate(false, null)
}

// ----- 3) WireGuard only for games ------------------------------------------

internal fun MainActivity.wgScopeGames(base: String): Boolean =
    prefs.getBoolean("wgmode_$base", false)

internal fun MainActivity.wgIncludePackages(base: String): List<String> {
    val set = LinkedHashSet<String>()
    for (a in loadApps()) if (a.isGame) set.add(a.pkg)
    set.addAll(favoriteSet())
    for (k in prefs.all.keys) {
        if (k.startsWith("prof_")) {
            val pkg = k.removePrefix("prof_")
            val p = getProfile(pkg)
            if (p != null && p.wg == base) set.add(pkg)
        }
    }
    val out = ArrayList<String>()
    for (p in set) {
        try {
            packageManager.getApplicationInfo(p, 0)
            out.add(p)
        } catch (_: Exception) {
        }
    }
    return out
}

internal fun MainActivity.wgScopedConfig(base: String, text: String): String {
    if (!wgScopeGames(base)) return text
    val pkgs = wgIncludePackages(base)
    if (pkgs.isEmpty()) return text
    val out = ArrayList<String>()
    var inserted = false
    for (line in text.split("\n")) {
        val t = line.trim()
        val low = t.lowercase()
        if (low.startsWith("includedapplications") || low.startsWith("excludedapplications")) continue
        out.add(line)
        if (!inserted && t.equals("[Interface]", ignoreCase = true)) {
            out.add("IncludedApplications = " + pkgs.joinToString(", "))
            inserted = true
        }
    }
    return if (inserted) out.joinToString("\n") else text
}
