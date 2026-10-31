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
        } else {
            val n = s.toLongOrNull() ?: return null
            return when {
                s.length >= 12 -> n
                s.length >= 9 -> n * 1000L
                else -> null
            }
        }
    } else {
        val m = Regex("(\\d{4})\\D(\\d{1,2})\\D(\\d{1,2})").find(s) ?: return null
        y = m.groupValues[1].toInt()
        mo = m.groupValues[2].toInt()
        d = m.groupValues[3].toInt()
    }
    if (mo !in 1..12 || d !in 1..31) return null
    if (y < 1700) {
        val g = jalaliToGregorian(y, mo, d)
        y = g[0]
        mo = g[1]
        d = g[2]
    }
    val c = java.util.Calendar.getInstance()
    c.clear()
    c.set(y, mo - 1, d, 23, 59, 59)
    return c.timeInMillis
}

// Whole days left (0 = expired); null when the server gave nothing we can read.
internal fun MainActivity.subDaysLeft(): Int? {
    val end = parseExpiryMillis(prefs.getString("subExp", "") ?: "")
    if (end != null) {
        val diff = end - System.currentTimeMillis()
        return if (diff <= 0L) 0 else ((diff + 86399999L) / 86400000L).toInt()
    }
    val d = prefs.getInt("subDays", -1)
    return if (d >= 0) d else null
}

internal fun MainActivity.subToken(): String = prefs.getString("subCode", "") ?: ""

internal fun MainActivity.subV2Items(): List<V2Item> {
    val out = ArrayList<V2Item>()
    val raw = prefs.getString("subV2", "") ?: ""
    for (line in raw.split("\n")) {
        if (line.isBlank()) continue
        val p = line.split("\t", limit = 2)
        val name = if (p.size == 2) p[0].trim() else ""
        val link = if (p.size == 2) p[1] else p[0]
        val it0 = parseV2Link(link, name) ?: continue
        out.add(V2Item(it0.name, it0.proto, it0.host, it0.port, it0.raw, true))
    }
    return out
}

// DNS Pro list (GitHub dns-pro.json, same format as dns.json), stored as "name<TAB>ip<TAB>ip" lines.
private fun MainActivity.parseDnsLines(json: String): List<String>? {
    return try {
        val arr = JSONObject(json).optJSONArray("providers") ?: return null
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
        lines
    } catch (_: Throwable) {
        null
    }
}

internal fun MainActivity.subDnsList(): List<DnsProvider> {
    val out = ArrayList<DnsProvider>()
    val raw = prefs.getString("subDns", "") ?: ""
    for (line in raw.split("\n")) {
        if (line.isBlank()) continue
        val p = line.split("\t")
        val ips = ArrayList<String>()
        for (i in 1 until p.size) if (p[i].isNotBlank()) ips.add(p[i].trim())
        if (p[0].isBlank() || ips.isEmpty()) continue
        out.add(DnsProvider(p[0].trim(), ips[0], ips.getOrElse(1) { "" }, ips.getOrElse(2) { "" }, ips.getOrElse(3) { "" }))
    }
    return out
}

internal fun MainActivity.subWgNameSet(): Set<String> =
    prefs.getStringSet("subWgNames", null) ?: emptySet<String>()

internal fun MainActivity.subWgList(): List<String> {
    val set = subWgNameSet()
    val out = ArrayList<String>()
    for (f in listCfg("wg")) {
        val base = f.removeSuffix(".conf")
        if (set.contains(base)) out.add(base)
    }
    return out
}

// Deletes the account's WireGuard servers (and V2Gaming links) from the phone.
internal fun MainActivity.removeSubConfig() {
    try {
        for (n in subWgNameSet()) deleteCfg("wg", "$n.conf")
        prefs.edit().remove("subWgNames").remove("subWgGh").remove("subV2").remove("subDns").apply()
    } catch (_: Throwable) {
    }
}

// Same, but a tunnel that is currently up through one of the account's servers is stopped first.
internal fun MainActivity.removeSubItems() {
    try {
        if (WgState.up && subWgNameSet().contains(WgState.activeName)) disconnectWg()
        val ids = subV2Items().map { it.raw.hashCode().toString() }
        if (V2VpnService.running && ids.contains(V2VpnService.activeId)) V2VpnService.requestStop(this)
        if (DnsVpnService.running && subDnsList().any { it.name == DnsVpnService.activeName }) DnsVpnService.requestStop(this)
    } catch (_: Throwable) {
    }
    removeSubConfig()
}

// Stable per-device id (hashed); the server locks an account to the first device that logs in.
internal fun MainActivity.deviceId(): String {
    var seed = try {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
    } catch (_: Throwable) {
        ""
    }
    if (seed.isEmpty()) {
        seed = prefs.getString("devFallback", "") ?: ""
        if (seed.isEmpty()) {
            seed = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("devFallback", seed).apply()
        }
    }
    val md = java.security.MessageDigest.getInstance("SHA-256").digest(("ezzgb|" + seed).toByteArray())
    return md.joinToString("") { "%02x".format(it) }
}

internal fun MainActivity.fetchSubStatus(token: String): SubStatus {
    val body = httpGet(SUB_API + "?a=status&t=" + Uri.encode(token))
    if (body == null) {
        val http4 = lastHttpErr.startsWith("HTTP 4")
        val msg = when {
            lastHttpErr == "HTTP 401" -> SESSION_LOST
            http4 -> "حساب نامعتبر یا منقضی است."
            else -> "سرور اشتراک در دسترس نیست (" + lastHttpErr + ")"
        }
        return SubStatus(false, "", -1, msg, http4)
    }
    return try {
        val o = JSONObject(body)
        val ok = o.optBoolean("active", false)
        SubStatus(ok, o.optString("expires", ""), jsonDays(o), if (ok) "" else "حساب نامعتبر یا منقضی است.", true)
    } catch (_: Throwable) {
        SubStatus(false, "", -1, "پاسخ سرور معتبر نیست.", false)
    }
}

// Drops comments, blank lines and keys the WireGuard library does not know; fixes spacing around "=".
private fun wgSanitize(text: String): String {
    val known = setOf(
        "privatekey", "publickey", "presharedkey", "address", "dns", "mtu", "listenport",
        "endpoint", "allowedips", "persistentkeepalive",
        // AmneziaWG obfuscation keys: must survive cleaning, or the server never answers
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime"
    )
    val out = ArrayList<String>()
    var first = true
    for (ln in text.replace("\r", "").split("\n")) {
        val t = ln.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue
        if (t.startsWith("[")) {
            val h = t.trim('[', ']', ' ').lowercase()
            if (h == "interface" || h == "peer") {
                if (!first) out.add("")
                first = false
                out.add(if (h == "interface") "[Interface]" else "[Peer]")
            }
            continue
        }
        val i = t.indexOf('=')
        if (i <= 0) continue
        val k = t.substring(0, i).trim()
        var v = t.substring(i + 1).trim()
        if (v.isEmpty() || !known.contains(k.lowercase())) continue
        if (k.lowercase() == "dns" || k.lowercase() == "address" || k.lowercase() == "allowedips") {
            v = v.split(",").joinToString(",") { it.trim() }
        }
        out.add(k + " = " + v)
    }
    return out.joinToString("\n")
}

private fun MainActivity.parseWgEntries(body: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    try {
        val arr = JSONObject(body).getJSONArray("configs")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nm = o.optString("name")
            var text = o.optString("conf").replace("\\n", "\n")
            val url = o.optString("url")
            if (text.isEmpty() && url.isNotEmpty()) text = httpGet(githubRawUrl(url), 10000, 262144) ?: ""
            if (text.isBlank()) continue
            // Accept every config the account has. If it does not parse as is, try a cleaned copy;
            // otherwise keep it unchanged so connecting shows what is wrong with it.
            var use = text
            try {
                wgParseCheck(text)
            } catch (_: Throwable) {
                val cleaned = wgSanitize(text)
                try {
                    wgParseCheck(cleaned)
                    use = cleaned
                } catch (_: Throwable) {
                }
            }
            out.add(Pair(nm, use))
        }
    } catch (_: Throwable) {
    }
    return out
}

// Downloads the account's status and its two server lists. onDone gets "" on success or a message.
internal fun MainActivity.syncSubscription(manual: Boolean, onDone: ((String) -> Unit)? = null) {
    val token = subToken()
    if (token.isEmpty() || subSyncBusy) {
        if (manual && onDone != null) onDone("")
        return
    }
    subSyncBusy = true
    runBg {
        var msg = ""
        var expired = false
        var sessionLost = false
        try {
            val st = fetchSubStatus(token)
            if (!st.definite) {
                msg = st.err
            } else if (!st.active) {
                if (st.err == SESSION_LOST) {
                    sessionLost = true
                } else {
                    prefs.edit().putBoolean("subActive", false).putString("subExp", st.expires)
                        .putInt("subDays", st.days).apply()
                    expired = true
                }
                msg = st.err
            } else {
                prefs.edit().putBoolean("subActive", true).putString("subExp", st.expires)
                    .putInt("subDays", st.days).apply()

                // V2Gaming
                val bodyV2 = httpGet(SUB_API + "?a=list&f=v2ray.json&t=" + Uri.encode(token), 8000, 1500000)
                if (bodyV2 != null && bodyV2.trimStart().startsWith("{")) {
                    val pairs = parseV2Json(bodyV2)
                    if (pairs != null && subToken() == token) {
                        val lines = pairs.joinToString("\n") {
                            it.first.replace("\t", " ").replace("\n", " ") + "\t" + it.second
                        }
                        prefs.edit().putString("subV2", lines).apply()
                    }
                } else if (bodyV2 == null) {
                    msg = "سرور اشتراک در دسترس نیست (" + lastHttpErr + ")"
                }

                // WireGuard Gaming
                // Two sources: the subscription server (admin panel, per account) and GitHub
                // wireguard-pro.json (same format as wireguard.json, shared by every account).
                // If one source is unreachable, its last synced configs stay.
                val bodyWg: String? = httpGet(SUB_API + "?a=list&f=wireguard.json&t=" + Uri.encode(token), 10000, 1500000)
                val panelEntries: List<Pair<String, String>>? =
                    if (bodyWg != null && bodyWg.trimStart().startsWith("{")) parseWgEntries(bodyWg) else null
                var ghEntries: List<Pair<String, String>>? = null
                for (u in remoteUrls(ghRepo(), "wireguard-pro.json")) {
                    val b = httpGet(u, 8000, 1500000)
                    if (b != null && b.trimStart().startsWith("{")) {
                        ghEntries = parseWgEntries(b)
                        break
                    }
                }
                if (panelEntries != null || ghEntries != null) {
                    if (subToken() == token) {
                        val oldSet = subWgNameSet()
                        val oldGh: Set<String> = prefs.getStringSet("subWgGh", null) ?: emptySet<String>()
                        val listed = ArrayList<String>()
                        val ghNames = ArrayList<String>()
                        // a source that failed keeps its previous names
                        if (panelEntries == null) for (n in oldSet) if (!oldGh.contains(n)) listed.add(n)
                        if (ghEntries == null) for (n in oldGh) if (oldSet.contains(n) && !listed.contains(n)) {
                            listed.add(n)
                            ghNames.add(n)
                        }
                        fun addAll(list: List<Pair<String, String>>, fromGh: Boolean) {
                            for (e in list) {
                                val base0 = cleanName(e.first, "wg")
                                var b = base0
                                var i = 2
                                while (listed.contains(b)) {
                                    b = base0.take(12) + "_" + i
                                    i++
                                }
                                listed.add(b)
                                if (fromGh) ghNames.add(b)
                                writeCfg("wg", "$b.conf", e.second)
                            }
                        }
                        if (panelEntries != null) addAll(panelEntries, false)
                        if (ghEntries != null) addAll(ghEntries, true)
                        for (n in oldSet) {
                            if (!listed.contains(n)) deleteCfg("wg", "$n.conf")
                        }
                        prefs.edit().putStringSet("subWgNames", HashSet<String>(listed))
                            .putStringSet("subWgGh", HashSet<String>(ghNames)).apply()
                    }
                } else if (bodyWg == null && msg.isEmpty()) {
                    msg = "سرور اشتراک در دسترس نیست (" + lastHttpErr + ")"
                }
                // DNS Pro (GitHub dns-pro.json)
                var bodyDns: String? = null
                for (u in remoteUrls(ghRepo(), "dns-pro.json")) {
                    val b = httpGet(u, 8000, 600000)
                    if (b != null && b.trimStart().startsWith("{")) {
                        bodyDns = b
                        break
                    }
                }
                if (bodyDns != null && subToken() == token) {
                    val dl = parseDnsLines(bodyDns)
                    if (dl != null) prefs.edit().putString("subDns", dl.joinToString("\n")).apply()
                }
                if (msg.isEmpty()) prefs.edit().putLong("subSyncTs", System.currentTimeMillis()).apply()
            }
        } catch (_: Throwable) {
            if (msg.isEmpty()) msg = "همگام‌سازی ناموفق بود."
        } finally {
            subSyncBusy = false
        }
        ui {
            if (expired && subToken() == token) removeSubItems()
            if (sessionLost && subToken() == token) {
                logoutSub()
                toast(msg)
                if (tab == 4 || tab == 6 || tab == 7) showTab(tab)
            }
            vpnRender?.invoke()
            if (manual && onDone != null) onDone(msg)
        }
    }
}

internal fun MainActivity.loginSub(user: String, pass: String, onDone: () -> Unit) {
    val u = user.trim()
    if (u.isEmpty() || pass.isEmpty()) {
        toast("نام کاربری و رمز را وارد کن.")
        return
    }
    toast("در حال ورود…")
    runBg {
        val r = httpPost(
            SUB_API + "?a=login",
            "u=" + Uri.encode(u) + "&p=" + Uri.encode(pass) + "&d=" + deviceId()
        )
        var ok = false
        var token = ""
        var exp = ""
        var days = -1
        var err = ""
        if (r == null) {
            err = "سرور اشتراک در دسترس نیست (" + lastHttpErr + ")"
        } else {
            try {
                val o = JSONObject(r.second)
                ok = o.optBoolean("ok", false)
                token = o.optString("token", "")
                exp = o.optString("expires", "")
                days = jsonDays(o)
                if (!ok) err = o.optString("error", "ورود ناموفق بود.")
            } catch (_: Throwable) {
                err = "پاسخ سرور معتبر نیست."
            }
            if (ok && token.isEmpty()) {
                ok = false
                err = "پاسخ سرور معتبر نیست."
            }
        }
        ui {
            if (ok) {
                // a different account must not see the previous account's servers
                if (subToken().isNotEmpty() && subToken() != token) removeSubItems()
                prefs.edit().putString("subCode", token).putString("subUser", u)
                    .putBoolean("subActive", true).putString("subExp", exp)
                    .putInt("subDays", days).putLong("subSyncTs", 0L).apply()
                toast("وارد شدی ✅")
                onDone()
                refreshRemoteDns(true, null)
                syncSubscription(false)
            } else {
                toast(err)
            }
        }
    }
}

internal fun MainActivity.logoutSub() {
    removeSubItems()
    prefs.edit().remove("subCode").remove("subUser").remove("subExp").remove("subDays")
        .remove("subSyncTs").putBoolean("subActive", false).apply()
    // back to the free DNS list right away (premium DNS entries disappear too)
    refreshRemoteDns(true, null)
}

// ----- "اشتراک ویژه" tab ---------------------------------------------------------

internal fun MainActivity.buildSubscription() {
    if (subToken().isEmpty()) {
        buildSubLogin()
        return
    }

    // ---------------- Account: status + remaining days
    section("اشتراک ویژه")
    val hCard = card()
    val userLbl = label("👤 " + (prefs.getString("subUser", "") ?: ""), 14f, white, true, false)
    hCard.addView(userLbl)
    val daysLbl = label("", 24f, good, true, false)
    hCard.addView(daysLbl, vlp(8, 0))
    val expLbl = label("", 12f, muted, false, false)
    hCard.addView(expLbl, vlp(2, 6))

    fun paintHead() {
        val act = prefs.getBoolean("subActive", false)
        val d = subDaysLeft()
        val exp = prefs.getString("subExp", "") ?: ""
        if (!act || (d != null && d <= 0)) {
            daysLbl.text = Tr.t("❌ اشتراک منقضی شده")
            daysLbl.setTextColor(bad)
        } else if (d == null) {
            daysLbl.text = Tr.t("✅ اشتراک فعال")
            daysLbl.setTextColor(good)
        } else {
            daysLbl.text = if (Tr.en) "$d days left" else "$d روز باقی‌مانده"
            daysLbl.setTextColor(if (d <= 3) bad else if (d <= 7) warn else good)
        }
        if (exp.isEmpty()) {
            expLbl.visibility = View.GONE
        } else {
            expLbl.visibility = View.VISIBLE
            expLbl.text = Tr.t("تاریخ پایان:") + " " + exp
        }
    }

    val aRow = LinearLayout(this)
    aRow.orientation = LinearLayout.HORIZONTAL
    val bRefresh = button("🔄 به‌روزرسانی", false) {
        toast("در حال به‌روزرسانی…")
        syncSubscription(true) { m ->
            toast(if (m.isEmpty()) "به‌روز شد ✅" else m)
        }
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
        if (item.proto == "hysteria2" || item.proto == "hy2" || item.proto == "tuic") {
            resLbl.text = Tr.t("این پروتکل UDP است؛ تست TCP معنی ندارد.")
        } else {
            resLbl.text = Tr.t("در حال تست…")
            runBg {
                val ms = tcpPing(item.host, item.port, 3000)
                ui {
                    resLbl.text = if (ms >= 0) "✅ " + Tr.t("وصل است") else "❌ " + Tr.t("وصل نیست")
                }
            }
        }
    }
    val b2 = if (!v2Supported(item.proto)) {
        smallBtn("پشتیبانی نمی‌شود", false) { toast("این پروتکل در اپ پشتیبانی نمی‌شود.") }
    } else if (isActive) {
        smallBtn("قطع اتصال", false) {
            V2VpnService.requestStop(this)
            handler.postDelayed({ vpnRender?.invoke() }, 700)
        }
    } else {
        smallBtn("اتصال", true) { connectV2(item) }
    }
    val m1 = LinearLayout.LayoutParams(WRAP, WRAP)
    m1.marginEnd = dp(6)
    br.addView(b1, m1)
    br.addView(b2)
    box.addView(br, vlp(8, 0))
    return box
}

// Games and favorites always go through the tunnels. Some games are not flagged as games by the system,
// so the user can add any other app here; it is then included in WireGuard and V2Gaming tunnels.

private fun MainActivity.subWgRow(base: String): LinearLayout {
    val isActive = WgState.up && WgState.activeName == base
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = android.view.Gravity.CENTER_VERTICAL
    row.setPadding(dp(12), dp(10), dp(12), dp(10))
    row.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.addView(label((if (isActive) "$base  ✓" else base), 13.5f, white, true, false))
    val gi = if (isActive) ((wgAppsInfo[base] ?: "") + wgHealthInfo()) else ""
    if (gi.isNotEmpty()) col.addView(label("🎮 " + gi, 11.5f, muted, false, false))
    val tr = wgTestRes[base] ?: ""
    if (tr.isNotEmpty()) col.addView(label(tr, 11.5f, white, false, false), vlp(2, 2))
    row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
    tlp.marginEnd = dp(6)
    row.addView(smallBtn("🧪 تست", false) { testWg(base) }, tlp)
    row.addView(
        smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
            if (isActive) disconnectWg() else connectWg(base)
        }
    )
    return row
}

private fun MainActivity.subDnsRow(
    pr: DnsProvider,
    isActive: Boolean,
    res: String,
    onRes: (String) -> Unit
): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = android.view.Gravity.CENTER_VERTICAL
    row.setPadding(dp(12), dp(10), dp(12), dp(10))
    row.background = rounded(if (isActive) accentAlpha(35) else panel2, 12, if (isActive) accent else stroke)
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.addView(label(if (isActive) pr.name + "  ✓" else pr.name, 13.5f, white, true, false))
    if (res.isNotEmpty()) col.addView(label(res, 11.5f, white, false, false), vlp(2, 2))
    row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
    tlp.marginEnd = dp(6)
    row.addView(
        smallBtn("🧪 تست", false) {
            onRes(Tr.t("در حال تست…"))
            runBg {
                val vals = ArrayList<Int>()
                for (k in 0 until 3) {
                    val ms = dnsPingMs(pr.primary, 1200)
                    if (ms >= 0) vals.add(ms)
                }
                val avg = if (vals.isEmpty()) -1 else vals.average().toInt()
                ui {
                    onRes(if (avg >= 0) "✅ $avg ms" else "❌ " + Tr.t("جواب نمی‌دهد"))
                }
            }
        },
        tlp
    )
    row.addView(
        smallBtn(if (isActive) "قطع اتصال" else "اتصال", !isActive) {
            if (isActive) stopDns() else connectDns(pr)
            handler.postDelayed({ vpnRender?.invoke() }, 1200)
        }
    )
    return row
}
