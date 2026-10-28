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

@Volatile
var lastHttpErr = ""

internal fun httpPost(url: String, form: String, timeoutMs: Int = 10000): Pair<Int, String>? {
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val st = if (code in 200..299) c.inputStream else c.errorStream
        val text = st?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        Pair(code, text.take(20000))
    } catch (e: Exception) {
        lastHttpErr = when (e) {
            is java.net.UnknownHostException -> "آدرس پیدا نشد (اینترنت یا فیلتر)"
            is java.net.SocketTimeoutException -> "timeout (احتمالاً فیلتر)"
            else -> e.javaClass.simpleName
        }
        null
    }
}

internal fun httpGet(url: String, timeoutMs: Int = 8000, maxBytes: Int = 300000): String? {
    var holder: HttpURLConnection? = null
    try {
        val c = URL(url).openConnection() as HttpURLConnection
        holder = c
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "EzzGameBOOST")
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode != 200) {
            lastHttpErr = "HTTP " + c.responseCode
            return null
        }
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
        return out.toString("UTF-8").removePrefix("\uFEFF")
    } catch (e: Exception) {
        lastHttpErr = when (e) {
            is java.net.UnknownHostException -> "آدرس پیدا نشد (اینترنت یا فیلتر)"
            is java.net.SocketTimeoutException -> "timeout (احتمالاً فیلتر)"
            else -> e.javaClass.simpleName
        }
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
    markNewKeys("dns", lines.map { it.substringBefore("\t") })
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
        var got: String? = null
        for (u in listUrls("dns.json")) {
            val b = httpGet(u, 6000, 600000)
            if (b != null && b.trimStart().startsWith("{")) {
                got = b
                break
            }
            if (b != null) lastHttpErr = "فایل JSON نیست"
        }
        val body = got
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

class ReleaseInfo(val tag: String, val url: String, val notes: String)

private fun releaseFromJson(o: JSONObject): ReleaseInfo? {
    val tag = o.optString("tag_name", "")
    if (tag.isEmpty()) return null
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
    return ReleaseInfo(tag, url, o.optString("body", "").trim().take(500))
}

// Tries three sources in turn, because api.github.com is often blocked or rate-limited and
// "releases/latest" returns 404 when the newest release is a pre-release:
//   1) /releases/latest   2) /releases?per_page=1   3) the releases.atom feed on github.com
internal fun MainActivity.fetchLatestRelease(): ReleaseInfo? {
    // Own server first (GitHub is blocked on many networks): SUB_BASE/update.json
    try {
        val own = httpGet(SUB_BASE + "/update.json", 8000, 20000)
        if (own != null && own.trimStart().startsWith("{")) {
            val o = JSONObject(own)
            val v = o.optString("version", "").trim()
            if (v.isNotEmpty()) {
                val u = o.optString("url", "").trim()
                return ReleaseInfo(v, if (u.isNotEmpty()) u else RELEASES_PAGE, o.optString("notes", "").trim().take(500))
            }
        }
    } catch (_: Exception) {
    }
    val repo = ghRepo()
    try {
        val b1 = httpGet("https://api.github.com/repos/$repo/releases/latest", 8000, 1000000)
        if (b1 != null) {
            val r = releaseFromJson(JSONObject(b1))
            if (r != null) return r
        }
    } catch (_: Exception) {
    }
    try {
        val b2 = httpGet("https://api.github.com/repos/$repo/releases?per_page=1", 8000, 1000000)
        if (b2 != null) {
            val arr = org.json.JSONArray(b2)
            if (arr.length() > 0) {
                val r = releaseFromJson(arr.getJSONObject(0))
                if (r != null) return r
            }
        }
    } catch (_: Exception) {
    }
    try {
        val b3 = httpGet("https://github.com/$repo/releases.atom", 8000, 1000000)
        if (b3 != null) {
            val m = Regex("href=\"[^\"]*/releases/tag/([^\"]+)\"").find(b3)
            if (m != null) {
                val tag = android.net.Uri.decode(m.groupValues[1])
                return ReleaseInfo(tag, "https://github.com/$repo/releases/tag/" + m.groupValues[1], "")
            }
        }
    } catch (_: Exception) {
    }
    return null
}

// onDone gets "new", "latest", or "fail|<reason>"
internal fun MainActivity.checkUpdate(manual: Boolean, onDone: ((String) -> Unit)?) {
    val last = prefs.getLong("updTry", 0L)
    if (!manual && System.currentTimeMillis() - last < 12L * 3600L * 1000L) return
    prefs.edit().putLong("updTry", System.currentTimeMillis()).apply()
    runBg {
        var msg = "fail|" + lastHttpErr
        val r = fetchLatestRelease()
        if (r != null) {
            prefs.edit()
                .putString("updTag", r.tag)
                .putString("updUrl", r.url)
                .putString("updNotes", r.notes)
                .apply()
            msg = if (isNewerVersion(r.tag, currentVersion())) "new" else "latest"
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
    refreshRemoteV2(false, null)
    val lastWg = prefs.getLong("remoteWgTry", 0L)
    if (System.currentTimeMillis() - lastWg >= 12L * 3600L * 1000L) {
        prefs.edit().putLong("remoteWgTry", System.currentTimeMillis()).apply()
        importDefaultWgList(true) { vpnRender?.invoke() }
    }
    // one-time: free V2Gaming list used to be merged with the account's servers; reload it clean
    if (!prefs.getBoolean("subSplit1", false)) {
        prefs.edit().putBoolean("subSplit1", true).apply()
        refreshRemoteV2(true, null)
    }
    checkSubSilently()
    checkUpdate(false, null)
}

// ----- "New" badge: an item first seen in an online list is marked new for 24 hours.
// The very first sync of a list is the baseline (nothing is marked new).

internal fun MainActivity.newMap(kind: String): HashMap<String, Long> {
    val m = HashMap<String, Long>()
    val raw = prefs.getString("newSeen_" + kind, null) ?: return m
    for (ln in raw.split("\n")) {
        val p = ln.split("\t")
        if (p.size == 2) m[p[0]] = p[1].toLongOrNull() ?: 0L
    }
    return m
}

internal fun MainActivity.markNewKeys(kind: String, keys: List<String>) {
    val baselined = prefs.contains("newSeen_" + kind)
    val old = newMap(kind)
    val now = System.currentTimeMillis()
    val sb = StringBuilder()
    for (k0 in keys) {
        val k = k0.replace("\t", " ").replace("\n", " ").trim()
        if (k.isEmpty()) continue
        val ts = old[k] ?: if (baselined) now else 0L
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append(k).append("\t").append(ts)
    }
    prefs.edit().putString("newSeen_" + kind, sb.toString()).apply()
}

internal fun MainActivity.isNewKey(kind: String, key: String): Boolean {
    val ts = newMap(kind)[key.replace("\t", " ").replace("\n", " ").trim()] ?: return false
    return ts > 0L && System.currentTimeMillis() - ts < 24L * 3600L * 1000L
}

// ----- 3) WireGuard only for games ------------------------------------------

// Tunnels (WireGuard and V2Ray) always apply to games only. There is no user choice.
internal fun MainActivity.wgScopeGames(@Suppress("UNUSED_PARAMETER") base: String): Boolean = true

internal fun MainActivity.wgIncludePackages(base: String): List<String> = scopedPackages(base)

internal fun MainActivity.scopedPackages(base: String?): List<String> {
    val set = LinkedHashSet<String>()
    for (a in loadApps()) if (a.isGame) set.add(a.pkg)
    set.addAll(favoriteSet())
    for (k in prefs.all.keys) {
        if (k.startsWith("prof_")) {
            val pkg = k.removePrefix("prof_")
            val p = getProfile(pkg)
            if (p != null && base != null && p.wg == base) set.add(pkg)
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

internal fun MainActivity.wgScopedConfig(base: String, text: String): String? {
    if (!wgScopeGames(base)) return text
    val pkgs = wgIncludePackages(base)
    // Games-only mode must never fall back to a whole-phone tunnel.
    if (pkgs.isEmpty()) return null
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
    return if (inserted) wgEnsureKeepalive(out.joinToString("\n")) else null
}

// Mobile NATs drop idle UDP mappings; a peer without PersistentKeepalive then stops receiving.
internal fun wgEnsureKeepalive(text: String): String {
    val src = text.split("\n")
    val needs = ArrayList<Boolean>()
    var cur = -1
    for (ln in src) {
        val t = ln.trim().lowercase()
        if (t == "[peer]") {
            cur++
            needs.add(true)
        } else if (cur >= 0 && t.startsWith("persistentkeepalive")) {
            needs[cur] = false
        }
    }
    val out = ArrayList<String>()
    var idx = -1
    for (ln in src) {
        out.add(ln)
        if (ln.trim().equals("[Peer]", ignoreCase = true)) {
            idx++
            if (needs[idx]) out.add("PersistentKeepalive = 25")
        }
    }
    return out.joinToString("\n")
}


internal fun MainActivity.ghRepo(): String =
    prefs.getString("ghRepo", "ezzdigi/EzzBoostGame") ?: "ezzdigi/EzzBoostGame"

internal fun parseRepo(raw: String): String? {
    val s = raw.trim().trimEnd('/')
    val m = Regex("^(?:https?://)?(?:www\\.)?(?:github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$").find(s) ?: return null
    return m.groupValues[1] + "/" + m.groupValues[2]
}


// With an active subscription the server's DNS list (free + premium) is tried first.
// V2Gaming / WireGuard account servers are not merged here: they are downloaded into the
// "اشتراک ویژه" tab only (see Subscription.kt). An expired code gets an HTTP error and the
// free GitHub list is used.
internal fun MainActivity.listUrls(file: String): List<String> {
    val token = prefs.getString("subCode", "") ?: ""
    val base = remoteUrls(ghRepo(), file)
    if (file != "dns.json") return base
    if (token.isEmpty() || !prefs.getBoolean("subActive", false)) return base
    return listOf(SUB_API + "?a=list&f=" + file + "&t=" + android.net.Uri.encode(token)) + base
}
