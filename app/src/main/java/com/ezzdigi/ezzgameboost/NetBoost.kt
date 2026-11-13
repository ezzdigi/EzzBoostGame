package com.ezzdigi.ezzgameboost

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

// Keeps Wi-Fi out of power-save while a gaming tunnel (WireGuard, V2Ray or DNS) is up.
// Power-save is a common cause of ping spikes and bursts of lost packets on Wi-Fi.
// Several services can ask for the lock at once; it is released when the last one lets go,
// so a quick switch from one tunnel to another never drops it in between.
object NetBoost {
    private var lock: WifiManager.WifiLock? = null
    private val owners = HashSet<String>()

    @Synchronized
    fun acquire(ctx: Context, owner: String) {
        owners.add(owner)
        try {
            if (lock?.isHeld == true) return
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val l = wm.createWifiLock(mode, "EzzGameBOOST:net")
            l.setReferenceCounted(false)
            l.acquire()
            lock = l
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        try {
            if (lock?.isHeld == true) lock?.release()
        } catch (_: Throwable) {
        }
        lock = null
    }
}
