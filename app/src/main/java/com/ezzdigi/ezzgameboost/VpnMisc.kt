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

// ----- BRAND / ONBOARDING / MISC ----------------------------------------

internal fun MainActivity.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        toast("مرورگری برای باز کردن لینک پیدا نشد.")
    }
}

internal fun MainActivity.shareApp() {
    val i = Intent(Intent.ACTION_SEND)
    i.type = "text/plain"
    i.putExtra(
        Intent.EXTRA_TEXT,
        Tr.t("EzzGameBOOST؛ ابزار گیمینگ اندروید: بوست RAM، DNS گیمینگ و تست پینگ.")
    )
    try {
        startActivity(Intent.createChooser(i, null))
    } catch (_: Exception) {
    }
}

internal fun MainActivity.setLang(l: String) {
    prefs.edit().putString("lang", l).apply()
    Tr.load(this)
    buildShell()
    showTab(tab)
    refreshWidget()
}

internal fun MainActivity.refreshWidget() {
    try {
        val m = AppWidgetManager.getInstance(this)
        val c = ComponentName(this, BoostWidget::class.java)
        if (m.getAppWidgetIds(c).isNotEmpty()) m.updateAppWidget(c, BoostWidget.build(this))
    } catch (_: Throwable) {
    }
}

@Suppress("DEPRECATION")
internal fun MainActivity.showOnboarding() {
    val rv = LinearLayout(this)
    rv.orientation = LinearLayout.VERTICAL
    rv.setBackgroundColor(bg0)
    rv.layoutDirection = if (Tr.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
    rv.setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
        insets
    }
    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    col.setPadding(dp(24), dp(28), dp(24), dp(24))

    col.addView(LogoView(this, accent, panel), LinearLayout.LayoutParams(dp(104), dp(104)))
    val nameView = TextView(this)
    val sb = SpannableStringBuilder("EzzGameBOOST")
    sb.setSpan(ForegroundColorSpan(accent), 7, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    nameView.text = sb
    nameView.textSize = 26f
    nameView.typeface = Typeface.DEFAULT_BOLD
    nameView.setTextColor(white)
    nameView.gravity = Gravity.CENTER
    col.addView(nameView, vlp(14, 2))
    col.addView(label("", 8f, muted), vlp(0, 6))

    val langRow = LinearLayout(this)
    langRow.orientation = LinearLayout.HORIZONTAL
    val l1 = LinearLayout.LayoutParams(WRAP, WRAP)
    l1.marginEnd = dp(8)
    langRow.addView(chip("فارسی", !Tr.en, false) {
        prefs.edit().putString("lang", "fa").apply()
        Tr.load(this)
        showOnboarding()
    }, l1)
    langRow.addView(chip("English", Tr.en, false) {
        prefs.edit().putString("lang", "en").apply()
        Tr.load(this)
        showOnboarding()
    })
    val lrp = LinearLayout.LayoutParams(WRAP, WRAP)
    lrp.topMargin = dp(14)
    col.addView(langRow, lrp)

    val fc = card()
    val feats = listOf(
        "⚡ بوست RAM و پایش زنده‌ی گوشی",
        "🌐 تغییر واقعی DNS با رادار گیم، الکترو، شکن، بگذر و شلتر",
        "📡 تست پینگ، ذخیره‌ی سرورهای بازی و تاریخچه‌ی کیفیت شبکه",
        "🎮 کتابخانه‌ی بازی‌ها با زمان بازی و اتصال خودکار DNS",
        "🎛 پروفایل برای هر بازی: DNS یا وایرگارد مخصوص، پاک‌سازی RAM و اتصال خودکار"
    )
    for (f in feats) fc.addView(label(f, 13.5f, white), vlp(0, 8))
    col.addView(fc, vlp(18, 8))
    val priv = label(
        "حریم خصوصی: برای تغییر DNS یک VPN محلی ساخته می‌شود. فقط درخواست‌های DNS به سرور انتخابی تو می‌رود و هیچ داده‌ای برای ما ارسال نمی‌شود.",
        11.5f, muted
    )
    priv.gravity = Gravity.CENTER
    col.addView(priv, vlp(0, 14))
    col.addView(button("شروع") {
        prefs.edit().putBoolean("onboarded", true).apply()
        startMain()
    }, LinearLayout.LayoutParams(MATCH, WRAP))
    sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
    rv.addView(sv, LinearLayout.LayoutParams(MATCH, MATCH))
    setContentView(rv)
}

// ----- VPN: WireGuard configs + custom DNS -----------------------------

internal fun MainActivity.cfgDir(kind: String): java.io.File {
    val d = java.io.File(filesDir, kind)
    if (!d.exists()) d.mkdirs()
    return d
}

internal fun MainActivity.listCfg(kind: String): List<String> {
    val files = cfgDir(kind).listFiles() ?: return emptyList()
    val names = ArrayList<String>()
    for (f in files) if (f.isFile) names.add(f.name)
    names.sort()
    return names
}

internal fun MainActivity.readCfg(kind: String, file: String): String =
    try {
        java.io.File(cfgDir(kind), file).readText()
    } catch (_: Exception) {
        ""
    }

internal fun MainActivity.writeCfg(kind: String, file: String, text: String) {
    java.io.File(cfgDir(kind), file).writeText(text)
}

internal fun MainActivity.deleteCfg(kind: String, file: String) {
    try {
        java.io.File(cfgDir(kind), file).delete()
    } catch (_: Exception) {
    }
}

internal fun MainActivity.cleanName(raw: String, fallback: String): String {
    var n = raw.trim().substringBeforeLast(".conf").substringBeforeLast(".txt")
    n = n.replace(Regex("[^A-Za-z0-9_=+-]"), "_").trim('_')
    if (n.isEmpty()) n = fallback
    return n.take(15)
}

internal fun MainActivity.uniqueFile(kind: String, base: String, ext: String): String {
    var n = base
    var i = 1
    while (java.io.File(cfgDir(kind), n + ext).exists()) {
        val suffix = i.toString()
        n = base.take(15 - suffix.length - 1) + "_" + suffix
        i++
    }
    return n + ext
}

internal fun MainActivity.wgEndpoint(text: String): String {
    for (line in text.lines()) {
        val l = line.trim()
        if (l.startsWith("Endpoint", ignoreCase = true)) return l.substringAfter("=").trim()
    }
    return ""
}

internal fun MainActivity.clipText(): String {
    return try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this).toString() else ""
    } catch (_: Exception) {
        ""
    }
}

internal fun MainActivity.pickFile(code: Int) {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
    i.addCategory(Intent.CATEGORY_OPENABLE)
    i.type = "*/*"
    try {
        startActivityForResult(i, code)
    } catch (_: Exception) {
        toast("فایل‌منیجر پیدا نشد.")
    }
}

internal fun MainActivity.displayName(uri: Uri): String {
    var name = ""
    try {
        val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (c != null) {
            if (c.moveToFirst()) name = c.getString(0) ?: ""
            c.close()
        }
    } catch (_: Exception) {
    }
    return name
}

internal fun MainActivity.readUriText(uri: Uri): String? = try {
    contentResolver.openInputStream(uri)?.use { st ->
        val bytes = st.readBytes()
        if (bytes.size > 262144) null else String(bytes, Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}

internal fun MainActivity.importFromUri(uri: Uri) {
    val nm = displayName(uri)
    runBg {
        val text = readUriText(uri)
        ui {
            if (text == null || text.isBlank()) {
                toast(Tr.t("فایل خوانده نشد یا خیلی بزرگ است."))
            } else if (listCfg("wg").size >= 20) {
                toast(Tr.t("حداکثر ۲۰ کانفیگ وایرگارد قابل ذخیره است."))
            } else {
                importWg(text, if (importName.isNotBlank()) importName else nm) { vpnRender?.invoke() }
            }
        }
    }
}

internal fun MainActivity.importWg(text: String, wantedName: String, onDone: () -> Unit) {
    runBg {
        var err = ""
        try {
            Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Throwable) {
            err = (e.message ?: e.javaClass.simpleName).take(120)
        }
        ui {
            if (err.isNotEmpty()) {
                toast(Tr.t("کانفیگ وایرگارد نامعتبر است:") + " " + err)
            } else {
                val file = uniqueFile("wg", cleanName(wantedName, "wg"), ".conf")
                writeCfg("wg", file, text)
                toast(Tr.t("کانفیگ ذخیره شد"))
                onDone()
            }
        }
    }
}

// ----- WireGuard config test ----------------------------------------------

class WgTestTun : Tunnel {
    override fun getName(): String = "ezztest"
    override fun onStateChange(newState: Tunnel.State) {
    }
}

internal fun MainActivity.wgScopeToPackages(text: String, pkgs: List<String>): String? {
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

internal fun MainActivity.testWg(base: String) {
    if (wgBusy) return
    if (WgState.up || V2VpnService.running || V2VpnService.busy) {
        wgTestRes[base] = "⚠️ اول اتصال فعلی (وایرگارد یا V2Ray) را قطع کن، بعد تست کن."
        vpnRender?.invoke()
        return
    }
    val prep = try {
        VpnService.prepare(this)
    } catch (_: Exception) {
        null
    }
    if (prep != null) {
        pendingWgTest = base
        try {
            startActivityForResult(prep, 4760)
        } catch (_: Exception) {
            toast("این دستگاه از VPN محلی پشتیبانی نمی‌کند.")
        }
    } else {
        runWgTest(base)
    }
}

// Brings the tunnel up for this app only (never the whole phone), sends a small
// request through it and reports whether the server answered.
// Blocking: brings the config up for this app only, sends one small request through it and
// brings it down again. Returns a one-line result. Call from a background thread.
internal fun MainActivity.wgSelfCheck(text: String, fast: Boolean = false): String {
    var result = ""
    val tun = WgTestTun()
    var up = false
    try {
        val scoped = wgScopeToPackages(text, listOf(packageName))
        if (scoped == null) {
            result = "✕ کانفیگ ساختار معتبر ندارد."
        } else {
            val cfg = Config.parse(ByteArrayInputStream(scoped.toByteArray(Charsets.UTF_8)))
            val be = wgBackendGet()
            be.setState(tun, Tunnel.State.UP, cfg)
            up = true
            Thread.sleep(600)
            var ms = -1L
            val urls = listOf("https://1.1.1.1/cdn-cgi/trace", "https://api.ipify.org")
            for (u in urls) {
                val t0 = System.currentTimeMillis()
                val b = httpGet(u, if (fast) 4000 else 6000, 4000)
                if (b != null) {
                    ms = System.currentTimeMillis() - t0
                    break
                }
            }
            result = if (ms >= 0) {
                "✅ تونل جواب می‌دهد • $ms ms"
            } else {
                "✕ سرور وایرگارد جواب نمی‌دهد: کاربر روی سرور ساخته نشده، سرور خاموش یا بدون اینترنت است، یا پورت UDP بسته/فیلتر است. در این حالت بازی خطای شبکه می‌دهد."
            }
        }
    } catch (e: Throwable) {
        result = "✕ " + hideAddr((e.message ?: e.javaClass.simpleName).take(120))
    }
    if (up) {
        try {
            wgBackendGet().setState(tun, Tunnel.State.DOWN, null)
        } catch (_: Throwable) {
        }
        // give the VPN service time to shut down so it cannot tear down the next tunnel
        try {
            Thread.sleep(2000)
        } catch (_: Throwable) {
        }
    }
    return result
}

internal fun MainActivity.runWgTest(base: String) {
    if (wgBusy) return
    val text = readCfg("wg", base + ".conf")
    if (text.isEmpty()) {
        toast("کانفیگ پیدا نشد.")
        return
    }
    wgBusy = true
    wgTestRes[base] = "⏳ در حال تست…"
    vpnRefresh?.invoke()
    runBg {
        val result = wgSelfCheck(text)
        WgState.up = false
        WgState.activeName = ""
        ui {
            wgBusy = false
            wgTestRes[base] = result
            vpnRender?.invoke()
            homeRefresh?.invoke()
        }
    }
}

// AmneziaWG configs carry obfuscation keys (Jc, Jmin, S1, H1, I1 ...) that the standard
// WireGuard library cannot parse.
internal fun isAwgConfig(text: String): Boolean {
    val keys = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
    for (ln in text.lines()) {
        val k = ln.substringBefore('=').trim().lowercase()
        if (ln.contains('=') && keys.contains(k)) return true
    }
    return false
}

// ----- Default online gaming-server list (wireguard.json in the repo) -----

internal fun MainActivity.importDefaultWgList(silent: Boolean = false, onDone: () -> Unit) {
    if (!silent) toast("در حال دریافت سرورهای گیمینگ…")
    runBg {
        var body: String? = null
        for (u in listUrls("wireguard.json")) {
            val b = httpGet(u, 6000, 1500000)
            if (b != null && b.trimStart().startsWith("{")) {
                body = b
                break
            }
            if (b != null) lastHttpErr = "فایل JSON نیست"
        }
        var added = 0
        var awg = 0
        var err = ""
        val listed = ArrayList<String>()
        if (body == null) {
            err = "لیست سرورها دریافت نشد. (" + lastHttpErr + ")"
        } else {
            try {
                val arr = org.json.JSONObject(body).getJSONArray("configs")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val nm = o.optString("name")
                    var text = o.optString("conf").replace("\\n", "\n")
                    val url = o.optString("url")
                    if (text.isEmpty() && url.isNotEmpty()) text = httpGet(githubRawUrl(url), 10000, 262144) ?: ""
                    if (!text.contains("[Interface]")) continue
                    if (isAwgConfig(text)) {
                        awg++
                        continue
                    }
                    try {
                        Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                    } catch (_: Throwable) {
                        continue
                    }
                    val base = cleanName(nm, "wg")
                    // never overwrite an account server with a free one of the same name
                    if (subWgNameSet().contains(base)) continue
                    listed.add(base)
                    val exists = java.io.File(cfgDir("wg"), base + ".conf").exists()
                    if (!exists && listCfg("wg").size >= 20) continue
                    writeCfg("wg", base + ".conf", text)
                    added++
                }
            } catch (_: Throwable) {
                err = "لیست سرورها معتبر نیست."
            }
            if (added == 0 && err.isEmpty()) {
                err = if (awg > 0) {
                    awg.toString() + " کانفیگ از نوع AmneziaWG است (دارای Jc/S1/H1) و این نسخه فقط WireGuard استاندارد را پشتیبانی می‌کند."
                } else {
                    "سرور گیمینگ معتبری پیدا نشد."
                }
            }
        }
        ui {
            if (listed.isNotEmpty()) markNewKeys("wg", listed)
            if (silent) {
                if (added > 0) onDone()
            } else if (added > 0) {
                toast(added.toString() + " " + Tr.t("کانفیگ اضافه شد"))
                onDone()
            } else {
                toast(err)
            }
        }
    }
}

// ----- Import WireGuard configs from GitHub -------------------------------

internal fun githubRawUrl(u: String): String {
    var s = u.trim()
    if (!s.startsWith("http")) s = "https://" + s
    val isGh = s.contains("://github.com/") || s.contains("://gist.github.com/")
    if (isGh) s = s.substringBefore("#").substringBefore("?")
    val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$").find(s)
    if (m != null) {
        return "https://raw.githubusercontent.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/" + m.groupValues[3]
    }
    val g = Regex("^https?://gist\\.github\\.com/([^/]+)/([0-9a-fA-F]+)").find(s)
    if (g != null) {
        return "https://gist.githubusercontent.com/" + g.groupValues[1] + "/" + g.groupValues[2] + "/raw"
    }
    return s
}

internal fun MainActivity.importFromGithub(url: String, wantedName: String, onDone: () -> Unit) {
    val u = url.trim()
    if (u.isEmpty()) {
        toast("لینک GitHub را وارد کن.")
        return
    }
    if (listCfg("wg").size >= 20) {
        toast(Tr.t("حداکثر ۲۰ کانفیگ وایرگارد قابل ذخیره است."))
        return
    }
    toast("در حال دریافت از GitHub…")
    runBg {
        var added = 0
        var err = ""
        try {
            val files = ArrayList<Pair<String, String>>()
            val clean = u.substringBefore("#").substringBefore("?").trimEnd('/')
            val m = Regex("^(?:https?://)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/tree/([^/]+)(?:/(.*))?)?$").find(clean)
            if (m != null) {
                val dir = m.groupValues[4].replace(" ", "%20")
                var api = "https://api.github.com/repos/" + m.groupValues[1] + "/" + m.groupValues[2] + "/contents"
                if (dir.isNotEmpty()) api += "/" + dir
                if (m.groupValues[3].isNotEmpty()) api += "?ref=" + m.groupValues[3]
                val body = httpGet(api, 10000, 1500000)
                if (body == null) {
                    err = "لیست فایل‌ها از GitHub دریافت نشد. (" + lastHttpErr + ")"
                } else {
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val nm = o.optString("name")
                        val dl = o.optString("download_url")
                        if (o.optString("type") == "file" && nm.endsWith(".conf", ignoreCase = true) && dl.isNotEmpty()) {
                            files.add(Pair(nm, dl))
                        }
                    }
                    if (files.isEmpty()) err = "هیچ فایل .conf در این مسیر پیدا نشد."
                }
            } else {
                val raw = githubRawUrl(u)
                files.add(Pair(raw.substringAfterLast('/').substringBefore('?'), raw))
            }
            var failed = 0
            var awgN = 0
            for (f in files) {
                if (listCfg("wg").size >= 20) break
                val text = httpGet(f.second, 10000, 262144)
                if (text == null || !text.contains("[Interface]")) {
                    failed++
                    continue
                }
                if (isAwgConfig(text)) {
                    awgN++
                    continue
                }
                try {
                    Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                } catch (_: Throwable) {
                    failed++
                    continue
                }
                val nm = if (files.size == 1 && wantedName.isNotBlank()) wantedName else f.first
                writeCfg("wg", uniqueFile("wg", cleanName(nm, "wg"), ".conf"), text)
                added++
            }
            if (added == 0 && err.isEmpty()) {
                err = if (awgN > 0) {
                    "کانفیگ از نوع AmneziaWG است و این نسخه فقط WireGuard استاندارد را پشتیبانی می‌کند."
                } else if (failed > 0) {
                    "کانفیگ معتبر وایرگارد پیدا نشد."
                } else {
                    "دریافت از GitHub ناموفق بود."
                }
            }
        } catch (e: Throwable) {
            if (err.isEmpty()) err = "دریافت از GitHub ناموفق بود."
        }
        ui {
            if (added > 0) {
                toast(added.toString() + " " + Tr.t("کانفیگ اضافه شد"))
                onDone()
            } else {
                toast(err)
            }
        }
    }
}

// ----- Custom DNS (IPv4 / IPv6) -------------------------------------------

internal fun MainActivity.isIpv4(s: String): Boolean {
    val p = s.split(".")
    if (p.size != 4) return false
    for (x in p) {
        if (x.isEmpty() || x.length > 3) return false
        val n = x.toIntOrNull() ?: return false
        if (n < 0 || n > 255) return false
    }
    return true
}

internal fun MainActivity.isIpv6(s: String): Boolean {
    if (!s.contains(":")) return false
    if (!Regex("^[0-9a-fA-F:.]+\$").matches(s)) return false
    return try {
        InetAddress.getByName(s) is java.net.Inet6Address
    } catch (_: Exception) {
        false
    }
}

internal fun MainActivity.customDnsLines(): List<List<String>> {
    val raw = prefs.getString("customDns", "") ?: ""
    val out = ArrayList<List<String>>()
    for (line in raw.split("\n")) {
        if (line.isBlank()) continue
        val p = line.split("\t")
        if (p.size >= 5) out.add(p)
    }
    return out
}

internal fun MainActivity.writeCustomDns(lines: List<List<String>>) {
    prefs.edit().putString("customDns", lines.joinToString("\n") { it.joinToString("\t") }).apply()
    dnsCache = null
}

internal fun MainActivity.testDnsAddrs(addrs: List<Pair<String, String>>, onDone: (String) -> Unit) {
    runBg {
        val sb = StringBuilder()
        for (a in addrs) {
            var best = -1
            for (k in 0 until 2) {
                val ms = dnsPingMs(a.second, 2000)
                if (ms >= 0 && (best < 0 || ms < best)) best = ms
            }
            sb.append(a.first).append(":  ").append(a.second).append("  →  ")
            sb.append(if (best >= 0) "$best ms ✅" else "✕").append("\n")
        }
        ui { onDone(sb.toString().trim()) }
    }
}

internal fun MainActivity.wgBackendGet(): GoBackend {
    var b = wgBackend
    if (b == null) {
        b = GoBackend(applicationContext)
        wgBackend = b
    }
    return b
}

internal fun MainActivity.connectWg(base: String) {
    if (wgBusy) return
    pendingWg = base
    val prep = try {
        VpnService.prepare(this)
    } catch (_: Exception) {
        null
    }
    if (prep != null) {
        try {
            startActivityForResult(prep, 4720)
        } catch (_: Exception) {
            toast("این دستگاه از VPN محلی پشتیبانی نمی‌کند.")
        }
    } else {
        doConnectWg(base)
    }
}

internal fun MainActivity.doConnectWg(base: String) {
    val text = readCfg("wg", base + ".conf")
    if (text.isEmpty()) {
        toast("کانفیگ پیدا نشد.")
        return
    }
    wgBusy = true
    toast(Tr.t("در حال اتصال به") + " " + base + "…")
    vpnRefresh?.invoke()
    runBg {
        var err = ""
        var noGames = false
        try {
            val scoped = try {
                wgScopedConfig(base, text)
            } catch (_: Throwable) {
                null
            }
            if (scoped == null) {
                noGames = true
            } else {
                val cfg = Config.parse(ByteArrayInputStream(scoped.toByteArray(Charsets.UTF_8)))
                val be = wgBackendGet()
                val old = wgTunnel
                if (old != null) {
                    try {
                        be.setState(old, Tunnel.State.DOWN, null)
                    } catch (_: Throwable) {
                    }
                }
                val tun = WgTun(base)
                be.setState(tun, Tunnel.State.UP, cfg)
                wgTunnel = tun
                WgState.activeName = base
                WgState.up = true
                try {
                    val pk = wgIncludePackages(base)
                    val labels = loadApps().filter { pk.contains(it.pkg) }.map { it.label }
                    wgAppsInfo[base] = if (labels.isEmpty()) "" else labels.take(4).joinToString("، ") + (if (labels.size > 4) " +" + (labels.size - 4) else "")
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            err = (e.message ?: e.javaClass.simpleName).take(160)
        }
        ui {
            wgBusy = false
            if (noGames) {
                toast(Tr.t("بازی‌ای پیدا نشد. یک بازی نصب کن یا آن را در تب بازی‌ها علاقه‌مند کن."))
            } else if (err.isEmpty()) {
                toast(Tr.t("متصل شد"))
            } else {
                toast(Tr.t("خطای وایرگارد:") + " " + hideAddr(err))
            }
            vpnRefresh?.invoke()
            homeRefresh?.invoke()
            launchPendingSoon(300)
        }
    }
}

internal fun MainActivity.disconnectWg() {
    if (wgBusy) return
    wgBusy = true
    runBg {
        try {
            val t = wgTunnel
            if (t != null) wgBackendGet().setState(t, Tunnel.State.DOWN, null)
        } catch (_: Throwable) {
        }
        WgState.up = false
        WgState.activeName = ""
        ui {
            wgBusy = false
            toast(Tr.t("وایرگارد قطع شد"))
            vpnRefresh?.invoke()
            homeRefresh?.invoke()
        }
    }
}

internal fun MainActivity.smallBtn(text: String, filled: Boolean, action: () -> Unit): TextView {
    val b = button(text, filled, action)
    b.textSize = 12.5f
    b.setPadding(dp(14), dp(8), dp(14), dp(8))
    return b
}

internal fun MainActivity.buildVpn() {
    // ---------------- WireGuard
    section("WireGuard Gaming")
    val wCard = card()
    val wStatus = label("", 15f, white, true, false)
    wCard.addView(wStatus)
    val nameIn = input("نام کانفیگ (اختیاری)")
    wCard.addView(nameIn, vlp(8, 8))
    val wRow = LinearLayout(this)
    wRow.orientation = LinearLayout.HORIZONTAL
    val bFile = button("📁 افزودن فایل .conf", false) {
        importName = nameIn.text.toString()
        pickFile(4730)
    }
    val bClip = button("📋 از کلیپ‌بورد", false) {
        val txt = clipText().trim()
        if (txt.isEmpty() || !txt.contains("[Interface]")) {
            toast(Tr.t("کانفیگ وایرگارد در کلیپ‌بورد پیدا نشد."))
        } else if (listCfg("wg").size >= 20) {
            toast(Tr.t("حداکثر ۲۰ کانفیگ وایرگارد قابل ذخیره است."))
        } else {
            importWg(txt, nameIn.text.toString()) { vpnRender?.invoke() }
        }
    }
    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp1.marginEnd = dp(4)
    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp2.marginStart = dp(4)
    wRow.addView(bFile, lp1)
    wRow.addView(bClip, lp2)
    wCard.addView(wRow, vlp(0, 10))
    val ghIn = input("لینک GitHub (اختیاری: فایل .conf، پوشه یا ریپو)")
    wCard.addView(ghIn, vlp(0, 8))
    val bGh = button("دریافت سرور گیمینگ وایرگارد", true) {
        val gl = ghIn.text.toString().trim()
        if (gl.isEmpty()) importDefaultWgList { vpnRender?.invoke() }
        else importFromGithub(gl, nameIn.text.toString()) {
            ghIn.setText("")
            vpnRender?.invoke()
        }
    }
    wCard.addView(bGh, vlp(0, 10))
    val wList = LinearLayout(this)
    wList.orientation = LinearLayout.VERTICAL
    wCard.addView(wList)
    content.addView(wCard, vlp(0, 4))

    var wKey = ""
    fun renderW(force: Boolean) {
        val up = WgState.up
        val act = WgState.activeName
        wStatus.text = if (wgBusy) "⏳ ..." else if (up) Tr.t("🟢 متصل: ") + act else Tr.t("⚪ قطع")
        wStatus.setTextColor(if (up) good else white)
        val acct = subWgNameSet()
        val names = listCfg("wg").filter { !acct.contains(it.removeSuffix(".conf")) }
        val key = "$up|$act|$wgBusy|" + names.joinToString(",") + "|" + wgTestRes.toString() + "|" + (System.currentTimeMillis() / 3600000L)
        if (!force && key == wKey) return
        wKey = key
        wList.removeAllViews()
        if (names.isEmpty()) {
            wList.addView(label("هنوز کانفیگی اضافه نشده.", 12.5f, muted))
            return
        }
        for (file in names) {
            val base = file.removeSuffix(".conf")
            val isActive = up && act == base
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(label((if (isActive) "$base  ✓" else base) + (if (isNewKey("wg", base)) "  🆕 جدید" else ""), 13.5f, white, true, false))
            val gi = if (isActive) (wgAppsInfo[base] ?: "") else ""
            if (gi.isNotEmpty()) col.addView(label("🎮 " + gi, 11.5f, muted, false, false))
            val tr = wgTestRes[base] ?: ""
            if (tr.isNotEmpty()) col.addView(label(tr, 11.5f, white, false, false), vlp(2, 2))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tbtn = smallBtn("🧪 تست", false) { testWg(base) }
            val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
            tlp.marginEnd = dp(6)
            row.addView(tbtn, tlp)
            row.addView(smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
                if (isActive) disconnectWg() else connectWg(base)
            })
            val del = smallBtn("🗑", false) {
                if (isActive) disconnectWg()
                deleteCfg("wg", file)
                renderW(true)
            }
            val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
            dlp.marginStart = dp(6)
            row.addView(del, dlp)
            wList.addView(row, vlp(0, 6))
        }
    }

    val renderV = buildV2Section()

    // ---------------- Custom DNS (IPv4 / IPv6)
    section("CUSTOM DNS • IPv4 / IPv6")
    val cCard = card()
    val cName = input("نام (اختیاری)")
    val c4a = input("IPv4 اصلی  (مثل 1.1.1.1)")
    val c4b = input("IPv4 دوم  (مثل 1.0.0.1)")
    val c6a = input("IPv6 اصلی  (مثل 2606:4700:4700::1111)")
    val c6b = input("IPv6 دوم  (مثل 2606:4700:4700::1001)")
    for (e in arrayOf(c4a, c4b, c6a, c6b)) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        e.textDirection = View.TEXT_DIRECTION_LTR
    }
    cCard.addView(cName, vlp(8, 0))
    cCard.addView(c4a, vlp(8, 0))
    cCard.addView(c4b, vlp(8, 0))
    cCard.addView(c6a, vlp(8, 0))
    cCard.addView(c6b, vlp(8, 0))

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

    val cRes = label("", 12.5f, white, false, false)
    val cRow = LinearLayout(this)
    cRow.orientation = LinearLayout.HORIZONTAL
    val bTest = button("🧪 تست آدرس‌ها", false) {
        val a = collect()
        if (a != null) {
            cRes.text = Tr.t("در حال تست…")
            testDnsAddrs(a) { cRes.text = it }
        }
    }
    val bSave = button("💾 ذخیره‌ی DNS") {
        val a = collect()
        if (a != null) {
            val lines = ArrayList<List<String>>(customDnsLines())
            if (lines.size >= 20) {
                toast(Tr.t("حداکثر ۲۰ DNS دلخواه قابل ذخیره است."))
            } else {
                var nm = cName.text.toString().trim().replace("\t", " ").replace("\n", " ").take(24)
                if (nm.isEmpty()) nm = "Custom " + (lines.size + 1)
                lines.add(
                    listOf(
                        nm,
                        c4a.text.toString().trim(), c4b.text.toString().trim(),
                        c6a.text.toString().trim(), c6b.text.toString().trim()
                    )
                )
                writeCustomDns(lines)
                toast(Tr.t("DNS ذخیره شد"))
                vpnRender?.invoke()
            }
        }
    }
    val lpT = LinearLayout.LayoutParams(0, WRAP, 1f)
    lpT.marginEnd = dp(4)
    val lpS = LinearLayout.LayoutParams(0, WRAP, 1f)
    lpS.marginStart = dp(4)
    cRow.addView(bTest, lpT)
    cRow.addView(bSave, lpS)
    cCard.addView(cRow, vlp(10, 6))
    cCard.addView(cRes, vlp(0, 6))
    val cList = LinearLayout(this)
    cList.orientation = LinearLayout.VERTICAL
    cCard.addView(cList)
    content.addView(cCard, vlp(0, 4))

    var cKey = ""
    fun renderC(force: Boolean) {
        val lines = customDnsLines()
        val key = lines.joinToString("|") { it.joinToString(",") } + "|" +
            DnsVpnService.running + "|" + DnsVpnService.activeName
        if (!force && key == cKey) return
        cKey = key
        cList.removeAllViews()
        if (lines.isEmpty()) {
            cList.addView(label("هنوز DNS دلخواهی ذخیره نشده.", 12.5f, muted))
            return
        }
        for ((idx, ln) in lines.withIndex()) {
            val nm = ln[0]
            val addrs = ArrayList<String>()
            for (j in 1..4) if (ln[j].isNotBlank()) addrs.add(ln[j])
            val provName = "★ " + nm
            val isActive = DnsVpnService.running && DnsVpnService.activeName == provName
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
            }
            val b2 = smallBtn("🧪", false) {
                val pairs = ArrayList<Pair<String, String>>()
                for (a in addrs) pairs.add(Pair(if (a.contains(":")) "IPv6" else "IPv4", a))
                cRes.text = Tr.t("در حال تست…")
                testDnsAddrs(pairs) { cRes.text = it }
            }
            val b3 = smallBtn("🗑", false) {
                if (isActive) stopDns()
                val l = ArrayList<List<String>>(customDnsLines())
                if (idx < l.size) l.removeAt(idx)
                writeCustomDns(l)
                renderC(true)
            }
            val m1 = LinearLayout.LayoutParams(WRAP, WRAP)
            m1.marginEnd = dp(6)
            val m2 = LinearLayout.LayoutParams(WRAP, WRAP)
            m2.marginEnd = dp(6)
            br.addView(b1, m1)
            br.addView(b2, m2)
            br.addView(b3)
            box.addView(br, vlp(8, 0))
            cList.addView(box, vlp(0, 6))
        }
    }

    vpnRefresh = {
        renderW(false)
        renderC(false)
        renderV(false)
    }
    vpnRender = {
        renderW(true)
        renderC(true)
        renderV(true)
    }
    renderW(true)
    renderC(true)
}
