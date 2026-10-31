package com.ezzdigi.ezzgameboost

import android.content.Context
import java.io.ByteArrayInputStream
import org.amnezia.awg.backend.GoBackend as AwgGoBackend
import org.amnezia.awg.backend.Tunnel as AwgTunnel
import org.amnezia.awg.config.Config as AwgConfig

// AmneziaWG support for Pro Gaming.
//
// A config that carries obfuscation keys (Jc, Jmin, Jmax, S1..S4, H1..H4, I1..I5) cannot run on the
// standard WireGuard backend: it would either fail to parse or silently lose the keys and never get a
// handshake from an AmneziaWG server. Such configs are routed to the AmneziaWG backend
// (org.amnezia.awg.*, from com.zaneschepke:amneziawg-android). Plain WireGuard configs keep using the
// standard backend exactly as before.

class AwgTun(private val tunnelName: String) : AwgTunnel {
    override fun getName(): String = tunnelName

    override fun isIpv4ResolutionPreferred(): Boolean = true

    override fun onStateChange(newState: AwgTunnel.State) {
        WgState.up = newState == AwgTunnel.State.UP
        if (!WgState.up) WgState.activeName = ""
    }
}

class AwgTestTun : AwgTunnel {
    override fun getName(): String = "ezztest"
    override fun isIpv4ResolutionPreferred(): Boolean = true
    override fun onStateChange(newState: AwgTunnel.State) {
    }
}

object AwgEngine {
    // Process-wide, like WgState: the watchdog service uses these after the activity is gone.
    @Volatile
    var backend: AwgGoBackend? = null

    @Volatile
    var tunnel: AwgTun? = null

    @Synchronized
    fun backend(ctx: Context): AwgGoBackend {
        var b = backend
        if (b == null) {
            b = create(ctx.applicationContext)
            backend = b
        }
        return b
    }

    // Different releases of the library expose GoBackend(Context) or GoBackend(Context, handler).
    private fun create(ctx: Context): AwgGoBackend {
        val cls = AwgGoBackend::class.java
        try {
            return cls.getConstructor(Context::class.java).newInstance(ctx)
        } catch (_: NoSuchMethodException) {
        }
        for (c in cls.constructors) {
            val p = c.parameterTypes
            if (p.size == 2 && p[0] == Context::class.java) {
                return c.newInstance(ctx, null) as AwgGoBackend
            }
        }
        throw IllegalStateException("AmneziaWG backend constructor not found")
    }

    // Keys the library may not know yet (newer protocol versions). I1..I5 are client-side "signature"
    // packets that the server never needs to match, so dropping them keeps the tunnel compatible.
    private val optionalClientKeys = setOf("i1", "i2", "i3", "i4", "i5")

    private fun stripOptional(text: String): String {
        val out = ArrayList<String>()
        for (ln in text.split("\n")) {
            val k = ln.substringBefore('=').trim().lowercase()
            if (ln.contains('=') && optionalClientKeys.contains(k)) continue
            out.add(ln)
        }
        return out.joinToString("\n")
    }

    private fun parseRaw(text: String): AwgConfig =
        AwgConfig.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    fun parse(text: String): AwgConfig {
        try {
            return parseRaw(text)
        } catch (e: Throwable) {
            val stripped = stripOptional(text)
            if (stripped == text) throw e
            return try {
                parseRaw(stripped)
            } catch (_: Throwable) {
                throw e
            }
        }
    }
}

// Latest handshake (epoch millis) over all peers of an AmneziaWG tunnel, 0 if none yet.
internal fun awgLatestHandshake(be: AwgGoBackend, tun: AwgTunnel): Long {
    var best = 0L
    try {
        val st = be.getStatistics(tun)
        for (k in st.peers()) {
            val ps = st.peer(k)
            if (ps != null && ps.latestHandshakeEpochMillis() > best) best = ps.latestHandshakeEpochMillis()
        }
    } catch (_: Throwable) {
    }
    return best
}

internal fun awgWaitHandshake(be: AwgGoBackend, tun: AwgTunnel, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (awgLatestHandshake(be, tun) > 0L) return true
        try {
            Thread.sleep(500)
        } catch (_: Throwable) {
        }
    }
    return false
}

// Validates a config with the engine that will actually run it. Throws when it is invalid.
internal fun wgParseCheck(text: String) {
    if (isAwgConfig(text)) {
        AwgEngine.parse(text)
    } else {
        com.wireguard.config.Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }
}

// Brings down whichever engine currently holds the tunnel. Safe to call from any thread.
internal fun wgStopCurrent() {
    try {
        val b = WgState.backend
        val t = WgState.tunnel
        if (b != null && t != null) b.setState(t, com.wireguard.android.backend.Tunnel.State.DOWN, null)
    } catch (_: Throwable) {
    }
    try {
        val b = AwgEngine.backend
        val t = AwgEngine.tunnel
        if (b != null && t != null) b.setState(t, AwgTunnel.State.DOWN, null)
    } catch (_: Throwable) {
    }
}

internal fun MainActivity.wgIsAwgBase(base: String): Boolean =
    try {
        isAwgConfig(readCfg("wg", "$base.conf"))
    } catch (_: Throwable) {
        false
    }
