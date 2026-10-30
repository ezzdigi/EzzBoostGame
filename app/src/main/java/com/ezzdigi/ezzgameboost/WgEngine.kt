package com.ezzdigi.ezzgameboost

import android.content.Context
import java.io.ByteArrayInputStream

// Runs a WireGuard config exactly as the user gave it.
//  - plain WireGuard configs  -> standard wireguard-android backend (com.wireguard.*)
//  - AmneziaWG configs (Jc, Jmin, S1, H1, I1 ... keys) -> AmneziaWG backend (org.amnezia.awg.*)
// Both tunnel libraries live side by side; only one tunnel is up at a time.
object WgEngine {
    @Volatile
    private var std: com.wireguard.android.backend.GoBackend? = null

    @Volatile
    private var awg: org.amnezia.awg.backend.GoBackend? = null

    @Synchronized
    fun std(ctx: Context): com.wireguard.android.backend.GoBackend {
        var b = std
        if (b == null) {
            b = com.wireguard.android.backend.GoBackend(ctx.applicationContext)
            std = b
        }
        return b
    }

    @Synchronized
    fun awg(ctx: Context): org.amnezia.awg.backend.GoBackend {
        var b = awg
        if (b == null) {
            b = org.amnezia.awg.backend.GoBackend(ctx.applicationContext)
            awg = b
        }
        return b
    }

    // Throws when the text is not a config its backend can parse.
    fun validate(text: String) {
        val ins = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        if (isAwgConfig(text)) {
            org.amnezia.awg.config.Config.parse(ins)
        } else {
            com.wireguard.config.Config.parse(ins)
        }
    }
}

// One tunnel (standard or AmneziaWG). track = false for the short self-test tunnel, which must not
// touch the app-wide connection state.
class WgSession(ctx: Context, private val tunnelName: String, val awg: Boolean, private val track: Boolean = true) {
    private val app: Context = ctx.applicationContext

    private fun changed(up: Boolean) {
        if (!track) return
        WgState.up = up
        if (!up) WgState.activeName = ""
    }

    private val stdTun = object : com.wireguard.android.backend.Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: com.wireguard.android.backend.Tunnel.State) {
            changed(newState == com.wireguard.android.backend.Tunnel.State.UP)
        }
    }

    private val awgTun = object : org.amnezia.awg.backend.Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: org.amnezia.awg.backend.Tunnel.State) {
            changed(newState == org.amnezia.awg.backend.Tunnel.State.UP)
        }
    }

    fun up(text: String) {
        val ins = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        if (awg) {
            val cfg = org.amnezia.awg.config.Config.parse(ins)
            WgEngine.awg(app).setState(awgTun, org.amnezia.awg.backend.Tunnel.State.UP, cfg)
        } else {
            val cfg = com.wireguard.config.Config.parse(ins)
            WgEngine.std(app).setState(stdTun, com.wireguard.android.backend.Tunnel.State.UP, cfg)
        }
    }

    fun down() {
        if (awg) {
            WgEngine.awg(app).setState(awgTun, org.amnezia.awg.backend.Tunnel.State.DOWN, null)
        } else {
            WgEngine.std(app).setState(stdTun, com.wireguard.android.backend.Tunnel.State.DOWN, null)
        }
    }

    // Epoch millis of the newest handshake with any peer, 0 when there has been none.
    fun latestHandshake(): Long {
        var best = 0L
        try {
            if (awg) {
                val st = WgEngine.awg(app).getStatistics(awgTun)
                for (k in st.peers()) {
                    val ps = st.peer(k)
                    if (ps != null && ps.latestHandshakeEpochMillis() > best) best = ps.latestHandshakeEpochMillis()
                }
            } else {
                val st = WgEngine.std(app).getStatistics(stdTun)
                for (k in st.peers()) {
                    val ps = st.peer(k)
                    if (ps != null && ps.latestHandshakeEpochMillis() > best) best = ps.latestHandshakeEpochMillis()
                }
            }
        } catch (_: Throwable) {
        }
        return best
    }
}
