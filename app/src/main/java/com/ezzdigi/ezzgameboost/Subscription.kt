package com.ezzdigi.ezzgameboost

import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- Premium subscription (per-user account) --------------------------------
//
// The account lives on the subscription server (SUB_API). After a login the app keeps
// a token and downloads that account's servers:
//   - V2Gaming links   -> prefs "subV2"  (lines "name<TAB>link")
//   - WireGuard configs (from the subscription server / panel) -> the normal "wg" config folder, names remembered in "subWgNames"
// Both are shown only in the "اشتراک ویژه" tab and are removed on logout / expiry.

class SubStatus(
    val active: Boolean,
    val expires: String,
    val days: Int,
    val err: String,
    val definite: Boolean
)

@Volatile
private var subSyncBusy = false

private const val SESSION_LOST = "با این حساب در دستگاه دیگری وارد شده‌اند؛ دوباره وارد شو."

internal fun jsonDays(o: JSONObject): Int {
    for (k in arrayOf("days_left", "daysLeft", "remaining_days", "days_remaining", "days")) {
        if (o.has(k)) {
            val v = o.optInt(k, -1)
            if (v >= 0) return v
        }
    }
    return -1
}

private fun normDigits(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        sb.append(
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        )
    }
    return sb.toString()
}

private fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): IntArray {
    val jy2 = jy + 1595
    var days = -355668 + (365 * jy2) + ((jy2 / 33) * 8) + (((jy2 % 33) + 3) / 4) + jd +
        (if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186)
    var gy = 400 * (days / 146097)
    days %= 146097
    if (days > 36524) {
        days--
        gy += 100 * (days / 36524)
        days %= 36524
        if (days >= 365) days++
    }
    gy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        gy += (days - 1) / 365
        days = (days - 1) % 365
    }
    var gd = days + 1
    val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
    val sal = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var gm = 0
    while (gm < 13 && gd > sal[gm]) {
        gd -= sal[gm]
        gm++
    }
    return intArrayOf(gy, gm, gd)
}

// Accepts 2026-12-31, 2026/12/31, 20261231, a Jalali date (1405/10/09), or a unix timestamp.
// Returns the end of that day (local time) in milliseconds.
internal fun parseExpiryMillis(raw: String): Long? {
    val s = normDigits(raw).trim()
    if (s.isEmpty()) return null
    var y: Int
    var mo: Int
    var d: Int
    if (s.all { it.isDigit() }) {
        if (s.length == 8) {
            y = s.substring(0, 4).toInt()
            mo = s.substring(4, 6).toInt()
            d = s.substring(6, 8).toInt()
        } else if (s.length > 9) {
            return s.toLongOrNull()?.times(1000L)
        } else {
            return null
        }
    } else {
        val parts = s.split(Regex("[-/.]"))
        if (parts.size != 3) return null
        y = parts[0].toIntOrNull() ?: return null
        mo = parts[1].toIntOrNull() ?: return null
        d = parts[2].toIntOrNull() ?: return null
    }
    if (y < 100) y += 2000
    val cal = java.util.Calendar.getInstance()
    if (y > 1700) {
        cal.set(y, mo - 1, d, 23, 59, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    } else if (y in 1300..1500) {
        val g = jalaliToGregorian(y, mo, d)
        cal.set(g[0], g[1] - 1, g[2], 23, 59, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
    return null
}

internal fun MainActivity.subToken(): String {
    return prefs.getString("sub_token", "") ?: ""
}

internal fun MainActivity.subUser(): String {
    return prefs.getString("sub_user", "") ?: ""
}

internal fun MainActivity.subV2Items(): List<V2Item> {
    val raw = prefs.getString("sub_v2", "") ?: ""
    val list = mutableListOf<V2Item>()
    for (line in raw.split("\n")) {
        val tr = line.trim()
        if (tr.isEmpty()) continue
        val p = tr.split("\t", limit = 2)
        if (p.size == 2) {
            val name = p[0].trim()
            val link = p[1].trim()
            if (link.isNotEmpty()) {
                val item = parseV2Config(link)
                if (item != null) {
                    list.add(V2Item(name, item.raw, item.type, item.server, item.port, item.uuid, item.flow, item.security, item.sni, item.pbk, item.sid, item.typeStr))
                }
            }
        }
    }
    return list
}

internal fun MainActivity.subWgList(): List<String> {
    val raw = prefs.getString("sub_wg_names", "") ?: ""
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun MainActivity.loginSub(u: String, p: String, onDone: () -> Unit) {
    val username = u.trim()
    val password = p.trim()
    if (username.isEmpty() || password.isEmpty()) {
        toast("نام کاربری و رمز عبور را وارد کن")
        return
    }
    toast("در حال ورود...")
    Thread {
        try {
            val data = "user=" + Uri.encode(username) + "&pass=" + Uri.encode(password)
            val res = httpPost(SUB_API + "?a=login", data, 10000, 15000)
            if (res == null) {
                runOnUiThread { toast("خطا در ارتباط با سرور اشتراک") }
                return@Thread
            }
            val json = JSONObject(res)
            if (!json.optBoolean("ok", false)) {
                val err = json.optString("error", "نام کاربری یا رمز عبور اشتباه است")
                runOnUiThread { toast(err) }
                return@Thread
            }
            val token = json.optString("token", "")
            if (token.isEmpty()) {
                runOnUiThread { toast("پاسخ نامعتبر از سرور") }
                return@Thread
            }
            prefs.edit().putString("sub_token", token).putString("sub_user", username).apply()
            syncSubDataSync()
            runOnUiThread {
                toast("با موفقیت وارد شدی!")
                onDone()
                refreshTab()
            }
        } catch (e: Exception) {
            runOnUiThread { toast("خطا: ${e.localizedMessage ?: "مشکل در اتصال"}") }
        }
    }.start()
}

internal fun MainActivity.logoutSub() {
    val names = subWgList()
    val ed = prefs.edit()
    for (name in names) {
        deleteWgConfigInternal(name)
    }
    ed.remove("sub_token")
        .remove("sub_user")
        .remove("sub_v2")
        .remove("sub_wg_names")
        .remove("sub_expires")
        .remove("sub_days")
        .remove("sub_status_checked")
        .apply()
    toast("از حساب خارج شدی")
    refreshTab()
}

internal fun MainActivity.checkSubStatusQuick(): SubStatus {
    val token = subToken()
    if (token.isEmpty()) return SubStatus(false, "", -1, "", false)
    val expStr = prefs.getString("sub_expires", "") ?: ""
    val days = prefs.getInt("sub_days", -1)
    val expMs = parseExpiryMillis(expStr)
    if (expMs != null && System.currentTimeMillis() > expMs) {
        return SubStatus(false, expStr, 0, "اشتراک شما منقضی شده است.", true)
    }
    if (days == 0) {
        return SubStatus(false, expStr, 0, "اشتراک شما منقضی شده است.", true)
    }
    return SubStatus(true, expStr, days, "", false)
}

internal fun MainActivity.syncSubData() {
    if (subToken().isEmpty()) return
    if (subSyncBusy) return
    subSyncBusy = true
    Thread {
        try {
            syncSubDataSync()
        } finally {
            subSyncBusy = false
        }
    }.start()
}

private fun MainActivity.syncSubDataSync() {
    val token = subToken()
    if (token.isEmpty()) return
    try {
        val body = httpGet(SUB_API + "?a=status&t=" + Uri.encode(token))
        if (body != null) {
            val json = JSONObject(body)
            if (json.has("session") && !json.optBoolean("session", true)) {
                runOnUiThread {
                    toast(SESSION_LOST)
                    logoutSub()
                    showTab(4)
                }
                return
            }
            if (json.optBoolean("ok", false)) {
                val active = json.optBoolean("active", true)
                val expires = json.optString("expires", json.optString("expire", ""))
                val days = jsonDays(json)
                val expMs = parseExpiryMillis(expires)
                val expiredByTime = expMs != null && System.currentTimeMillis() > expMs
                val expiredByDays = days == 0
                if (!active || expiredByTime || expiredByDays) {
                    runOnUiThread {
                        toast("اشتراک منقضی شده یا غیرفعال است.")
                        logoutSub()
                        showTab(4)
                    }
                    return
                }
                prefs.edit().putString("sub_expires", expires).putInt("sub_days", days).putLong("sub_status_checked", System.currentTimeMillis()).apply()
            }
        }

        val bodyV2 = httpGet(SUB_API + "?a=list&f=v2ray.json&t=" + Uri.encode(token), 8000, 1500000)
        if (bodyV2 != null) {
            val jv = JSONObject(bodyV2)
            if (jv.has("session") && !jv.optBoolean("session", true)) {
                runOnUiThread {
                    toast(SESSION_LOST)
                    logoutSub()
                    showTab(4)
                }
                return
            }
            if (jv.optBoolean("ok", false)) {
                val arr = jv.optJSONArray("items") ?: jv.optJSONArray("servers")
                if (arr != null) {
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name", "V2Ray ${i + 1}")
                        val link = item.optString("link", item.optString("config", ""))
                        if (link.isNotEmpty()) {
                            sb.append(name).append("\t").append(link).append("\n")
                        }
                    }
                    prefs.edit().putString("sub_v2", sb.toString()).apply()
                }
            }
        }

        val bodyWg: String? = httpGet(SUB_API + "?a=list&f=wireguard.json&t=" + Uri.encode(token), 10000, 1500000)
        if (bodyWg != null) {
            val jw = JSONObject(bodyWg)
            if (jw.has("session") && !jw.optBoolean("session", true)) {
                runOnUiThread {
                    toast(SESSION_LOST)
                    logoutSub()
                    showTab(4)
                }
                return
            }
            if (jw.optBoolean("ok", false)) {
                val arr = jw.optJSONArray("items") ?: jw.optJSONArray("servers")
                if (arr != null) {
                    val oldNames = subWgList()
                    val newNames = mutableListOf<String>()
                    val keepNames = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name", "Pro-WG ${i + 1}").trim()
                        val conf = item.optString("config", item.optString("content", ""))
                        if (name.isNotEmpty() && conf.isNotEmpty()) {
                            val cleanName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                            keepNames.add(cleanName)
                            newNames.add(cleanName)
                            try {
                                val cfg = Config.parse(ByteArrayInputStream(conf.toByteArray(Charsets.UTF_8)))
                                saveWgConfigInternal(cleanName, cfg)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    for (old in oldNames) {
                        if (!keepNames.contains(old)) {
                            if (WgState.activeName == old && WgState.up) {
                                stopWgVpn()
                            }
                            deleteWgConfigInternal(old)
                        }
                    }
                    prefs.edit().putString("sub_wg_names", newNames.joinToString(",")).apply()
                }
            }
        }
        runOnUiThread {
            refreshTab()
        }
    } catch (_: Exception) {
    }
}

private fun MainActivity.deleteWgConfigInternal(name: String) {
    try {
        val file = java.io.File(filesDir, "wg_$name.conf")
        if (file.exists()) file.delete()
    } catch (_: Exception) {
    }
}

private fun MainActivity.saveWgConfigInternal(name: String, cfg: Config) {
    try {
        val file = java.io.File(filesDir, "wg_$name.conf")
        java.io.FileOutputStream(file).use { os ->
            cfg.writeTo(os)
        }
    } catch (_: Exception) {
    }
}

// ----- "اشتراک ویژه" (Account tab) ----------------------------------------------------

internal fun MainActivity.buildSubAccount() {
    val token = subToken()
    if (token.isEmpty()) {
        buildSubLogin()
        return
    }

    section("اشتراک ویژه")
    val hCard = card()
    val userLbl = label("کاربر: " + subUser(), 14f, white, true, false)
    hCard.addView(userLbl, vlp(0, 6))

    val statusLbl = label("در حال بررسی وضعیت اشتراک...", 12.5f, muted, false, false)
    hCard.addView(statusLbl, vlp(0, 8))

    val aRow = LinearLayout(this)
    aRow.orientation = LinearLayout.HORIZONTAL

    val bRefresh = button("🔄 بروزرسانی سرورها", true) {
        toast("در حال دریافت سرورها...")
        syncSubData()
    }
    val bOut = button("خروج از حساب", false) {
        logoutSub()
        showTab(4)
    }
    val a1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    a1.marginEnd = dp(4)
    val a2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    a2.marginStart = dp(4)
    aRow.addView(bRefresh, a1)
    aRow.addView(bOut, a2)
    hCard.addView(aRow, vlp(4, 6))
    hCard.addView(button("🛒 تمدید / دریافت اکانت", false) { openUrl(SUB_BUY_URL) }, vlp(0, 0))
    content.addView(hCard, vlp(0, 4))

    vpnRefresh = { paintHead() }
    vpnRender = { paintHead() }
    paintHead()
}

// Shown in the EzzGaming / Pro Gaming tabs while nobody is logged in.
private fun MainActivity.subNeedLogin(title: String) {
    section(title)
    val c = card()
    c.addView(label("برای دیدن سرورها اول وارد «اشتراک ویژه» شو.", 13f, muted, true), vlp(0, 6))
    c.addView(button("رفتن به اشتراک ویژه", true) { showTab(4) }, vlp(0, 0))
    content.addView(c, vlp(0, 4))
}

// ----- "EzzGaming" tab (V2Ray servers of the account) -------------------------------

internal fun MainActivity.buildSubV2() {
    if (subToken().isEmpty()) {
        subNeedLogin("EzzGaming")
        return
    }
    section("EzzGaming")
    val vCard = card()
    val vStatus = label("", 11.5f, accent, false, false)
    vCard.addView(vStatus, vlp(0, 8))
    val vList = LinearLayout(this)
    vList.orientation = LinearLayout.VERTICAL
    vCard.addView(vList)
    content.addView(vCard, vlp(0, 4))

    var vKey = ""
    fun renderV(force: Boolean) {
        val items = subV2Items()
        val st = v2Status()
        vStatus.text = if (st.isEmpty()) "" else Tr.t(st)
        vStatus.visibility = if (st.isEmpty()) View.GONE else View.VISIBLE
        val k = items.joinToString("|") { it.raw.hashCode().toString() } + "|" + V2VpnService.running + "|" +
            V2VpnService.busy + "|" + V2VpnService.activeId + "|" + V2VpnService.lastError + "|" + V2VpnService.probe
        if (!force && k == vKey) return
        vKey = k
        vList.removeAllViews()
        if (items.isEmpty()) {
            vList.addView(label("هنوز کانفیگی نیست.", 12.5f, muted))
            return
        }
        for (item in items) vList.addView(subV2Row(item), vlp(0, 6))
    }

    vpnRefresh = { renderV(false) }
    vpnRender = { renderV(true) }
    renderV(true)
}

// ----- "Pro Gaming" tab (WireGuard servers of the account) --------------------------

internal fun MainActivity.buildSubWg() {
    if (subToken().isEmpty()) {
        subNeedLogin("Pro Gaming")
        return
    }
    section("Pro Gaming")
    val wCard = card()
    val wStatus = label("", 15f, white, true, false)
    wCard.addView(wStatus)
    val wList = LinearLayout(this)
    wList.orientation = LinearLayout.VERTICAL
    wCard.addView(wList, vlp(8, 0))
    content.addView(wCard, vlp(0, 4))

    // DNS Pro: names only, with test + connect
    section("X-PRO")
    val dCard = card()
    val dList = LinearLayout(this)
    dList.orientation = LinearLayout.VERTICAL
    dCard.addView(dList)
    content.addView(dCard, vlp(0, 4))
    val dnsRes = HashMap<String, String>()
    var dKey = ""
    fun renderD(force: Boolean) {
        val items = subDnsList()
        val run = DnsVpnService.running
        val act = DnsVpnService.activeName
        val k = items.joinToString(",") { it.name } + "|$run|$act|" + dnsRes.toString()
        if (!force && k == dKey) return
        dKey = k
        dList.removeAllViews()
        if (items.isEmpty()) {
            dList.addView(label("هنوز DNSی نیست.", 12.5f, muted))
            return
        }
        for (pr in items) {
            dList.addView(
                subDnsRow(pr, run && act == pr.name, dnsRes[pr.name] ?: "") { r ->
                    dnsRes[pr.name] = r
                    renderD(true)
                },
                vlp(0, 6)
            )
        }
    }

    var wKey = ""
    fun renderW(force: Boolean) {
        val up = WgState.up
        val act = WgState.activeName
        wStatus.text = if (wgBusy) "⏳ ..." else if (up) Tr.t("🟢 متصل: ") + act else Tr.t("⚪ قطع")
        wStatus.setTextColor(if (up) good else white)
        val names = subWgList()
        val k = "$up|$act|$wgBusy|" + names.joinToString(",") + "|" + wgTestRes.toString()
        if (!force && k == wKey) return
        wKey = k
        wList.removeAllViews()
        if (names.isEmpty()) {
            wList.addView(label("هنوز کانفیگی نیست.", 12.5f, muted))
            return
        }
        for (base in names) wList.addView(subWgRow(base), vlp(0, 6))
    }

    vpnRefresh = { renderW(false); renderD(false) }
    vpnRender = { renderW(true); renderD(true) }
    renderW(true)
    renderD(true)
}

private fun MainActivity.buildSubLogin() {
    section("اشتراک ویژه")
    val sCard = card()
    sCard.addView(label("وارد حساب نشده‌ای", 13f, muted, true), vlp(0, 6))
    val uIn = input("نام کاربری")
    uIn.textDirection = View.TEXT_DIRECTION_LTR
    sCard.addView(uIn, vlp(0, 8))
    val pIn = input("رمز عبور")
    pIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    pIn.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
    pIn.textDirection = View.TEXT_DIRECTION_LTR
    sCard.addView(pIn, vlp(0, 8))
    sCard.addView(
        button("ورود به حساب", true) {
            loginSub(uIn.text.toString(), pIn.text.toString()) {
                pIn.setText("")
                if (tab == 4) showTab(4)
            }
        },
        vlp(0, 6)
    )
    sCard.addView(button("🛒 دریافت اکانت", false) { openUrl(SUB_BUY_URL) }, vlp(0, 0))
    content.addView(sCard, vlp(0, 4))
}

private fun MainActivity.subV2Row(item: V2Item): LinearLayout {
    val myId = item.raw.hashCode().toString()
    val isActive = V2VpnService.running && V2VpnService.activeId == myId
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(12), dp(10), dp(12), dp(10))
    box.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
    box.addView(label(if (isActive) item.name + "  ✓" else item.name, 13.5f, white, true, false))
    val resLbl = label("", 12f, white, false, false)
    box.addView(resLbl)
    val br = LinearLayout(this)
    br.orientation = LinearLayout.HORIZONTAL
    val b1 = smallBtn("🧪 تست", false) {
        resLbl.text = "در حال تست..."
        val start = System.currentTimeMillis()
        testV2Delay(item) { ms ->
            runOnUiThread {
                resLbl.text = if (ms >= 0) "پینگ: $ms ms" else "خطا در تست"
            }
        }
    }
    val b2 = smallBtn(if (isActive) "⏹️ قطع" else "▶️ اتصال", true) {
        if (isActive) {
            stopV2Vpn(this)
        } else {
            startV2Vpn(this, item, myId)
        }
    }
    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp1.marginEnd = dp(6)
    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    br.addView(b1, lp1)
    br.addView(b2, lp2)
    box.addView(br, vlp(0, 6))
    return box
}

private fun MainActivity.subWgRow(base: String): LinearLayout {
    val up = WgState.up
    val active = WgState.activeName == base
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(12), dp(10), dp(12), dp(10))
    box.background = rounded(if (active && up) accentAlpha(35) else panel2, 12, if (active && up) accent else stroke)
    box.addView(label(if (active && up) "$base  ✓" else base, 13.5f, white, true, false))

    val resText = wgTestRes[base] ?: ""
    val resLbl = label(resText, 12f, white, false, false)
    box.addView(resLbl)

    val br = LinearLayout(this)
    br.orientation = LinearLayout.HORIZONTAL
    val b1 = smallBtn("🧪 تست پینگ", false) {
        wgTestRes[base] = "تست..."
        refreshTab()
        testWgPing(base) { ms ->
            runOnUiThread {
                wgTestRes[base] = if (ms >= 0) "پینگ: $ms ms" else "خطا در تست"
                refreshTab()
            }
        }
    }
    val b2 = smallBtn(if (active && up) "⏹️ قطع" else "▶️ اتصال", true) {
        if (active && up) {
            stopWgVpn()
        } else {
            startWgVpn(base)
        }
    }
    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp1.marginEnd = dp(6)
    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    br.addView(b1, lp1)
    br.addView(b2, lp2)
    box.addView(br, vlp(0, 6))
    return box
}

private fun MainActivity.subDnsRow(pr: DnsProfile, active: Boolean, testRes: String, onTest: (String) -> Unit): LinearLayout {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(12), dp(10), dp(12), dp(10))
    box.background = rounded(if (active) accentAlpha(35) else panel2, 12, if (active) accent else stroke)
    box.addView(label(if (active) pr.name + "  ✓" else pr.name, 13.5f, white, true, false))

    if (testRes.isNotEmpty()) {
        box.addView(label(testRes, 12f, white, false, false))
    }

    val br = LinearLayout(this)
    br.orientation = LinearLayout.HORIZONTAL
    val b1 = smallBtn("🧪 تست", false) {
        onTest("تست...")
        testDnsDelay(pr) { ms ->
            onTest(if (ms >= 0) "پینگ: $ms ms" else "خطا در تست")
        }
    }
    val b2 = smallBtn(if (active) "⏹️ قطع" else "▶️ اتصال", true) {
        if (active) {
            stopDnsVpn()
        } else {
            startDnsVpn(pr)
        }
    }
    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp1.marginEnd = dp(6)
    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
    br.addView(b1, lp1)
    br.addView(b2, lp2)
    box.addView(br, vlp(0, 6))
    return box
}

private fun MainActivity.paintHead() {
    val st = checkSubStatusQuick()
    // Find status label or view logic as needed
}
