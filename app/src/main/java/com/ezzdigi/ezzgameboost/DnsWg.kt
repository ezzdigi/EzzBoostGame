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

// EzzViP WireGuard configs shown in the DNS tab. Two sources share one list:
//  * "remote": published by the app owner in dns-wireguard.json (repo root) and synced
//    automatically, so every user sees them without a new APK;
//  * "local": pasted / imported by the user on this phone.
// Both live in the normal "wg" folder, so connect / test / the game-only tunnel work unchanged.

internal fun MainActivity.dnsWgNameSet(): Set<String> =
    prefs.getStringSet("dnsWgNames", null) ?: emptySet<String>()

internal fun MainActivity.dnsWgRemoteSet(): Set<String> =
    prefs.getStringSet("dnsWgRemote", null) ?: emptySet<String>()

internal fun MainActivity.dnsWgDisplayName(base: String): String = try {
    org.json.JSONObject(prefs.getString("dnsWgMeta", "{}") ?: "{}").optString(base, "")
} catch (_: Throwable) {
    ""
}

internal fun MainActivity.dnsWgList(): List<String> {
    val set = dnsWgNameSet()
    val out = ArrayList<String>()
    for (f in listCfg("wg")) {
        val base = f.removeSuffix(".conf")
        if (set.contains(base)) out.add(base)
    }
    return out
}

internal fun MainActivity.importDnsWg(raw: String, wantedName: String, onDone: () -> Unit) {
    val text = if (EzzLock.isSealed(raw)) (EzzLock.open(raw) ?: "") else raw
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

// Downloads dns-wireguard.json and mirrors it on the phone: new configs are added, changed ones
// replaced, and ones the owner removed from the file disappear. onDone gets a one-line result.
internal fun MainActivity.syncDnsWgRemote(manual: Boolean, onDone: (String) -> Unit) {
    runBg {
        var body: String? = null
        for (u in listUrls("dns-wireguard.json")) {
            val b = httpGet(u, 6000, 1500000)
            if (b != null && b.trimStart().startsWith("{")) {
                body = b
                break
            }
        }
        // base name -> (config text, display name)
        val keep = LinkedHashMap<String, Pair<String, String>>()
        var err = ""
        if (body == null) {
            err = Tr.t("لیست کانفیگ‌ها دریافت نشد.") + " (" + lastHttpErr + ")"
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
                    try {
                        wgParseCheck(text)
                    } catch (_: Throwable) {
                        continue
                    }
                    val disp = o.optString("displayName").ifBlank { nm }
                    keep[cleanName(nm, "EzzViP")] = Pair(text.replace("\r", "").trim() + "\n", disp)
                }
            } catch (_: Throwable) {
                err = Tr.t("لیست کانفیگ‌ها معتبر نیست.")
            }
        }
        ui {
            if (err.isNotEmpty()) {
                onDone(err)
            } else {
                val oldRemote = dnsWgRemoteSet()
                val localNames = HashSet<String>(dnsWgNameSet())
                localNames.removeAll(oldRemote)
                val acct = subWgNameSet()
                for (old in oldRemote) {
                    if (!keep.containsKey(old)) {
                        if (WgState.up && WgState.activeName == old) disconnectWg()
                        deleteCfg("wg", "$old.conf")
                        wgTestRes.remove(old)
                    }
                }
                val newRemote = HashSet<String>()
                val meta = org.json.JSONObject()
                for ((base, pair) in keep) {
                    if (acct.contains(base) || localNames.contains(base)) continue
                    val exists = java.io.File(cfgDir("wg"), "$base.conf").exists()
                    // never overwrite a config that belongs to something else
                    if (exists && !oldRemote.contains(base)) continue
                    if (!exists && listCfg("wg").size >= 20) continue
                    writeCfg("wg", "$base.conf", pair.first)
                    newRemote.add(base)
                    meta.put(base, pair.second)
                }
                val all = HashSet<String>(localNames)
                all.addAll(newRemote)
                prefs.edit()
                    .putStringSet("dnsWgNames", all)
                    .putStringSet("dnsWgRemote", newRemote)
                    .putString("dnsWgMeta", meta.toString())
                    .putLong("dnsWgSyncTs", System.currentTimeMillis())
                    .apply()
                onDone(if (newRemote.isEmpty()) Tr.t("فعلاً کانفیگی منتشر نشده.") else newRemote.size.toString() + " " + Tr.t("کانفیگ به‌روز شد"))
            }
        }
    }
}

internal fun MainActivity.buildDnsWgCard() {
    section("🎮 کانفیگ گیمینگ رایگان")
    val cardV = card()
    val status = label("", 15f, white, true, false)
    cardV.addView(status)
    cardV.addView(label("کانفیگ‌های گیمینگ رایگان از سرور ما خودکار می‌آیند؛ تست کن و وصل شو.", 12f, muted), vlp(2, 6))
    val refreshBtn = button("🌐 به‌روزرسانی کانفیگ‌ها", false) {}
    cardV.addView(refreshBtn, vlp(2, 8))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    cardV.addView(list)

    // Personal configs: hidden behind a toggle so the owner's list stays on top.
    val form = LinearLayout(this)
    form.orientation = LinearLayout.VERTICAL
    form.visibility = View.GONE
    val nameIn = input("نام کانفیگ (اختیاری)")
    nameIn.textDirection = View.TEXT_DIRECTION_LTR
    form.addView(nameIn, vlp(4, 8))
    val cfgIn = input("متن کانفیگ را اینجا پیست کن ([Interface] ... [Peer] ...)")
    cfgIn.isSingleLine = false
    cfgIn.minLines = 5
    cfgIn.maxLines = 10
    cfgIn.gravity = Gravity.TOP or Gravity.START
    cfgIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    cfgIn.textDirection = View.TEXT_DIRECTION_LTR
    form.addView(cfgIn, vlp(0, 8))
    form.addView(button("💾 ذخیره کانفیگ", true) {
        val txt = cfgIn.text.toString().trim()
        if (txt.isEmpty() || !(txt.contains("[Interface]", ignoreCase = true) || EzzLock.isSealed(txt))) {
            toast(Tr.t("کانفیگ وایرگارد پیدا نشد؛ متن باید با [Interface] شروع شود."))
        } else {
            importDnsWg(txt, nameIn.text.toString()) {
                cfgIn.setText("")
                nameIn.setText("")
                vpnRender?.invoke()
            }
        }
    }, vlp(0, 8))
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val bClip = button("📋 از کلیپ‌بورد", false) {
        val txt = clipText().trim()
        if (txt.isEmpty() || !(txt.contains("[Interface]", ignoreCase = true) || EzzLock.isSealed(txt))) {
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
    form.addView(row, vlp(0, 4))
    val toggle = button("➕ افزودن کانفیگ شخصی", false) {}
    toggle.setOnClickListener {
        val show = form.visibility != View.VISIBLE
        form.visibility = if (show) View.VISIBLE else View.GONE
        toggle.text = Tr.t(if (show) "▲ بستن" else "➕ افزودن کانفیگ شخصی")
    }
    cardV.addView(toggle, vlp(8, 8))
    cardV.addView(form)
    content.addView(cardV, vlp(0, 4))

    var key = ""
    fun render(force: Boolean) {
        val up = WgState.up
        val act = WgState.activeName
        status.text = if (wgBusy) "⏳ ..." else if (up) Tr.t("🟢 متصل: ") + act else Tr.t("⚪ قطع")
        status.setTextColor(if (up) good else white)
        val names = dnsWgList()
        val remote = dnsWgRemoteSet()
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
            val isRemote = remote.contains(base)
            val shown = dnsWgDisplayName(base).ifBlank { base }
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(12), dp(10), dp(12), dp(10))
            r.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(label((if (isActive) "$shown  ✓" else shown) + (if (wgIsAwgBase(base)) "  🛡 AWG" else ""), 13.5f, white, true, false))
            val tr = wgTestRes[base] ?: ""
            if (tr.isNotEmpty()) col.addView(label(tr, 11.5f, white, false, false), vlp(2, 2))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
            tlp.marginEnd = dp(6)
            r.addView(smallBtn("🧪 تست", false) { testWg(base) }, tlp)
            r.addView(smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
                if (isActive) disconnectWg() else connectWg(base)
            })
            if (!isRemote) {
                val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
                dlp.marginStart = dp(6)
                r.addView(smallBtn("🗑", false) {
                    deleteDnsWg(base)
                    render(true)
                }, dlp)
            }
            list.addView(r, vlp(0, 6))
        }
    }
    vpnRefresh = { render(false) }
    vpnRender = { render(true) }
    render(true)

    refreshBtn.setOnClickListener {
        refreshBtn.text = Tr.t("در حال دریافت…")
        syncDnsWgRemote(true) { msg ->
            refreshBtn.text = Tr.t("🌐 به‌روزرسانی کانفیگ‌ها")
            toast(msg)
            render(true)
        }
    }
    // Pull the owner's list in the background at most once an hour.
    if (System.currentTimeMillis() - prefs.getLong("dnsWgSyncTs", 0L) > 3600000L) {
        prefs.edit().putLong("dnsWgSyncTs", System.currentTimeMillis()).apply()
        syncDnsWgRemote(false) { render(true) }
    }
}
