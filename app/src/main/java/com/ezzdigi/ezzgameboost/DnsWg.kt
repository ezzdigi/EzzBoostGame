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

// EzzViP WireGuard configs added by hand from the DNS tab. They are kept in the same "wg" folder
// as every other config (so connect / test / the game-only tunnel all work unchanged) and are
// remembered by name here, so the DNS tab lists them as their own group.

internal fun MainActivity.dnsWgNameSet(): Set<String> =
    prefs.getStringSet("dnsWgNames", null) ?: emptySet<String>()

internal fun MainActivity.dnsWgList(): List<String> {
    val set = dnsWgNameSet()
    val out = ArrayList<String>()
    for (f in listCfg("wg")) {
        val base = f.removeSuffix(".conf")
        if (set.contains(base)) out.add(base)
    }
    return out
}

internal fun MainActivity.importDnsWg(text: String, wantedName: String, onDone: () -> Unit) {
    if (listCfg("wg").size >= 20) {
        toast(Tr.t("حداکثر ۲۰ کانفیگ وایرگارد قابل ذخیره است."))
        return
    }
    val clean = text.replace("\r", "").trim() + "\n"
    runBg {
        var err = ""
        try {
            wgParseCheck(clean)
        } catch (e: Throwable) {
            err = (e.message ?: e.javaClass.simpleName).take(120)
        }
        ui {
            if (err.isNotEmpty()) {
                toast(Tr.t("کانفیگ وایرگارد نامعتبر است:") + " " + err)
            } else {
                val file = uniqueFile("wg", cleanName(wantedName, "EzzViP"), ".conf")
                writeCfg("wg", file, clean)
                val set = HashSet<String>(dnsWgNameSet())
                set.add(file.removeSuffix(".conf"))
                prefs.edit().putStringSet("dnsWgNames", set).apply()
                toast(Tr.t("کانفیگ ذخیره شد"))
                onDone()
            }
        }
    }
}

internal fun MainActivity.importDnsWgFromUri(uri: Uri) {
    val nm = displayName(uri)
    runBg {
        val text = readUriText(uri)
        ui {
            if (text == null || text.isBlank()) {
                toast(Tr.t("فایل خوانده نشد یا خیلی بزرگ است."))
            } else {
                importDnsWg(text, if (importName.isNotBlank()) importName else nm) { vpnRender?.invoke() }
            }
        }
    }
}

internal fun MainActivity.deleteDnsWg(base: String) {
    if (WgState.up && WgState.activeName == base) disconnectWg()
    deleteCfg("wg", "$base.conf")
    val set = HashSet<String>(dnsWgNameSet())
    set.remove(base)
    prefs.edit().putStringSet("dnsWgNames", set).apply()
    wgTestRes.remove(base)
}

internal fun MainActivity.buildDnsWgCard() {
    section("⚡ EzzViP • کانفیگ وایرگارد")
    val card = card()
    val status = label("", 15f, white, true, false)
    card.addView(status)
    card.addView(label("کانفیگ وایرگارد خودت را اینجا بگذار؛ مثل سرورهای EzzViP تست و وصل می‌شود.", 12f, muted), vlp(2, 6))
    val nameIn = input("نام کانفیگ (اختیاری)")
    nameIn.textDirection = View.TEXT_DIRECTION_LTR
    card.addView(nameIn, vlp(4, 8))
    val cfgIn = input("متن کانفیگ را اینجا پیست کن ([Interface] ... [Peer] ...)")
    cfgIn.isSingleLine = false
    cfgIn.minLines = 5
    cfgIn.maxLines = 10
    cfgIn.gravity = Gravity.TOP or Gravity.START
    cfgIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    cfgIn.textDirection = View.TEXT_DIRECTION_LTR
    card.addView(cfgIn, vlp(0, 8))
    val save = button("💾 ذخیره کانفیگ", true) {
        val txt = cfgIn.text.toString().trim()
        if (txt.isEmpty() || !txt.contains("[Interface]", ignoreCase = true)) {
            toast(Tr.t("کانفیگ وایرگارد پیدا نشد؛ متن باید با [Interface] شروع شود."))
        } else {
            importDnsWg(txt, nameIn.text.toString()) {
                cfgIn.setText("")
                nameIn.setText("")
                vpnRender?.invoke()
            }
        }
    }
    card.addView(save, vlp(0, 8))
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val bClip = button("📋 از کلیپ‌بورد", false) {
        val txt = clipText().trim()
        if (txt.isEmpty() || !txt.contains("[Interface]", ignoreCase = true)) {
            toast(Tr.t("کانفیگ وایرگارد در کلیپ‌بورد پیدا نشد."))
        } else {
            importDnsWg(txt, nameIn.text.toString()) { vpnRender?.invoke() }
        }
    }
    val bFile = button("📁 فایل .conf", false) {
        importName = nameIn.text.toString()
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 4731)
        } catch (_: Exception) {
            toast("فایل‌منیجر پیدا نشد.")
        }
    }
    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp1.marginEnd = dp(4)
    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp2.marginStart = dp(4)
    row.addView(bClip, lp1)
    row.addView(bFile, lp2)
    card.addView(row, vlp(0, 10))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    card.addView(list)
    content.addView(card, vlp(0, 4))

    var key = ""
    fun render(force: Boolean) {
        val up = WgState.up
        val act = WgState.activeName
        status.text = if (wgBusy) "⏳ ..." else if (up) Tr.t("🟢 متصل: ") + act else Tr.t("⚪ قطع")
        status.setTextColor(if (up) good else white)
        val names = dnsWgList()
        val k = "$up|$act|$wgBusy|" + names.joinToString(",") + "|" + wgTestRes.toString()
        if (!force && k == key) return
        key = k
        list.removeAllViews()
        if (names.isEmpty()) {
            list.addView(label("هنوز کانفیگی اضافه نشده.", 12.5f, muted))
            return
        }
        for (base in names) {
            val isActive = up && act == base
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(12), dp(10), dp(12), dp(10))
            r.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(label((if (isActive) "$base  ✓" else base) + (if (wgIsAwgBase(base)) "  🛡 AWG" else ""), 13.5f, white, true, false))
            val tr = wgTestRes[base] ?: ""
            if (tr.isNotEmpty()) col.addView(label(tr, 11.5f, white, false, false), vlp(2, 2))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
            tlp.marginEnd = dp(6)
            r.addView(smallBtn("🧪 تست", false) { testWg(base) }, tlp)
            r.addView(smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
                if (isActive) disconnectWg() else connectWg(base)
            })
            val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
            dlp.marginStart = dp(6)
            r.addView(smallBtn("🗑", false) {
                deleteDnsWg(base)
                render(true)
            }, dlp)
            list.addView(r, vlp(0, 6))
        }
    }
    vpnRefresh = { render(false) }
    vpnRender = { render(true) }
    render(true)
}
