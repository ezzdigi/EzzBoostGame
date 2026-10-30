package com.ezzdigi.ezzgameboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

// Keeps the app process at foreground priority while a WireGuard tunnel is up, and watches the
// tunnel. The WireGuard tunnel service itself is not a foreground service, so when a heavy game
// starts the system could kill the process and the tunnel with it. V2Ray and DNS already run as
// foreground services; this gives WireGuard the same protection.
//
// The watchdog also brings the tunnel back when it goes down on its own (system tore it down) or the
// server stops answering (no fresh handshake), using the config of the last successful connect. It
// works from process-wide state, so it keeps working after the activity has been destroyed. It never
// fights the user: a manual disconnect, or DNS/V2Ray taking the VPN slot, turns it off.
class WgKeepService : Service() {
    private val ht = HandlerThread("wg-watch")
    private lateinit var h: Handler
    private var downChecks = 0
    private var downTicks = 0

    private val watcher = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: Throwable) {
            }
            if (!WgState.wanted && !WgState.up) downChecks++ else downChecks = 0
            if (downChecks >= 3) {
                stopSelf()
                return
            }
            h.postDelayed(this, 4000)
        }
    }

    private fun tick() {
        if (!WgState.wanted || WgState.reconnecting) return
        // Android allows only one active VPN at a time. Normal app flows prevent
        // starting EzzGaming while Pro Gaming is active; if another VPN takes the slot,
        // keep the user's Pro Gaming intent so the watchdog can recover after it is stopped.
        if (DnsVpnService.running || V2VpnService.running) return
        val be = WgState.backend ?: return
        val tun = WgState.tunnel ?: return
        val now = System.currentTimeMillis()
        var reason = ""
        if (!WgState.up) {
            downTicks++
            if (downTicks >= 2) reason = "تونل از سمت سیستم قطع شد"
        } else {
            downTicks = 0
            val hs = wgLatestHandshake(be, tun)
            if (hs > 0L) {
                WgState.attempts = 0
                // with PersistentKeepalive the handshake is renewed about every 2 minutes while the server answers
                if (now - hs > 210000L) reason = "سرور جواب نمی‌داد (هندشیک قدیمی)"
            } else if (now - WgState.upSince > 25000L) {
                reason = "سرور جواب نمی‌داد (بدون هندشیک)"
            }
        }
        if (reason.isEmpty()) return
        if (WgState.attempts >= 4) {
            WgState.wanted = false
            WgState.lastDrop = reason + " • تلاش مجدد متوقف شد"
            return
        }
        reconnect(be, tun, reason)
    }

    private fun reconnect(be: GoBackend, tun: WgTun, reason: String) {
        WgState.reconnecting = true
        WgState.attempts++
        try {
            val cfg = Config.parse(ByteArrayInputStream(WgState.cfgText.toByteArray(Charsets.UTF_8)))
            if (!WgState.wanted) return
            try {
                be.setState(tun, Tunnel.State.DOWN, null)
            } catch (_: Throwable) {
            }
            be.setState(tun, Tunnel.State.UP, cfg)
            WgState.activeName = WgState.baseName
            WgState.up = true
            WgState.upSince = System.currentTimeMillis()
            WgState.reconnects++
            WgState.lastDrop = reason
        } catch (e: Throwable) {
            WgState.lastDrop = reason + " • " + (e.message ?: e.javaClass.simpleName).take(60)
        } finally {
            WgState.reconnecting = false
            downTicks = 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        ht.start()
        h = Handler(ht.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("name") ?: ""
        goForeground(name)
        h.removeCallbacks(watcher)
        downChecks = 0
        downTicks = 0
        h.postDelayed(watcher, 6000)
        return START_NOT_STICKY
    }

    private fun goForeground(name: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("ezz_wg", "WireGuard", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "ezz_wg")
                .setContentTitle("EzzGameBOOST WireGuard")
                .setContentText("متصل: $name")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(3, n)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(watcher)
        ht.quitSafely()
        super.onDestroy()
    }
}
