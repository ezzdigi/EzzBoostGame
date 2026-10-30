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

// ---------------------------------------------------------------------------
// DNS VPN service: local VPN that only captures DNS queries and forwards them
// to the chosen DNS server (primary, then secondary as fallback).
// ---------------------------------------------------------------------------

class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ezzdigi.ezzgameboost.DNS_START"
        const val ACTION_STOP = "com.ezzdigi.ezzgameboost.DNS_STOP"

        @Volatile
        var instance: DnsVpnService? = null

        fun requestStop(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.shutdown()
            } else {
                try {
                    val i = Intent(ctx, DnsVpnService::class.java)
                    i.action = ACTION_STOP
                    ctx.startService(i)
                } catch (_: Exception) {
                }
            }
            running = false
            activeName = ""
        }
        private const val FAKE_DNS = "10.111.222.2"
        private const val TUN_ADDR = "10.111.222.1"

        @Volatile
        var running = false

        @Volatile
        var activeName = ""
        val queryCount = AtomicLong(0L)
        val failCount = AtomicLong(0L)
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var gen = 0

    @Volatile
    private var primaryDownUntil = 0L
    private var primary = ""
    private var secondary = ""
    private var tertiary = ""
    private var quaternary = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        Tr.load(this)
    }

    fun shutdown() {
        cleanup()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            cleanup()
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        val p = intent.getStringExtra("primary") ?: ""
        val s = intent.getStringExtra("secondary") ?: ""
        val t3 = intent.getStringExtra("tertiary") ?: ""
        val q4 = intent.getStringExtra("quaternary") ?: ""
        val n = intent.getStringExtra("name") ?: p
        if (p.isEmpty()) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startTunnel(p, s, t3, q4, n)
        return Service.START_NOT_STICKY
    }

    private fun startTunnel(p: String, s: String, t3: String, q4: String, name: String) {
        cleanup()
        primary = p
        secondary = s
        tertiary = t3
        quaternary = q4
        primaryDownUntil = 0L
        try {
            val b = Builder()
            b.setSession("EzzGameBOOST DNS")
            b.addAddress(TUN_ADDR, 24)
            b.addRoute("10.111.222.0", 24)
            b.addDnsServer(FAKE_DNS)
            b.setMtu(1500)
            b.setBlocking(true)
            val pfd = b.establish()
            if (pfd == null) {
                running = false
                stopSelf()
                return
            }
            tun = pfd
            stopping = false
            running = true
            activeName = name
            queryCount.set(0L)
            failCount.set(0L)
            pool = java.util.concurrent.Executors.newFixedThreadPool(12)
            goForeground(name)
            val myGen = gen
            Thread { loop(pfd, myGen) }.start()
        } catch (_: Throwable) {
            cleanup()
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun goForeground(name: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("ezz_dns", "DNS", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(this, DnsVpnService::class.java)
            stopIntent.action = ACTION_STOP
            val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "ezz_dns")
                .setContentTitle("EzzGameBOOST • DNS")
                .setContentText(Tr.t("متصل:") + " " + name)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, Tr.t("قطع اتصال"), stopPi)
                .setOngoing(true)
                .build()
            startForeground(1, n)
        } catch (_: Throwable) {
        }
    }

    private fun loop(pfd: ParcelFileDescriptor, myGen: Int) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32768)
        val fds = arrayOf(StructPollfd())
        fds[0].fd = pfd.fileDescriptor
        fds[0].events = OsConstants.POLLIN.toShort()
        try {
            while (!stopping && gen == myGen) {
                fds[0].revents = 0
                try {
                    val r = Os.poll(fds, 500)
                    if (r <= 0) continue
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    break
                }
                if (stopping || gen != myGen) break
                val n = input.read(buf)
                if (n < 0) break
                if (n < 28) continue
                handle(buf, n, output)
            }
        } catch (_: Exception) {
        }
        if (!stopping && gen == myGen) {
            running = false
            activeName = ""
            stopSelf()
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun handle(buf: ByteArray, n: Int, output: FileOutputStream) {
        val ver = (buf[0].toInt() shr 4) and 0xF
        if (ver != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17 || n < ihl + 8) return
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return
        val udpLen = u16(buf, ihl + 4)
        val plen = minOf(udpLen - 8, n - ihl - 8)
        if (plen <= 0) return
        val payload = ByteArray(plen)
        System.arraycopy(buf, ihl + 8, payload, 0, plen)
        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        System.arraycopy(buf, 12, srcIp, 0, 4)
        System.arraycopy(buf, 16, dstIp, 0, 4)
        val pl = pool ?: return
        try {
            pl.execute {
                try {
                    val resp = forward(payload)
                    if (resp != null) {
                        queryCount.incrementAndGet()
                        val pkt = buildPacket(dstIp, srcIp, 53, srcPort, resp)
                        synchronized(output) { output.write(pkt) }
                    } else {
                        failCount.incrementAndGet()
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun forward(payload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val base = ArrayList<String>()
        base.add(primary)
        if (secondary.isNotEmpty()) base.add(secondary)
        if (tertiary.isNotEmpty()) base.add(tertiary)
        if (quaternary.isNotEmpty()) base.add(quaternary)
        val uniq = base.distinct()
        val order: List<String> = if (uniq.size > 1 && now < primaryDownUntil) uniq.drop(1) + uniq[0] else uniq
        for ((idx, server) in order.withIndex()) {
            val r = query(server, payload, if (idx == 0) 1800 else 2500)
            if (r != null) return r
            if (server == primary) primaryDownUntil = System.currentTimeMillis() + 30000L
        }
        return null
    }

    private fun query(server: String, payload: ByteArray, timeoutMs: Int): ByteArray? {
        val sock = try {
            DatagramSocket()
        } catch (_: Exception) {
            return null
        }
        try {
            protect(sock)
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(4096)
            val rp = DatagramPacket(buf, buf.size)
            sock.receive(rp)
            if (rp.length < 12 || buf[0] != payload[0] || buf[1] != payload[1]) return null
            return buf.copyOf(rp.length)
        } catch (_: Exception) {
            return null
        } finally {
            sock.close()
        }
    }

    private fun buildPacket(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, data: ByteArray): ByteArray {
        val total = 28 + data.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = (total and 0xFF).toByte()
        p[6] = 0x40
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        p[10] = (ck shr 8).toByte()
        p[11] = (ck and 0xFF).toByte()
        p[20] = (sport shr 8).toByte()
        p[21] = (sport and 0xFF).toByte()
        p[22] = (dport shr 8).toByte()
        p[23] = (dport and 0xFF).toByte()
        val ul = 8 + data.size
        p[24] = (ul shr 8).toByte()
        p[25] = (ul and 0xFF).toByte()
        System.arraycopy(data, 0, p, 28, data.size)
        return p
    }

    private fun cleanup() {
        stopping = true
        gen++
        running = false
        activeName = ""
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        try {
            pool?.shutdownNow()
        } catch (_: Exception) {
        }
        pool = null
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    override fun onRevoke() {
        cleanup()
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        instance = null
        super.onDestroy()
    }
}


// ---------------------------------------------------------------------------
// Home-screen widget
// ---------------------------------------------------------------------------

class BoostWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Tr.load(context)
        val v = build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, v)
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_boost)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val freeMb = mi.availMem / (1024L * 1024L)
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val dns = if (DnsVpnService.running) DnsVpnService.activeName else Tr.t("پیش‌فرض سیستم")
            v.setTextViewText(R.id.w_title, "EzzGameBOOST")
            v.setTextViewText(R.id.w_ram, "RAM " + Tr.t("آزاد") + ": " + freeMb + " MB")
            v.setTextViewText(R.id.w_bat, Tr.t("باتری") + ": " + pct + "%")
            v.setTextViewText(R.id.w_dns, "DNS: " + dns)
            v.setTextViewText(R.id.w_site, "")
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, pi)
            return v
        }
    }
}

// ---------------------------------------------------------------------------
// Quick Settings tile: toggle the DNS VPN from the notification shade
// ---------------------------------------------------------------------------

class DnsTileService : TileService() {
    private val h = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        Tr.load(this)
        if (DnsVpnService.running) {
            DnsVpnService.requestStop(this)
            update()
            h.postDelayed({ update() }, 600)
            return
        }
        val needConsent = try {
            VpnService.prepare(this) != null
        } catch (_: Exception) {
            true
        }
        if (needConsent) {
            openApp()
            return
        }
        val prefs = getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
        val list = DnsList.all(this)
        val pr = list[prefs.getInt("dnsSel", 0).coerceIn(0, list.size - 1)]
        val i = Intent(this, DnsVpnService::class.java)
        i.action = DnsVpnService.ACTION_START
        i.putExtra("primary", pr.primary)
        i.putExtra("secondary", pr.secondary)
        i.putExtra("tertiary", pr.tertiary)
        i.putExtra("quaternary", pr.quaternary)
        i.putExtra("name", pr.name)
        try {
            startService(i)
        } catch (_: Exception) {
            openApp()
            return
        }
        h.postDelayed({ update() }, 900)
        h.postDelayed({ update() }, 2500)
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (DnsVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "EzzGameBOOST DNS"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(i)
            }
        } catch (_: Throwable) {
        }
    }
}
