package com.ezzdigi.ezzgameboost

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

private const val BN_MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val BN_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// A TextView whose letters have a bright band sweeping across them again and again (glossy motion).
internal class ShineText(
    ctx: Context,
    private val baseColor: Int,
    private val hiColor: Int,
    delayMs: Long
) : TextView(ctx) {
    private var phase = 0f
    private var shader: LinearGradient? = null
    private val m = Matrix()
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800L
        startDelay = delayMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = if (w > 0) {
            LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(baseColor, baseColor, hiColor, baseColor, baseColor),
                floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        val s = shader
        if (s != null) {
            // The gradient covers the text at phase 0.5; at 0 and 1 it is fully outside (plain base color).
            m.setTranslate((phase * 2f - 1f) * width, 0f)
            s.setLocalMatrix(m)
            paint.shader = s
        }
        super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!anim.isStarted) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}

// Online mobile games, shown in English on the subscription page.
private val BANNER_GAMES = listOf(
    "PUBG MOBILE",
    "CALL OF DUTY MOBILE",
    "FREE FIRE",
    "MOBILE LEGENDS",
    "BRAWL STARS",
    "CLASH ROYALE",
    "STANDOFF 2",
    "GENSHIN IMPACT",
    "WILD RIFT",
    "EA FC MOBILE",
    "ROBLOX",
    "ARENA BREAKOUT"
)

// Glass card with the game names in graffiti style (Black Ops One) and a shine that keeps moving.
internal fun MainActivity.subGamesBanner() {
    section("ONLINE MOBILE GAMES")
    val face: Typeface = try {
        Typeface.createFromAsset(assets, "fonts/BlackOpsOne-Regular.ttf")
    } catch (_: Throwable) {
        Typeface.DEFAULT_BOLD
    }
    val card = subGlass(16)

    val base = if (uiLight) Color.argb(215, 40, 44, 62) else Color.argb(150, 255, 255, 255)
    val hi = if (uiLight) accent else Color.WHITE

    val title = TextView(this)
    title.text = "LOW PING  •  STABLE  •  FAST"
    title.typeface = face
    title.textSize = 11f
    title.letterSpacing = 0.12f
    title.gravity = Gravity.CENTER
    title.setTextColor(accent)
    card.addView(title, LinearLayout.LayoutParams(BN_MATCH, BN_WRAP))

    for ((i, name) in BANNER_GAMES.withIndex()) {
        val t = ShineText(this, base, hi, (i * 230L) % 2800L)
        t.text = name
        t.typeface = face
        t.textSize = if (i % 3 == 0) 24f else 19f
        t.letterSpacing = 0.05f
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        t.maxLines = 1
        t.setShadowLayer(dp(7).toFloat(), 0f, dp(2).toFloat(), accentAlpha(170))
        // A small tilt gives the spray-tag look; it alternates so the lines do not look uniform.
        t.rotation = if (i % 2 == 0) -2f else 1.5f
        val lp = LinearLayout.LayoutParams(BN_MATCH, BN_WRAP)
        lp.topMargin = dp(if (i == 0) 12 else 9)
        card.addView(t, lp)
    }
    content.addView(card, vlp(0, 8))
}
