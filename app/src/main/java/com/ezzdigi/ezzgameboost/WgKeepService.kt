package com.ezzdigi.ezzgameboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

// Keeps the app process at foreground priority while a WireGuard tunnel is up.
// The WireGuard tunnel service itself is not a foreground service, so when a heavy game starts the
// system could kill the process and the tunnel with it. V2Ray and DNS already run as foreground
// services; this gives WireGuard the same protection. It stops itself once the tunnel is down.
class WgKeepService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var downChecks = 0

    private val watcher = object : Runnable {
        override fun run() {
            if (WgState.up) downChecks = 0 else downChecks++
            if (downChecks >= 3) {
                stopSelf()
                return
            }
            h.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("name") ?: ""
        goForeground(name)
        h.removeCallbacks(watcher)
        downChecks = 0
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
        super.onDestroy()
    }
}
