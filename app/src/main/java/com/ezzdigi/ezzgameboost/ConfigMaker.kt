package com.ezzdigi.ezzgameboost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- "ساخت کانفیگ" tab (Pro subscription only) ---------------------------------
//
// Shown in the tab bar only while an account is logged in (see MainActivity.visibleTabs),
// like "Pro Gaming". The user picks one of the account's Pro servers (or "smart" = random),
// optionally one of the account's X-PRO DNS entries, names the file and gets a standard
// WireGuard .conf that can be copied, saved as a file or shared.

// Text waiting for the "save as" file picker (request code 4770 in MainActivity.onActivityResult).
@Volatile
internal var madeConfPending: String = ""

internal fun MainActivity.saveMadeConfig(uri: Uri) {
    val text = madeConfPending
    madeConfPending = ""
    if (text.isEmpty()) return
    try {
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        toast("ذخیره شد ✓")
    } catch (_: Exception) {
        toast("ذخیره نشد.")
    }
}

private fun MainActivity.makeTitle(base: String): String {
    val dn = try {
        JSONObject(prefs.getString("subWgMeta", "{}") ?: "{}").optJSONObject(base)?.optString("displayName", "") ?: ""
    } catch (_: Throwable) {
        ""
    }
    return dn.ifBlank { base }
}

internal fun MainActivity.buildSubMake() {
    section("ساخت کانفیگ")

    if (subToken().isEmpty()) {
        val c = card()
        c.addView(label("برای ساخت کانفیگ اول وارد «اشتراک ویژه» شو.", 13f, muted, true), vlp(0, 6))
        c.addView(button("رفتن به اشتراک ویژه", true) { showTab(4) }, vlp(0, 0))
        content.addView(c, vlp(0, 4))
        return
    }

    val act = prefs.getBoolean("subActive", false)
    val left = subDaysLeft()
    if (!act || (left != null && left <= 0)) {
        val c = card()
        c.addView(label("❌ اشتراک منقضی شده", 14f, bad, true), vlp(0, 6))
        c.addView(label("برای ساخت کانفیگ اشتراک را تمدید کن.", 12.5f, muted), vlp(0, 8))
        c.addView(button("رفتن به اشتراک ویژه", true) { showTab(4) }, vlp(0, 0))
        content.addView(c, vlp(0, 4))
        return
    }

    val servers = subWgList()
    val dnsItems = subDnsList()
    var selServer = "auto"
    var selDns = ""
    var lastBase = ""
    var lastText = ""
    var lastName = ""

    // ---- form
    val form = card()
    form.addView(label("نام کانفیگ (حداکثر ۱۵ حرف لاتین)", 12f, muted), vlp(0, 6))
    val nameIn = input("EzzPro")
    nameIn.textDirection = View.TEXT_DIRECTION_LTR
    form.addView(nameIn, vlp(0, 10))

    form.addView(label("سرور پرو", 12f, muted), vlp(0, 6))
    val sScroll = HorizontalScrollView(this)
    sScroll.isHorizontalScrollBarEnabled = false
    val sRow = LinearLayout(this)
    sRow.orientation = LinearLayout.HORIZONTAL
    sScroll.addView(sRow)
    form.addView(sScroll, vlp(0, 10))

    form.addView(label("DNS (اختیاری)", 12f, muted), vlp(0, 6))
    val dScroll = HorizontalScrollView(this)
    dScroll.isHorizontalScrollBarEnabled = false
    val dRow = LinearLayout(this)
    dRow.orientation = LinearLayout.HORIZONTAL
    dScroll.addView(dRow)
    form.addView(dScroll, vlp(0, 12))

    val result = LinearLayout(this)
    result.orientation = LinearLayout.VERTICAL

    fun addChip(row: LinearLayout, text: String, sel: Boolean, tr: Boolean, onClick: () -> Unit) {
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.marginEnd = dp(6)
        row.addView(chip(text, sel, tr, onClick), lp)
    }

    fun paintPickers() {
        sRow.removeAllViews()
        dRow.removeAllViews()
        addChip(sRow, "⚡ هوشمند", selServer == "auto", true) { selServer = "auto"; paintPickers() }
        for (b in servers) {
            addChip(sRow, makeTitle(b), selServer == b, false) { selServer = b; paintPickers() }
        }
        addChip(dRow, "پیش‌فرض کانفیگ", selDns.isEmpty(), true) { selDns = ""; paintPickers() }
        for (d in dnsItems) {
            addChip(dRow, d.name, selDns == d.name, false) { selDns = d.name; paintPickers() }
        }
    }

    fun paintResult() {
        result.removeAllViews()
        if (lastText.isEmpty()) return
        val rc = card()
        rc.addView(label("✓ " + Tr.t("کانفیگ ساخته شد") + ": $lastName.conf", 13.5f, good, true, false), vlp(0, 8))
        // the private key stays hidden on screen; copy / save / share use the full text
        val shown = lastText.lines().joinToString("\n") {
            val k = it.substringBefore("=").trim().lowercase()
            if (k == "privatekey" || k == "presharedkey") it.substringBefore("=").trim() + " = ••••••••" else it
        }
        val pv = label(shown, 11f, muted, false, false)
        pv.typeface = Typeface.MONOSPACE
        pv.textDirection = View.TEXT_DIRECTION_LTR
        pv.setTextIsSelectable(false)
        rc.addView(pv, vlp(0, 10))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val bCopy = smallBtn("📋 کپی", false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(lastName, lastText))
            toast("کپی شد ✓")
        }
        val bSave = smallBtn("💾 ذخیره", true) {
            madeConfPending = lastText
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/octet-stream"
            i.putExtra(Intent.EXTRA_TITLE, "$lastName.conf")
            try {
                startActivityForResult(i, 4770)
            } catch (_: Exception) {
                toast("فایل‌منیجر پیدا نشد.")
            }
        }
        val bShare = smallBtn("📤 ارسال", false) {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_SUBJECT, "$lastName.conf")
            i.putExtra(Intent.EXTRA_TEXT, lastText)
            try {
                startActivity(Intent.createChooser(i, null))
            } catch (_: Exception) {
            }
        }
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(4) }
        val l2 = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
        val l3 = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4) }
        row.addView(bCopy, l1)
        row.addView(bSave, l2)
        row.addView(bShare, l3)
        rc.addView(row)
        result.addView(rc, vlp(10, 0))
    }

    fun build() {
        if (servers.isEmpty()) {
            toast("هنوز کانفیگی نیست.")
            return
        }
        var base = if (selServer == "auto" || !servers.contains(selServer)) servers.random() else selServer
        if (selServer == "auto" && servers.size > 1) {
            var guard = 0
            while (base == lastBase && guard < 8) {
                base = servers.random()
                guard++
            }
        }
        var text = readCfg("wg", "$base.conf")
        if (text.isBlank()) {
            toast("خواندن کانفیگ سرور ممکن نشد.")
            return
        }
        val dnsPick = dnsItems.firstOrNull { it.name == selDns }
        if (dnsPick != null) {
            val ips = listOf(dnsPick.primary, dnsPick.secondary, dnsPick.tertiary, dnsPick.quaternary)
                .filter { it.isNotBlank() }
            text = wgApplyDns(text, ips)
        }
        lastBase = base
        lastText = text.replace("\r", "").trim() + "\n"
        lastName = nameIn.text.toString().replace(Regex("[^a-zA-Z0-9_+=.-]"), "").take(15).ifEmpty { "EzzPro" }
        paintResult()
    }

    form.addView(button("🛠️ ساخت کانفیگ", true) { build() }, vlp(0, 0))
    content.addView(form, vlp(0, 4))
    content.addView(result, vlp(0, 4))

    if (servers.isEmpty()) {
        content.addView(label("هنوز سروری برای حساب تو همگام نشده؛ از «اشتراک ویژه» دکمه به‌روزرسانی را بزن.", 12f, muted), vlp(6, 0))
    }
    paintPickers()
}
