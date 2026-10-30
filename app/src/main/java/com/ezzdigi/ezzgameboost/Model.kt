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

// The app tries these in order. Edit dns.json on GitHub (repo root) or on your site
// to update the DNS list without releasing a new APK.
// True while the light theme is active (set by MainActivity.applyPalette, read by the custom views).
@Volatile
var uiLight = false

// Subscription server (PHP on your hosting, see README_FA.md in the ezzsub package).
// Replace YOUR-DOMAIN (and the folder) with where you uploaded the "ezzsub" folder.
const val SUB_BASE = "https://ezzgame.ir/ezzsub"
const val SUB_API = SUB_BASE + "/api.php"
const val SUB_BUY_URL = SUB_BASE + "/"

// Public text lists (one link per line) that are pulled into V2Gaming automatically.
// A GitHub "blob" link is converted to the raw file. More can be added in v2ray.json
// with a "sources": ["https://..."] array.
val V2_SOURCES = listOf(
    "https://github.com/iboxz/free-v2ray-collector/blob/main/main/vless.txt",
    "https://github.com/iboxz/free-v2ray-collector/blob/main/main/vmess.txt"
)
// Extracted public servers are all shown as "<V2_AUTO_NAME> <number>".
const val V2_AUTO_NAME = "Gaming"
// How often the V2Gaming online list (and the public sources) is refreshed.
const val V2_REFRESH_HOURS = 2L
const val V2_SOURCE_MAX = 20
// Candidates per source that are tested (TCP connect) before the first V2_SOURCE_MAX live ones are kept.
const val V2_SOURCE_CANDIDATES = 60

// Online lists (dns.json, v2ray.json, wireguard.json) live in the repo root.
// Several mirrors are tried because raw.githubusercontent.com is blocked on some networks.
fun remoteUrls(repo: String, file: String): List<String> = listOf(
    "https://raw.githubusercontent.com/$repo/main/$file",
    "https://cdn.jsdelivr.net/gh/$repo@main/$file",
    "https://raw.githubusercontent.com/$repo/master/$file",
    "https://cdn.jsdelivr.net/gh/$repo@master/$file",
    "https://gcore.jsdelivr.net/gh/$repo@main/$file"
)
const val RELEASES_API = "https://api.github.com/repos/ezzdigi/EzzBoostGame/releases/latest"
const val RELEASES_PAGE = "https://github.com/ezzdigi/EzzBoostGame/releases/latest"

class AppItem(val label: String, val pkg: String, val isGame: Boolean)
class DnsProvider(
    val name: String,
    val primary: String,
    val secondary: String,
    val tertiary: String = "",
    val quaternary: String = ""
)
class ServerItem(val name: String, val host: String, val port: Int)
class HistItem(val ts: Long, val key: String, val avg: Int, val loss: Int)

object DnsList {
    val builtin = listOf(
        DnsProvider("Radar Game • رادار گیم", "10.202.10.10", "10.202.10.11"),
        DnsProvider("Electro • الکترو", "78.157.42.100", "78.157.42.101"),
        DnsProvider("Shecan • شکن", "178.22.122.100", "185.51.200.2"),
        DnsProvider("Begzar • بگذر", "185.55.226.26", "185.55.225.25", "185.55.224.24"),
        DnsProvider("Shelter • شلتر", "94.156.65.130", "91.92.249.227"),
        DnsProvider("Vanilla", "194.146.68.68", "194.146.68.40"),
        DnsProvider("Premium Turbo", "78.157.58.162", "78.157.58.163"),
        DnsProvider("Bertina", "193.186.32.32", "193.186.32.32"),
        DnsProvider("Hostiran", "172.29.0.100", "172.29.2.100"),
        DnsProvider("Server.ir", "65.109.209.224", "65.109.177.244"),
        DnsProvider("Beshkan App", "181.41.194.177", "181.41.194.186"),
        DnsProvider("Yandex DNS", "77.88.8.8", "77.88.8.1"),
        DnsProvider("Ezz01", "87.248.145.40", "87.107.170.69"),
        DnsProvider("Ezz02", "185.97.18.126", "87.248.145.40"),
        DnsProvider("Ezz03", "176.10.118.132", "176.10.118.133"),
        DnsProvider("Ezz04", "94.187.170.2", "94.187.170.3"),
        DnsProvider("Ezz05", "195.235.194.7", "195.235.194.8"),
        DnsProvider("Ezz06", "45.81.37.0", "45.81.37.1"),
        DnsProvider("Cloudflare", "1.1.1.1", "1.0.0.1"),
        DnsProvider("Google", "8.8.8.8", "8.8.4.4"),
        DnsProvider("Quad9", "9.9.9.9", "149.112.112.112"),
        DnsProvider("AdGuard", "94.140.14.14", "94.140.15.15")
    )

    // User-defined DNS (IPv4 / IPv6), stored as tab-separated lines:
    // name, ipv4-1, ipv4-2, ipv6-1, ipv6-2
    fun custom(ctx: Context): List<DnsProvider> {
        val raw = ctx.getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
            .getString("customDns", "") ?: ""
        val out = ArrayList<DnsProvider>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val p = line.split("\t")
            if (p.size < 5) continue
            val servers = ArrayList<String>()
            for (i in 1..4) if (p[i].isNotBlank()) servers.add(p[i].trim())
            if (servers.isEmpty()) continue
            out.add(
                DnsProvider(
                    "★ " + p[0],
                    servers[0],
                    servers.getOrElse(1) { "" },
                    servers.getOrElse(2) { "" },
                    servers.getOrElse(3) { "" }
                )
            )
        }
        return out
    }

    // Providers downloaded from the online list (tab-separated lines:
    // name, ip1[, ip2, ip3, ip4]).
    fun remote(ctx: Context): List<DnsProvider> {
        val raw = ctx.getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
            .getString("remoteDns", "") ?: ""
        val out = ArrayList<DnsProvider>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val ips = ArrayList<String>()
            for (i in 1 until p.size) if (p[i].isNotBlank()) ips.add(p[i].trim())
            if (ips.isEmpty()) continue
            out.add(
                DnsProvider(
                    p[0].trim(),
                    ips[0],
                    ips.getOrElse(1) { "" },
                    ips.getOrElse(2) { "" },
                    ips.getOrElse(3) { "" }
                )
            )
        }
        return out
    }

    fun merged(ctx: Context): List<DnsProvider> {
        val out = ArrayList<DnsProvider>(builtin)
        for (r in remote(ctx)) {
            val idx = out.indexOfFirst { it.name == r.name }
            if (idx >= 0) out[idx] = r else out.add(r)
        }
        return out
    }

    fun all(ctx: Context): List<DnsProvider> = merged(ctx) + custom(ctx)
}

class WgTun(private val tunnelName: String) : Tunnel {
    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        WgState.up = newState == Tunnel.State.UP
        if (!WgState.up) WgState.activeName = ""
    }
}

object WgState {
    @Volatile
    var up = false

    @Volatile
    var activeName = ""
}
