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
            b = newAwgBackend(ctx.applicationContext)
            awg = b
        }
        return b
    }

    // The AmneziaWG GoBackend constructor takes an extra callback argument whose type differs between
    // library versions. It is built by reflection and the callback is a do-nothing stub, because this
    // app never uses pre/post-up scripts.
    private fun newAwgBackend(ctx: Context): org.amnezia.awg.backend.GoBackend {
        val cls = org.amnezia.awg.backend.GoBackend::class.java
        for (c in cls.constructors) {
            val p = c.parameterTypes
            if (p.size == 1 && p[0].isAssignableFrom(ctx.javaClass)) {
                return c.newInstance(ctx) as org.amnezia.awg.backend.GoBackend
            }
        }
        for (c in cls.constructors) {
            val p = c.parameterTypes
            if (p.isNotEmpty() && p[0].isAssignableFrom(ctx.javaClass)) {
                val args = arrayOfNulls<Any>(p.size)
                args[0] = ctx
                for (i in 1 until p.size) args[i] = stub(p[i])
                return c.newInstance(*args) as org.amnezia.awg.backend.GoBackend
            }
        }
        throw IllegalStateException("AmneziaWG GoBackend constructor not found")
    }

    private fun stub(t: Class<*>): Any? {
        if (!t.isInterface) return null
        return java.lang.reflect.Proxy.newProxyInstance(t.classLoader, arrayOf(t)) { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "stub"
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Character.TYPE -> '\u0000'
                    else -> null
                }
            }
        }
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
        override fun isIpv4ResolutionPreferred(): Boolean = false
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
