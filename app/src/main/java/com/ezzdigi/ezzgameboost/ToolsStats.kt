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

// ----- TOOLS ------------------------------------------------------------

internal fun MainActivity.applyWindowToggles() {
    if (prefs.getBoolean("keepOn", false)) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val wlp = window.attributes
    wlp.screenBrightness = if (prefs.getBoolean("maxBright", false)) {
        1f
    } else {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    window.attributes = wlp
}

// ----- STATS ------------------------------------------------------------

internal fun MainActivity.buildStats() {
    section("آموزش و راهنمای استفاده")
    val guide = card()
    guide.addView(label("۱) ورود و انتخاب بازی", 14f, white, true), vlp(0, 6))
    guide.addView(label("از تب «بازی‌ها» بازی موردنظر را پیدا کن و برای اجرای آن روی دکمه اجرا بزن. در صورت نیاز می‌توانی تنظیمات DNS و پروفایل هر بازی را انتخاب کنی.", 12.5f, muted), vlp(0, 4))
    guide.addView(label("۲) اتصال به سرورها", 14f, white, true), vlp(0, 10))
    guide.addView(label("از تب‌های EzzGaming و Pro Gaming سرور موردنظر را انتخاب کن. برای قطع اتصال از دکمه قطع اتصال داخل همان بخش استفاده کن.", 12.5f, muted), vlp(0, 4))
    guide.addView(label("۳) خرید و فعال‌سازی اشتراک", 14f, white, true), vlp(0, 10))
    guide.addView(label("وارد تب «اشتراک ویژه» شو، روی دریافت اکانت یا تمدید اشتراک بزن و پس از خرید با نام کاربری و رمز عبور وارد شو. سپس فهرست سرورها را به‌روزرسانی کن.", 12.5f, muted), vlp(0, 4))
    guide.addView(button("رفتن به اشتراک ویژه", true) { showTab(4) }, vlp(0, 10))
    content.addView(guide, vlp(0, 4))

    section("THEME")
    val themeCard = card()
    themeCard.addView(label("حالت نمایش", 14f, white, true), vlp(0, 8))
    val modeRow = LinearLayout(this)
    modeRow.orientation = LinearLayout.HORIZONTAL
    val m1 = LinearLayout.LayoutParams(WRAP, WRAP)
    m1.marginEnd = dp(8)
    val curMode = prefs.getString("themeMode", null) ?: (if (prefs.contains("light")) (if (prefs.getBoolean("light", false)) "light" else "dark") else "auto")
    val m2 = LinearLayout.LayoutParams(WRAP, WRAP)
    m2.marginEnd = dp(8)
    modeRow.addView(chip("📱 خودکار", curMode == "auto", false) {
        prefs.edit().putString("themeMode", "auto").apply()
        applyPalette(resolveLight())
        buildShell()
        showTab(tab)
    }, m2)
    modeRow.addView(chip("🌙 تیره", curMode == "dark", false) {
        prefs.edit().putString("themeMode", "dark").apply()
        applyPalette(false)
        buildShell()
        showTab(tab)
    }, m1)
    modeRow.addView(chip("☀️ سفید", curMode == "light", false) {
        prefs.edit().putString("themeMode", "light").apply()
        applyPalette(true)
        buildShell()
        showTab(tab)
    })
    themeCard.addView(modeRow, vlp(0, 12))
    themeCard.addView(label("رنگ تم", 14f, white, true), vlp(0, 10))
    val current = prefs.getInt("accent", 0).coerceIn(0, accents.size - 1)
    val dots = LinearLayout(this)
    dots.orientation = LinearLayout.HORIZONTAL
    for (i in accents.indices) {
        val dot = TextView(this)
        dot.gravity = Gravity.CENTER
        dot.textSize = 16f
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(accents[i])
        if (i == current) {
            d.setStroke(dp(3), white)
            dot.text = "✓"
            dot.setTextColor(Color.rgb(8, 9, 14))
        }
        dot.background = d
        dot.setOnClickListener {
            prefs.edit().putInt("accent", i).apply()
            accent = accents[i]
            buildShell()
            showTab(tab)
        }
        val dp2 = LinearLayout.LayoutParams(dp(44), dp(44))
        dp2.marginEnd = dp(12)
        dots.addView(dot, dp2)
    }
    themeCard.addView(dots)
    themeCard.addView(label("رنگ فعلی: ${accentNames[current]}", 11.5f, muted), vlp(8, 0))
    content.addView(themeCard, vlp(0, 4))

    section("LANGUAGE")
    val langCard = card()
    val langRow = LinearLayout(this)
    langRow.orientation = LinearLayout.HORIZONTAL
    val l1 = LinearLayout.LayoutParams(WRAP, WRAP)
    l1.marginEnd = dp(8)
    langRow.addView(chip("فارسی", !Tr.en, false) { setLang("fa") }, l1)
    langRow.addView(chip("English", Tr.en, false) { setLang("en") })
    langCard.addView(langRow)
    content.addView(langCard, vlp(0, 4))

    section("ABOUT")
    val about = card()
    about.addView(label("EzzGameBOOST v" + currentVersion(), 14f, white, true, false))
    about.addView(label(Tr.t("نسخه فعلی:") + " " + currentVersion(), 12f, muted, false, false), vlp(2, 6))
    val updBtn = button("🔄 بررسی آپدیت", false) {}
    about.addView(updBtn, vlp(0, 8))
    updBtn.setOnClickListener {
        updBtn.text = Tr.t("در حال بررسی…")
        checkUpdate(true) { r ->
            updBtn.text = Tr.t("🔄 بررسی آپدیت")
            if (r == "new") {
                toast(Tr.t("نسخه جدید موجود است:") + " " + (prefs.getString("updTag", "") ?: ""))
                showTab(5)
            } else if (r == "latest") {
                toast(Tr.t("آخرین نسخه را داری ✅"))
            } else {
                val why = r.removePrefix("fail|")
                toast(Tr.t("بررسی آپدیت ناموفق بود") + (if (why.isNotEmpty() && why != "fail") " (" + why + ")" else ""))
            }
        }
    }
    val updTag = prefs.getString("updTag", "") ?: ""
    if (updTag.isNotEmpty() && isNewerVersion(updTag, currentVersion())) {
        val updUrl = prefs.getString("updUrl", RELEASES_PAGE) ?: RELEASES_PAGE
        about.addView(button(Tr.t("⬇️ دانلود نسخه") + " " + updTag) { openUrl(updUrl) }, vlp(0, 8))
        val notes = prefs.getString("updNotes", "") ?: ""
        if (notes.isNotEmpty()) about.addView(label(notes, 11.5f, muted, false, false), vlp(0, 8))
    }
    about.addView(button("اشتراک‌گذاری اپ", false) { shareApp() }, vlp(0, 8))
    content.addView(about, vlp(0, 4))
}
