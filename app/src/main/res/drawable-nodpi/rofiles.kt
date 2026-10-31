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

// ----- 2) Per-game profiles ---------------------------------------------------

class GameProfile(val dns: String, val wg: String, val boost: Boolean, val keepOn: Boolean)

internal fun MainActivity.getProfile(pkg: String): GameProfile? {
    val raw = prefs.getString("prof_$pkg", null) ?: return null
    val p = raw.split("\t")
    if (p.size < 4) return null
    return GameProfile(p[0], p[1], p[2] == "1", p[3] == "1")
}

internal fun MainActivity.saveProfile(pkg: String, p: GameProfile) {
    val s = listOf(p.dns, p.wg, if (p.boost) "1" else "0", if (p.keepOn) "1" else "0")
        .joinToString("\t") { it.replace("\t", " ") }
    prefs.edit().putString("prof_$pkg", s).apply()
}

internal fun MainActivity.deleteProfile(pkg: String) {
    prefs.edit().remove("prof_$pkg").apply()
}

internal fun MainActivity.editProfile(item: AppItem, after: () -> Unit) {
    val old = getProfile(item.pkg)
    var dns = old?.dns ?: ""
    var wg = old?.wg ?: ""
    val boost = false // RAM cleaning was removed
    var keepOn = old?.keepOn ?: false
    var again: () -> Unit = {}

    fun onOff(b: Boolean): String = Tr.t(if (b) "روشن" else "خاموش")
    fun themed(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    fun pick(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        val idx = options.indexOf(current).coerceAtLeast(0)
        themed().setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), idx) { d, which ->
                onPick(if (which == 0) "" else options[which])
                d.dismiss()
                again()
            }
            .setNegativeButton(Tr.t("بستن")) { _, _ -> again() }
            .show()
    }

    fun showMain() {
        val labels = arrayOf(
            "🌐 DNS: " + (if (dns.isEmpty()) Tr.t("بدون تغییر") else dns),
            "🔐 " + Tr.t("وایرگارد") + ": " + (if (wg.isEmpty()) Tr.t("بدون وایرگارد") else wg),
            "📱 " + Tr.t("روشن ماندن صفحه") + ": " + onOff(keepOn),
            "💾 " + Tr.t("ذخیره پروفایل"),
            "🗑 " + Tr.t("حذف پروفایل")
        )
        themed().setTitle(Tr.t("پروفایل بازی:") + " " + item.label)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> pick(
                        "DNS",
                        listOf(Tr.t("بدون تغییر")) + dnsProviders.map { it.name },
                        dns
                    ) { dns = it }
                    1 -> pick(
                        Tr.t("وایرگارد"),
                        listOf(Tr.t("بدون وایرگارد")) + listCfg("wg").map { it.removeSuffix(".conf") },
                        wg
                    ) { wg = it }
                    2 -> {
                        keepOn = !keepOn
                        again()
                    }
                    3 -> {
                        if (dns.isEmpty() && wg.isEmpty() && !boost && !keepOn) {
                            deleteProfile(item.pkg)
                        } else {
                            saveProfile(item.pkg, GameProfile(dns, wg, boost, keepOn))
                        }
                        toast(Tr.t("پروفایل ذخیره شد"))
                        after()
                    }
                    else -> {
                        deleteProfile(item.pkg)
                        toast(Tr.t("پروفایل حذف شد"))
                        after()
                    }
                }
            }
            .setNegativeButton(Tr.t("بستن"), null)
            .show()
    }

    again = { showMain() }
    showMain()
}

internal fun MainActivity.launchPendingSoon(delayMs: Long) {
    val pkg = pendingLaunch ?: return
    pendingLaunch = null
    handler.postDelayed({ doLaunch(pkg) }, delayMs)
}

internal fun MainActivity.launchWithProfile(pkg: String, p: GameProfile) {
    if (p.keepOn) {
        prefs.edit().putBoolean("keepOn", true).apply()
        applyWindowToggles()
    }
    val wgBase = p.wg
    if (wgBase.isNotEmpty() && listCfg("wg").contains("$wgBase.conf")) {
        val already = WgState.up && WgState.activeName == wgBase
        if (!already && !wgBusy) {
            pendingLaunch = pkg
            connectWg(wgBase)
            return
        }
    } else if (p.dns.isNotEmpty() && !WgState.up && !V2VpnService.running) {
        val pr = dnsProviders.firstOrNull { it.name == p.dns }
        if (pr != null && !(DnsVpnService.running && DnsVpnService.activeName == pr.name)) {
            pendingLaunch = pkg
            connectDns(pr)
            return
        }
    }
    doLaunch(pkg)
}
