package com.ezzdigi.ezzgameboost

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Pro server test: packet loss, download and upload speed. Every function here runs while the
// test tunnel (scoped to this app only) is up, so the traffic really goes through the server.
// Blocking: call from a background thread.

private const val SPEED_UA = "EzzGameBOOST"

// Packet loss: small UDP DNS probes sent through the tunnel; an unanswered probe counts as lost.
// Returns Pair(loss percent, average ping in ms or -1).
internal fun MainActivity.wgMeasureLoss(count: Int = 20): Pair<Int, Int> {
    val hosts = listOf("1.1.1.1", "8.8.8.8")
    var ok = 0
    var sum = 0
    for (i in 0 until count) {
        val ms = dnsPingMs(hosts[i % 2], 700)
        if (ms >= 0) {
            ok++
            sum += ms
        }
        try {
            Thread.sleep(60)
        } catch (_: Throwable) {
        }
    }
    val loss = (count - ok) * 100 / count
    return Pair(loss, if (ok > 0) sum / ok else -1)
}

// Download speed in Mbit/s (-1 when it could not be measured). Reads for at most ~7 seconds.
internal fun wgMeasureDownMbps(): Double {
    val urls = listOf(
        "https://speed.cloudflare.com/__down?bytes=12000000",
        "https://cachefly.cachefly.net/10mb.test"
    )
    for (u in urls) {
        var c: HttpURLConnection? = null
        try {
            c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", SPEED_UA)
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) continue
            val st = c.inputStream
            val buf = ByteArray(32768)
            var total = 0L
            val t0 = System.nanoTime()
            val limit = t0 + 7_000_000_000L
            while (true) {
                val n = st.read(buf)
                if (n < 0) break
                total += n
                if (System.nanoTime() >= limit) break
            }
            val secs = (System.nanoTime() - t0) / 1e9
            try {
                st.close()
            } catch (_: Throwable) {
            }
            if (total > 50_000 && secs > 0.05) return total * 8 / secs / 1e6
        } catch (_: Throwable) {
        } finally {
            try {
                c?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
    return -1.0
}

// Upload speed in Mbit/s (-1 when it could not be measured). Gives up after ~12 seconds.
internal fun wgMeasureUpMbps(): Double {
    val result = DoubleArray(1) { -1.0 }
    val holder = arrayOfNulls<HttpURLConnection>(1)
    val th = Thread {
        try {
            val size = 1_500_000
            val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            holder[0] = c
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("User-Agent", SPEED_UA)
            val data = ByteArray(16384) { (it * 31).toByte() }
            val os = c.outputStream
            val t0 = System.nanoTime()
            var sent = 0
            while (sent < size) {
                val n = minOf(data.size, size - sent)
                os.write(data, 0, n)
                sent += n
            }
            os.flush()
            val code = c.responseCode
            val secs = (System.nanoTime() - t0) / 1e9
            try {
                os.close()
            } catch (_: Throwable) {
            }
            if (code in 200..299 && secs > 0.05) result[0] = size * 8 / secs / 1e6
        } catch (_: Throwable) {
        }
    }
    th.isDaemon = true
    th.start()
    try {
        th.join(12000)
    } catch (_: Throwable) {
    }
    if (th.isAlive) {
        try {
            holder[0]?.disconnect()
        } catch (_: Throwable) {
        }
        return -1.0
    }
    return result[0]
}

// Multi-line report shown under the server in the Pro screen.
internal fun MainActivity.wgSpeedReport(): String {
    val (loss, avg) = wgMeasureLoss()
    val down = wgMeasureDownMbps()
    val up = wgMeasureUpMbps()
    fun f(v: Double) = if (v < 0) "—" else String.format(Locale.US, "%.1f Mbps", v)
    val icon = if (loss == 0) "🟢" else if (loss <= 5) "🟡" else "🔴"
    return icon + " Packet Loss: " + loss + "%" + (if (avg >= 0) "  •  Ping: $avg ms" else "") +
        "\n⬇️ Download: " + f(down) + "\n⬆️ Upload: " + f(up)
}
