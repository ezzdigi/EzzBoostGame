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
import android.system.StructStat
import android.util.Base64
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ----- Xray core (loaded by reflection from libv2ray.aar) + VPN service ---------

object XrayCore {
    private const val LIB = "libv2ray.Libv2ray"
    private const val CB = "libv2ray.CoreCallbackHandler"

    @Volatile
    private var envReady = false

    @Volatile
    private var controller: Any? = null

    fun available(): Boolean = try {
        Class.forName(LIB)
        true
    } catch (_: Throwable) {
        false
    }

    fun version(): String = try {
        val c = Class.forName(LIB)
        (c.getMethod("checkVersionX").invoke(null) as? String) ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun xudpKey(ctx: Context): String {
        val p = ctx.getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
        var id = p.getString("xudpId", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString("xudpId", id).apply()
        }
        val bytes = id.toByteArray().copyOf(32)
        return Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun explain(e: Throwable): String {
        var c: Throwable = e
        if (c is InvocationTargetException && c.targetException != null) c = c.targetException
        val m = c.message
        if (m != null && m.contains("without TLS or other encryption is prohibited")) {
            return "این هسته‌ی Xray کانفیگ VLESS/Trojan بدون TLS یا Reality را قبول نمی‌کند. " +
                "از ادمین بخواه Reality/TLS فعال کند، یا اپ را با هسته‌ی v26.6.27 بیلد کن."
        }
        return if (m.isNullOrBlank()) c.javaClass.simpleName else m
    }

    fun start(ctx: Context, config: String, tunFd: Int) {
        val lib = Class.forName(LIB)
        if (!envReady) {
            val m = lib.getMethod("initCoreEnv", String::class.java, String::class.java)
            m.invoke(null, ctx.filesDir.absolutePath, xudpKey(ctx))
            envReady = true
        }
        val cb = Class.forName(CB)
        val handler = Proxy.newProxyInstance(
            cb.classLoader, arrayOf<Class<*>>(cb),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "toString" -> "EzzXrayCallback"
                    "hashCode" -> 7
                    "equals" -> false
                    else -> 0L
                }
            }
        )
        val ctrl = lib.getMethod("newCoreController", cb).invoke(null, handler)
            ?: throw IllegalStateException("کنترلر هسته ساخته نشد")
        controller = ctrl
        val sl = try {
            ctrl.javaClass.getMethod("startLoop", String::class.java, Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            throw IllegalStateException("این نسخه‌ی هسته حالت TUN را پشتیبانی نمی‌کند")
        }
        sl.invoke(ctrl, config, tunFd)
    }

    fun stop() {
        val c = controller ?: return
        controller = null
        try {
            c.javaClass.getMethod("stopLoop").invoke(c)
        } catch (_: Throwable) {
        }
    }
}

class V2VpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ezzdigi.ezzgameboost.V2_START"
        const val ACTION_STOP = "com.ezzdigi.ezzgameboost.V2_STOP"

        @Volatile
        var running = false

        @Volatile
        var busy = false

        @Volatile
        var activeName = ""

        @Volatile
        var activeId = ""

        // Result of the end-to-end check through the local SOCKS inbound after connecting.
        @Volatile
        var probe = ""

        @Volatile
        var lastProbePort = 0

        fun pickProbePort(): Int {
            lastProbePort = 20000 + java.util.Random().nextInt(30000)
            return lastProbePort
        }

        @Volatile
        var lastError = ""

        @Volatile
        var instance: V2VpnService? = null

        fun requestStop(ctx: Context) {
            val s = instance
            if (s != null) {
                s.shutdown()
                return
            }
            running = false
            busy = false
            activeName = ""
            activeId = ""
            try {
                val i = Intent(ctx, V2VpnService::class.java)
                i.action = ACTION_STOP
                ctx.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var goFd = -1
    private var refStat: StructStat? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            shutdown()
            return Service.START_NOT_STICKY
        }
        if (intent.action != ACTION_START) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        val name = intent.getStringExtra("name") ?: "V2Ray"
        val id = intent.getStringExtra("id") ?: ""
        val config = intent.getStringExtra("config") ?: ""
        val pkgs = intent.getStringArrayExtra("pkgs") ?: emptyArray()
        if (config.isEmpty()) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        instance = this
        busy = true
        lastError = ""
        probe = ""
        goForeground(name)
        Thread { startCore(name, id, config, pkgs) }.start()
        return Service.START_NOT_STICKY
    }

    private fun startCore(name: String, id: String, config: String, pkgs: Array<String>) {
        try {
            releaseTunnel()
            if (!XrayCore.available()) throw IllegalStateException("هسته‌ی Xray در این نسخه نیست.")
            // Games only: never tunnel the whole phone.
            if (pkgs.isEmpty()) throw IllegalStateException("بازی‌ای پیدا نشد؛ یک بازی را در تب بازی‌ها علاقه‌مند کن.")
            val b = Builder()
            b.setSession("EzzGameBOOST V2Ray")
            b.addAddress("172.19.0.1", 30)
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("1.1.1.1")
            b.addDnsServer("8.8.8.8")
            b.setMtu(1500)
            var allowed = 0
            for (pk in pkgs) {
                try {
                    b.addAllowedApplication(pk)
                    allowed++
                } catch (_: Exception) {
                }
            }
            if (allowed == 0) throw IllegalStateException("بازی‌ای پیدا نشد؛ یک بازی را در تب بازی‌ها علاقه‌مند کن.")
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            val pfd = b.establish() ?: throw IllegalStateException("VPN ساخته نشد؛ اجازه‌ی VPN را بررسی کن.")
            tun = pfd
            val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            val fd = dup.detachFd()
            goFd = fd
            refStat = try {
                Os.fstat(pfd.fileDescriptor)
            } catch (_: Exception) {
                null
            }
            XrayCore.start(this, config, fd)
            activeName = name
            activeId = id
            running = true
            probe = "⏳ در حال تست سرور…"
            val pp = lastProbePort
            Thread { probeServer(pp, id) }.start()
        } catch (e: Throwable) {
            lastError = XrayCore.explain(e)
            running = false
            activeName = ""
            activeId = ""
            releaseTunnel()
            try {
                stopSelf()
            } catch (_: Exception) {
            }
        } finally {
            busy = false
        }
    }

    // Sends one small HTTPS request through the proxy to tell "connected" from "really works".
    private fun probeServer(port: Int, id: String) {
        var result = ""
        try {
            Thread.sleep(700)
            if (port <= 0) return
            val urls = arrayOf("https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204")
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            for (u in urls) {
                if (!running || activeId != id) return
                val t0 = System.currentTimeMillis()
                try {
                    val c = java.net.URL(u).openConnection(proxy) as java.net.HttpURLConnection
                    c.connectTimeout = 7000
                    c.readTimeout = 7000
                    c.instanceFollowRedirects = false
                    val code = c.responseCode
                    c.disconnect()
                    if (code in 200..399) {
                        result = "✅ سرور جواب می‌دهد • " + (System.currentTimeMillis() - t0) + " ms"
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (result.isEmpty()) {
                result = "⚠️ وصل شد ولی سرور جواب نمی‌دهد (خاموش یا فیلتر است). سرور دیگری را امتحان کن."
            }
        } catch (_: Throwable) {
            return
        }
        if (running && activeId == id) probe = result
    }

    // Closes the copy of the TUN fd that was handed to Go, only if it is still open
    // and still refers to the TUN device (so we never close an unrelated, reused fd).
    private fun closeLeakedFd(n: Int) {
        try {
            val p = ParcelFileDescriptor.adoptFd(n)
            val ref = refStat
            try {
                val st = Os.fstat(p.fileDescriptor)
                if (ref != null && st.st_rdev == ref.st_rdev && st.st_ino == ref.st_ino && st.st_dev == ref.st_dev) {
                    p.close()
                    return
                }
            } catch (_: ErrnoException) {
            }
            p.detachFd()
        } catch (_: Exception) {
        }
    }

    private fun releaseTunnel() {
        try {
            XrayCore.stop()
        } catch (_: Throwable) {
        }
        val g = goFd
        goFd = -1
        if (g >= 0) closeLeakedFd(g)
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    fun shutdown() {
        running = false
        activeName = ""
        activeId = ""
        busy = false
        releaseTunnel()
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun goForeground(name: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("ezz_v2", "V2Ray", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val stopI = Intent(this, V2VpnService::class.java)
            stopI.action = ACTION_STOP
            val stopPi = PendingIntent.getService(this, 2, stopI, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "ezz_v2")
                .setContentTitle("EzzGameBOOST V2Ray")
                .setContentText("متصل: $name")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPi)
                .setOngoing(true)
                .build()
            startForeground(2, n)
        } catch (_: Throwable) {
        }
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        running = false
        activeName = ""
        activeId = ""
        releaseTunnel()
        instance = null
        super.onDestroy()
    }
}
