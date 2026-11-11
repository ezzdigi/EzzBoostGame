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

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- "ساخت کانفیگ" tab (Pro subscription only) ---------------------------------
//
// Shown in the tab bar only while an account is logged in (see MainActivity.visibleTabs),
// like "Pro Gaming". The server is picked automatically from the account's Pro servers (no
// server picker), the user optionally picks one of the account's X-PRO DNS entries, names the
// file and gets a standard WireGuard .conf that can be copied, saved as a file or shared.

// Each account may build at most MK_DAILY_LIMIT configs per (device-local) day. The counter is kept in
// SharedPreferences, so it is per device: clearing app data or changing the clock resets it.
private const val MK_DAILY_LIMIT = 4

private fun MainActivity.mkKey(): String = "mkDaily_" + subToken().hashCode()

private fun MainActivity.mkUsedToday(): Int {
    val parts = (prefs.getString(mkKey(), "") ?: "").split("|")
    if (parts.size != 2 || parts[0] != java.time.LocalDate.now().toString()) return 0
    return parts[1].toIntOrNull() ?: 0
}

private fun MainActivity.mkAddUse() {
    prefs.edit().putString(mkKey(), java.time.LocalDate.now().toString() + "|" + (mkUsedToday() + 1)).apply()
}

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

    form.addView(label("DNS (اختیاری)", 12f, muted), vlp(0, 6))
    val dScroll = HorizontalScrollView(this)
    dScroll.isHorizontalScrollBarEnabled = false
    val dRow = LinearLayout(this)
    dRow.orientation = LinearLayout.HORIZONTAL
    dScroll.addView(dRow)
    form.addView(dScroll, vlp(0, 10))

    val limitLbl = label("", 12f, muted, false, false)
    form.addView(limitLbl, vlp(0, 10))

    val result = LinearLayout(this)
    result.orientation = LinearLayout.VERTICAL

    fun addChip(row: LinearLayout, text: String, sel: Boolean, tr: Boolean, onClick: () -> Unit) {
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.marginEnd = dp(6)
        row.addView(chip(text, sel, tr, onClick), lp)
    }

    fun paintPickers() {
        dRow.removeAllViews()
        addChip(dRow, "پیش‌فرض کانفیگ", selDns.isEmpty(), true) { selDns = ""; paintPickers() }
        for (d in dnsItems) {
            addChip(dRow, d.name, selDns == d.name, false) { selDns = d.name; paintPickers() }
        }
    }

    fun paintLimit() {
        val left = (MK_DAILY_LIMIT - mkUsedToday()).coerceAtLeast(0)
        limitLbl.text = if (Tr.en) "Today: $left of $MK_DAILY_LIMIT config builds left"
        else "امروز $left از $MK_DAILY_LIMIT ساخت باقی مانده است"
        limitLbl.setTextColor(if (left == 0) bad else muted)
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
        if (mkUsedToday() >= MK_DAILY_LIMIT) {
            paintLimit()
            toast(
                if (Tr.en) "Daily limit of $MK_DAILY_LIMIT config builds reached. Try again tomorrow."
                else "سقف $MK_DAILY_LIMIT بار ساخت کانفیگ در روز تمام شده؛ فردا دوباره امتحان کن."
            )
            return
        }
        var base = servers.random()
        if (servers.size > 1) {
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
        mkAddUse()
        paintLimit()
        paintResult()
    }

    form.addView(button("🛠️ ساخت کانفیگ", true) { build() }, vlp(0, 0))
    content.addView(form, vlp(0, 4))
    content.addView(result, vlp(0, 4))

    if (servers.isEmpty()) {
        content.addView(label("هنوز سروری برای حساب تو همگام نشده؛ از «اشتراک ویژه» دکمه به‌روزرسانی را بزن.", 12f, muted), vlp(6, 0))
    }
    paintPickers()
    paintLimit()

    val notes = card()
    notes.addView(label(if (Tr.en) "Notes" else "توضیحات", 13f, accent, true, false), vlp(0, 8))
    val noteLines = if (Tr.en) listOf(
        "Each user can build up to $MK_DAILY_LIMIT configs per day.",
        "Enter a short Latin name (max 15 characters); the file is saved with that name.",
        "DNS is optional; if you pick none, the config's own DNS is used.",
        "After building, copy, save or share the config and import it into WireGuard.",
        "If a config does not connect, build again to get a different server."
    ) else listOf(
        "هر کاربر در هر روز حداکثر $MK_DAILY_LIMIT بار می‌تواند کانفیگ بسازد.",
        "نام کانفیگ را کوتاه و لاتین (حداکثر ۱۵ حرف) وارد کن؛ فایل با همین نام ساخته می‌شود.",
        "DNS اختیاری است؛ اگر انتخاب نکنی همان DNS خود کانفیگ استفاده می‌شود.",
        "بعد از ساخت، کانفیگ را کپی، ذخیره یا ارسال کن و در WireGuard وارد کن.",
        "اگر کانفیگ وصل نشد، دوباره بساز تا سرور دیگری انتخاب شود."
    )
    for (l in noteLines) notes.addView(label("• $l", 12f, muted, false, false), vlp(0, 4))
    content.addView(notes, vlp(8, 4))
}
