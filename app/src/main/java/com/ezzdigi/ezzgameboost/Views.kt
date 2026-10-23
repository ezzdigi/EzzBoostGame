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

class BatteryState(val pct: Int, val temp: Float, val charging: Boolean)

// ---------------------------------------------------------------------------
// Custom views
// ---------------------------------------------------------------------------

class GaugeView(ctx: Context, private val title: String, private val mainColor: Int) : View(ctx) {
    private var shown = 0f
    private var target = 0f
    private var centerText = "0%"
    private var subText = ""
    private var arcColor = Color.WHITE
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = if (uiLight) Color.rgb(212, 217, 230) else Color.rgb(40, 43, 58)
    }
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = mainColor
    }
    private val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = if (uiLight) Color.rgb(98, 105, 126) else Color.rgb(141, 147, 167)
    }
    private val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = if (uiLight) Color.rgb(98, 105, 126) else Color.rgb(141, 147, 167)
    }

    fun setTarget(frac: Float, center: String, sub: String, c: Int) {
        centerText = center
        subText = sub
        arcColor = c
        target = frac.coerceIn(0f, 1f)
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, target)
        a.duration = 700
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h * 0.95f)
        val sw = size * 0.085f
        trackP.strokeWidth = sw
        arcP.strokeWidth = sw
        arcP.color = arcColor
        val cx = w / 2f
        val cy = h * 0.46f
        val r = size / 2f - sw
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 135f, 270f, false, trackP)
        if (shown > 0.005f) canvas.drawArc(rect, 135f, 270f * shown, false, arcP)
        valP.textSize = size * 0.21f
        val baseY = cy + valP.textSize * 0.15f
        canvas.drawText(centerText, cx, baseY, valP)
        subP.textSize = size * 0.105f
        canvas.drawText(subText, cx, baseY + subP.textSize * 1.4f, subP)
        titleP.textSize = size * 0.1f
        canvas.drawText(title, cx, cy + r * 0.95f, titleP)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

class ChartView(ctx: Context, private val lineColor: Int) : View(ctx) {
    private var pts: List<Int> = emptyList()
    private val gridP = Paint().apply {
        color = if (uiLight) Color.rgb(224, 228, 238) else Color.rgb(36, 39, 54)
        strokeWidth = 2f
    }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = lineColor
    }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = lineColor
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lossP = Paint().apply {
        color = Color.argb(110, 255, 55, 70)
        strokeWidth = 6f
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (uiLight) Color.rgb(98, 105, 126) else Color.rgb(141, 147, 167)
        textSize = 26f
    }

    fun setPoints(p: List<Int>) {
        pts = ArrayList(p)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = Color.red(lineColor)
        val g = Color.green(lineColor)
        val b = Color.blue(lineColor)
        fillP.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(90, r, g, b), Color.argb(0, r, g, b), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridP)
        }
        if (pts.isEmpty()) {
            canvas.drawText("—", w / 2f - 8f, h / 2f, txtP)
            return
        }
        val maxV = maxOf(60, pts.maxOrNull() ?: 60).toFloat() * 1.15f
        val n = pts.size
        val stepX = if (n > 1) (w - 2 * pad) / (n - 1) else 0f
        val path = Path()
        val area = Path()
        var started = false
        var lastX = pad
        var lastY = h
        for (i in 0 until n) {
            val x = pad + stepX * i
            val v = pts[i]
            if (v < 0) {
                canvas.drawLine(x, 0f, x, h, lossP)
                continue
            }
            val y = h - pad - (v / maxV) * (h - 2 * pad)
            if (!started) {
                path.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
        if (started) {
            area.lineTo(lastX, h)
            area.close()
            canvas.drawPath(area, fillP)
            canvas.drawPath(path, lineP)
            canvas.drawCircle(lastX, lastY, 9f, dotP)
        }
        canvas.drawText("${maxV.toInt()} ms", 8f, 28f, txtP)
    }
}

class BoostView(
    ctx: Context,
    private val accentColor: Int,
    private val panelColor: Int,
    private val textColor: Int
) : View(ctx) {
    var running = false
        private set
    private var angle = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val dimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
    }
    private val faceP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
    }
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = textColor
    }

    fun start() {
        running = true
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 360f)
        a.duration = 900
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    fun stop() {
        running = false
        anim?.cancel()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = minOf(w, h) / 2f
        faceP.shader = RadialGradient(
            w / 2f, h / 2f, r,
            Color.argb(110, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            panelColor, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val sw = minOf(width, height) * 0.05f
        val r = minOf(width, height) / 2f - sw
        ringP.strokeWidth = sw
        dimP.strokeWidth = sw
        canvas.drawCircle(cx, cy, r - sw, faceP)
        canvas.drawCircle(cx, cy, r, dimP)
        if (running) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, angle, 110f, false, ringP)
        } else {
            canvas.drawCircle(cx, cy, r, ringP)
        }
        iconP.textSize = r * 0.62f
        canvas.drawText("⚡", cx, cy + r * 0.05f, iconP)
        capP.textSize = r * 0.27f
        canvas.drawText(if (running) "CLEANING" else "BOOST", cx, cy + r * 0.52f, capP)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

class LogoView(ctx: Context, private val ringColor: Int, private val bgColor: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val dpad = Path()
    private val bolt = Path()

    init {
        body.moveTo(36f, 46f)
        body.lineTo(72f, 46f)
        body.quadTo(80f, 46f, 82f, 58f)
        body.lineTo(83.5f, 68f)
        body.quadTo(84.5f, 76f, 78f, 76f)
        body.quadTo(73f, 76f, 70f, 71f)
        body.lineTo(67f, 66f)
        body.lineTo(41f, 66f)
        body.lineTo(38f, 71f)
        body.quadTo(35f, 76f, 30f, 76f)
        body.quadTo(23.5f, 76f, 24.5f, 68f)
        body.lineTo(26f, 58f)
        body.quadTo(28f, 46f, 36f, 46f)
        body.close()

        dpad.moveTo(37f, 52f)
        dpad.lineTo(40f, 52f)
        dpad.lineTo(40f, 56f)
        dpad.lineTo(44f, 56f)
        dpad.lineTo(44f, 59f)
        dpad.lineTo(40f, 59f)
        dpad.lineTo(40f, 63f)
        dpad.lineTo(37f, 63f)
        dpad.lineTo(37f, 59f)
        dpad.lineTo(33f, 59f)
        dpad.lineTo(33f, 56f)
        dpad.lineTo(37f, 56f)
        dpad.close()

        bolt.moveTo(57f, 27f)
        bolt.lineTo(48f, 39f)
        bolt.lineTo(53f, 39f)
        bolt.lineTo(50f, 47f)
        bolt.lineTo(60f, 34f)
        bolt.lineTo(55f, 34f)
        bolt.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 108f
        canvas.save()
        canvas.scale(s, s)
        p.style = Paint.Style.FILL
        p.color = bgColor
        canvas.drawRoundRect(0f, 0f, 108f, 108f, 26f, 26f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.5f
        p.color = ringColor
        canvas.drawCircle(54f, 54f, 38f, p)
        p.style = Paint.Style.FILL
        canvas.save()
        canvas.scale(0.88f, 0.88f, 54f, 61f)
        p.color = Color.rgb(242, 243, 247)
        canvas.drawPath(body, p)
        p.color = bgColor
        canvas.drawPath(dpad, p)
        p.color = ringColor
        canvas.drawCircle(68f, 54f, 2.6f, p)
        canvas.drawCircle(74f, 59.5f, 2.6f, p)
        canvas.restore()
        p.color = Color.rgb(255, 176, 32)
        canvas.drawPath(bolt, p)
        canvas.restore()
    }
}
