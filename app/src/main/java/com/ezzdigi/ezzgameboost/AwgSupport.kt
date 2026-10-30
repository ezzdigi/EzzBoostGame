package com.ezzdigi.ezzgameboost

import android.net.VpnService
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

private class EzzAwgTunnel(private val name: String) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        AwgState.up = newState == Tunnel.State.UP
        if (!AwgState.up) AwgState.activeName = ""
    }
}

internal object AwgState {
    @Volatile var backend: GoBackend? = null
    @Volatile var tunnel: EzzAwgTunnel? = null
    @Volatile var up = false
    @Volatile var wanted = false
    @Volatile var activeName = ""
    @Volatile var cfgText = ""
    @Volatile var lastError = ""
}

internal fun isAmneziaWgConfig(text: String): Boolean {
    val keys = setOf(
        "jc","jmin","jmax","s1","s2","s3","s4",
        "h1","h2","h3","h4","i1","i2","i3","i4","i5",
        "headerprotectionkey","contentpaddingaddition",
        "randomtrailers","disablecookies","maxhandshakeattempts",
        "rekeyaftertime","rekeytimeout","rejectaftertime","keepalivetimeout"
    )
    return text.lineSequence().any { line ->
        val t = line.trim()
        t.contains("=") && keys.contains(t.substringBefore("=").trim().lowercase())
    }
}

private fun awgScoped(text: String, packageName: String): String {
    val out = StringBuilder()
    var inserted = false
    for (line in text.replace("\r", "").split("\n")) {
        val t = line.trim()
        if (t.equals("[Interface]", true)) {
            out.append(line).append('\n')
            if (!inserted) {
                out.append("IncludedApplications = ").append(packageName).append('\n')
                inserted = true
            }
        } else if (!t.startsWith("IncludedApplications", true) &&
            !t.startsWith("ExcludedApplications", true)) {
            out.append(line).append('\n')
        }
    }
    return out.toString()
}

internal fun MainActivity.connectAmneziaWg(base: String, raw: String) {
    runBg {
        var error = ""
        try {
            val prepared = awgScoped(raw, packageName)
            val cfg = Config.parse(ByteArrayInputStream(prepared.toByteArray(Charsets.UTF_8)))
            val backend = AwgState.backend ?: GoBackend(applicationContext).also { AwgState.backend = it }

            AwgState.tunnel?.let {
                try { backend.setState(it, Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            }

            val tunnel = EzzAwgTunnel(base)
            backend.setState(tunnel, Tunnel.State.UP, cfg)

            val deadline = System.currentTimeMillis() + 10000L
            while (System.currentTimeMillis() < deadline) {
                if (backend.getLastHandshake(tunnel) > 0L) break
                Thread.sleep(400)
            }
            if (backend.getLastHandshake(tunnel) <= 0L) {
                try { backend.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Throwable) {}
                throw IllegalStateException("هندشیک دریافت نشد")
            }

            AwgState.tunnel = tunnel
            AwgState.up = true
            AwgState.wanted = true
            AwgState.activeName = base
            AwgState.cfgText = prepared
            AwgState.lastError = ""
        } catch (e: Throwable) {
            AwgState.up = false
            AwgState.wanted = false
            AwgState.lastError = e.message ?: e.javaClass.simpleName
            error = AwgState.lastError
        }

        ui {
            wgBusy = false
            if (error.isEmpty()) toast("🟢 AmneziaWG وصل شد: $base")
            else toast("❌ AmneziaWG: " + error.take(140))
            vpnRender?.invoke()
            homeRefresh?.invoke()
        }
    }
}

internal fun MainActivity.disconnectAmneziaWg() {
    runBg {
        try {
            val backend = AwgState.backend
            val tunnel = AwgState.tunnel
            if (backend != null && tunnel != null) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
        } catch (_: Throwable) {}
        AwgState.tunnel = null
        AwgState.up = false
        AwgState.wanted = false
        AwgState.activeName = ""
        ui {
            vpnRender?.invoke()
            homeRefresh?.invoke()
        }
    }
}
