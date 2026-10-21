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

// ----- HOME -------------------------------------------------------------

internal fun MainActivity.buildHome() {
    val hero = LinearLayout(this)
    hero.orientation = LinearLayout.VERTICAL
    hero.setPadding(dp(16), dp(16), dp(16), dp(16))
    hero.background = heroBg()
    hero.addView(label("آماده نبرد؟ 🎮", 19f, white, true))
    val heroSub = label("", 12.5f, muted)
    hero.addView(heroSub, vlp(4, 0))
    content.addView(hero, vlp(4, 4))
    addUpdateBanner()

    section("LIVE STATS")
    val gCard = card(8)
    val gRow = LinearLayout(this)
    gRow.orientation = LinearLayout.HORIZONTAL
    val ramG = GaugeView(this, "RAM", white)
    val batG = GaugeView(this, "BATTERY", white)
    val stoG = GaugeView(this, "STORAGE", white)
    for (g in arrayOf(ramG, batG, stoG)) {
        gRow.addView(g, LinearLayout.LayoutParams(0, dp(132), 1f))
    }
    gCard.addView(gRow)
    content.addView(gCard, vlp(0, 4))

    section("BOOST CENTER")
    val bCard = card()
    bCard.gravity = Gravity.CENTER_HORIZONTAL
    val boost = BoostView(this, accent, panel, white)
    val bp = LinearLayout.LayoutParams(dp(170), dp(170))
    bp.gravity = Gravity.CENTER_HORIZONTAL
    bCard.addView(boost, bp)
    val boostMsg = label(
        "با یک لمس، پردازش‌های پس‌زمینه‌ی برنامه‌ها بسته می‌شوند تا RAM آزاد بیشتری برای بازی بماند.",
        12.5f, muted
    )
    boostMsg.gravity = Gravity.CENTER
    val mp = LinearLayout.LayoutParams(MATCH, WRAP)
    mp.topMargin = dp(10)
    bCard.addView(boostMsg, mp)
    boost.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        runBoost(boost, boostMsg)
    }
    content.addView(bCard, vlp(0, 4))

    section("QUICK LAUNCH")
    val favs = favoriteSet()
    if (favs.isEmpty()) {
        val hint = card()
        hint.addView(label("هنوز بازی مورد علاقه‌ای نداری. در تب «بازی‌ها» روی ☆ بزن تا اینجا میان‌بر بسازی.", 12.5f, muted))
        content.addView(hint, vlp(0, 4))
    } else {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (p in favs) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.marginEnd = dp(8)
            row.addView(appTile(p), lp)
        }
        hs.addView(row)
        content.addView(hs, vlp(0, 4))
    }

    section("SYSTEM")
    val info = card()
    val vDevice = infoRow(info, "دستگاه")
    val vAndroid = infoRow(info, "اندروید")
    val vCpu = infoRow(info, "هسته‌های پردازنده")
    val vRefresh = infoRow(info, "نرخ نوسازی صفحه")
    val vNet = infoRow(info, "شبکه")
    val vDns = infoRow(info, "DNS")
    val vThermal = infoRow(info, "وضعیت دمایی")
    val vTemp = infoRow(info, "دمای باتری")
    vDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}"
    vAndroid.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    vCpu.text = Runtime.getRuntime().availableProcessors().toString()
    content.addView(info, vlp(0, 4))

    val refresh: () -> Unit = {
        val ram = ramInfo()
        val used = ram.second - ram.first
        val rf = used.toFloat() / ram.second.toFloat()
        val rc = if (rf > 0.85f) bad else if (rf > 0.7f) warn else accent
        ramG.setTarget(rf, "${(rf * 100).toInt()}%", "${gb(used)}/${gb(ram.second)}G", rc)

        val bat = battery()
        val bc = if (bat.charging) good else if (bat.pct <= 20) bad else if (bat.pct <= 40) warn else accent
        batG.setTarget(
            bat.pct / 100f, "${bat.pct}%",
            if (bat.charging) Tr.t("⚡ شارژ") else "${bat.temp.toInt()}°C", bc
        )

        val st = storageInfo()
        val su = st.second - st.first
        val sf = su.toFloat() / st.second.toFloat()
        val sc = if (sf > 0.92f) bad else if (sf > 0.8f) warn else accent
        stoG.setTarget(sf, "${(sf * 100).toInt()}%", "${gb(st.first)}G " + Tr.t("آزاد"), sc)

        val net = networkText()
        val thermal = thermalText()
        val hz = refreshRateText()
        vRefresh.text = hz
        vNet.text = Tr.t(net)
        vDns.text = if (DnsVpnService.running) DnsVpnService.activeName else Tr.t("پیش‌فرض سیستم")
        vThermal.text = Tr.t(thermal)
        vTemp.text = String.format(Locale.US, "%.1f °C", bat.temp)
        heroSub.text = Tr.t("$net  •  $hz  •  $thermal")
    }
    refresh()
    homeRefresh = refresh
}

internal fun MainActivity.appTile(pkg: String): View {
    val t = LinearLayout(this)
    t.orientation = LinearLayout.VERTICAL
    t.gravity = Gravity.CENTER_HORIZONTAL
    t.setPadding(dp(6), dp(8), dp(6), dp(8))
    t.background = rounded(panel, 14, stroke)
    val iv = ImageView(this)
    t.addView(iv, LinearLayout.LayoutParams(dp(50), dp(50)))
    runBg {
        try {
            val d = packageManager.getApplicationIcon(pkg)
            ui { iv.setImageDrawable(d) }
        } catch (_: Exception) {
        }
    }
    val name = label(appLabel(pkg), 11f, white, false, false)
    name.maxLines = 1
    name.ellipsize = TextUtils.TruncateAt.END
    name.gravity = Gravity.CENTER
    val np = LinearLayout.LayoutParams(dp(64), WRAP)
    np.topMargin = dp(4)
    t.addView(name, np)
    t.setOnClickListener { launchGame(pkg) }
    return t
}

internal fun MainActivity.runBoost(view: BoostView, msg: TextView) {
    if (view.running) return
    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val before = ramInfo().first
    view.start()
    msg.setTextColor(muted)
    msg.text = Tr.t("در حال پاک‌سازی پردازش‌های پس‌زمینه…")
    runBg {
        var count = 0
        try {
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val pkgs = packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
            for (p in pkgs) {
                try {
                    am.killBackgroundProcesses(p)
                    count++
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(1800)
        } catch (_: InterruptedException) {
        }
        val after = ramInfo().first
        ui {
            view.stop()
            val diff = mb(after) - mb(before)
            msg.setTextColor(white)
            msg.text = if (diff > 0) {
                "✅ ${diff} MB آزاد شد • ${count} برنامه بررسی شد.\nRAM آزاد: ${mb(after)} MB"
            } else {
                "✅ ${count} برنامه بررسی شد؛ تغییر محسوسی در RAM آزاد دیده نشد.\nRAM آزاد: ${mb(after)} MB\n(اندروید معمولاً خودش RAM را خوب مدیریت می‌کند.)"
            }
            msg.text = Tr.t(msg.text.toString())
            homeRefresh?.invoke()
        }
    }
}

// ----- GAMES ------------------------------------------------------------

internal fun MainActivity.buildGames() {
    section("GAME LIBRARY")
    val search = input("جستجوی بازی یا برنامه…")
    search.setText(gamesQuery)
    content.addView(search, vlp(0, 8))

    val listBox = LinearLayout(this)
    listBox.orientation = LinearLayout.VERTICAL

    val chipRow = LinearLayout(this)
    chipRow.orientation = LinearLayout.HORIZONTAL
    val names = arrayOf("بازی‌ها", "علاقه‌مندی‌ها", "همه")
    fun fillChips() {
        chipRow.removeAllViews()
        for (i in names.indices) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.marginEnd = dp(8)
            chipRow.addView(chip(names[i], gamesFilter == i) {
                gamesFilter = i
                fillChips()
                renderGames(listBox)
            }, lp)
        }
    }
    fillChips()
    content.addView(chipRow, vlp(0, 10))
    content.addView(listBox)

    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            gamesQuery = s?.toString() ?: ""
            renderGames(listBox)
        }
    })
    renderGames(listBox)
}

internal fun MainActivity.loadApps(): List<AppItem> {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val result = ArrayList<AppItem>()
    val seen = HashSet<String>()
    for (ri in pm.queryIntentActivities(intent, 0)) {
        val pkg = ri.activityInfo.packageName
        if (pkg == packageName || !seen.add(pkg)) continue
        val ai = ri.activityInfo.applicationInfo
        @Suppress("DEPRECATION")
        val game = ai.category == ApplicationInfo.CATEGORY_GAME ||
            (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        result.add(AppItem(ri.loadLabel(pm).toString(), pkg, game))
    }
    return result
}

internal fun MainActivity.renderGames(box: LinearLayout) {
    val apps = appsCache
    if (apps == null) {
        box.removeAllViews()
        box.addView(label("در حال بارگذاری فهرست برنامه‌ها…", 13f, muted))
        if (!appsLoading) {
            appsLoading = true
            runBg {
                val loaded = loadApps()
                ui {
                    appsCache = loaded
                    appsLoading = false
                    renderGames(box)
                }
            }
        }
        return
    }
    val favs = favoriteSet()
    val q = gamesQuery.trim().lowercase()
    val filtered = apps.filter {
        (q.isEmpty() || it.label.lowercase().contains(q)) &&
            when (gamesFilter) {
                0 -> it.isGame
                1 -> favs.contains(it.pkg)
                else -> true
            }
    }
    val list = filtered.sortedWith(
        compareByDescending<AppItem> { favs.contains(it.pkg) }.thenBy { it.label.lowercase() }
    )
    box.removeAllViews()
    if (list.isEmpty()) {
        val msg = when {
            gamesFilter == 0 && q.isEmpty() ->
                "بازی‌ای شناسایی نشد. روی «همه» بزن و بازی‌هایت را با ☆ به علاقه‌مندی‌ها اضافه کن."
            gamesFilter == 1 && q.isEmpty() -> "فهرست علاقه‌مندی‌ها خالی است."
            else -> "موردی پیدا نشد."
        }
        box.addView(label(msg, 13f, muted))
        return
    }
    val shown = list.take(60)
    for (item in shown) {
        box.addView(appRow(item, favs.contains(item.pkg), box), vlp(0, 8))
    }
    if (list.size > shown.size) {
        box.addView(label("${list.size - shown.size} مورد دیگر… برای پیداکردن، جستجو کن.", 12f, muted))
    }
}

internal fun MainActivity.appRow(item: AppItem, fav: Boolean, box: LinearLayout): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(12), dp(10), dp(12), dp(10))
    row.background = rounded(panel, 14, if (fav) accentAlpha(130) else stroke)

    val icon = ImageView(this)
    row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
    runBg {
        try {
            val d = packageManager.getApplicationIcon(item.pkg)
            ui { icon.setImageDrawable(d) }
        } catch (_: Exception) {
        }
    }

    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(12), 0, dp(8), 0)
    val nm = label(item.label, 14.5f, white, true, false)
    nm.maxLines = 1
    nm.ellipsize = TextUtils.TruncateAt.END
    col.addView(nm)
    val pt = prefs.getLong("pt_" + item.pkg, 0L)
    val lc = prefs.getInt("lc_" + item.pkg, 0)
    col.addView(label(if (lc == 0) "هنوز اجرا نشده" else "${fmtDur(pt)} • ${lc} بار اجرا", 11.5f, muted))
    val pf = getProfile(item.pkg)
    if (pf != null) {
        val parts = ArrayList<String>()
        if (pf.dns.isNotEmpty()) parts.add("DNS: " + pf.dns)
        if (pf.wg.isNotEmpty()) parts.add("WG: " + pf.wg)
        if (pf.boost) parts.add("⚡")
        if (pf.keepOn) parts.add("📱")
        col.addView(label("🎛 " + parts.joinToString(" • "), 11f, accent, false, false))
    }
    row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

    val prof = TextView(this)
    prof.text = "⚙"
    prof.textSize = 22f
    prof.setTextColor(if (pf != null) accent else muted)
    prof.setPadding(dp(6), 0, dp(6), 0)
    prof.setOnClickListener { editProfile(item) { renderGames(box) } }
    row.addView(prof)

    val star = TextView(this)
    star.text = if (fav) "★" else "☆"
    star.textSize = 24f
    star.setTextColor(if (fav) warn else muted)
    star.setPadding(dp(6), 0, dp(10), 0)
    star.setOnClickListener {
        toggleFavorite(item.pkg)
        renderGames(box)
    }
    row.addView(star)

    val play = button("اجرا") { launchGame(item.pkg) }
    play.textSize = 13f
    play.setPadding(dp(16), dp(9), dp(16), dp(9))
    row.addView(play)
    return row
}

internal fun MainActivity.favoriteSet(): Set<String> {
    val s = prefs.getStringSet("fav", null)
    val out = HashSet<String>()
    if (s != null) out.addAll(s)
    return out
}

internal fun MainActivity.toggleFavorite(pkg: String) {
    val s = HashSet<String>(favoriteSet())
    if (!s.add(pkg)) s.remove(pkg)
    prefs.edit().putStringSet("fav", s).apply()
}

internal fun MainActivity.launchGame(pkg: String) {
    if (packageManager.getLaunchIntentForPackage(pkg) == null) {
        toast("اجرای این برنامه ممکن نیست.")
        return
    }
    val prof = getProfile(pkg)
    if (prof != null) {
        launchWithProfile(pkg, prof)
        return
    }
    if (prefs.getBoolean("autoDns", false) && !DnsVpnService.running) {
        val needConsent = try {
            VpnService.prepare(this) != null
        } catch (_: Exception) {
            true
        }
        if (!needConsent) {
            startDnsService(selectedDns())
            handler.postDelayed({ doLaunch(pkg) }, 900)
            return
        }
        toast("برای اتصال خودکار، یک‌بار DNS را دستی وصل کن.")
    }
    doLaunch(pkg)
}

internal fun MainActivity.doLaunch(pkg: String) {
    val i = packageManager.getLaunchIntentForPackage(pkg)
    if (i == null) {
        toast("اجرای این برنامه ممکن نیست.")
        return
    }
    prefs.edit()
        .putString("pendingPkg", pkg)
        .putLong("pendingStart", System.currentTimeMillis())
        .putInt("lc_$pkg", prefs.getInt("lc_$pkg", 0) + 1)
        .apply()
    try {
        startActivity(i)
    } catch (_: Exception) {
        toast("اجرای این برنامه ممکن نیست.")
    }
}

internal fun MainActivity.finalizeSession(): Boolean {
    val pkg = prefs.getString("pendingPkg", null) ?: return false
    val start = prefs.getLong("pendingStart", 0L)
    val dur = System.currentTimeMillis() - start
    val e = prefs.edit().remove("pendingPkg").remove("pendingStart")
    var recorded = false
    if (start > 0L && dur >= 20000L && dur <= 43200000L) {
        e.putLong("pt_$pkg", prefs.getLong("pt_$pkg", 0L) + dur)
        recorded = true
    }
    e.apply()
    return recorded
}
