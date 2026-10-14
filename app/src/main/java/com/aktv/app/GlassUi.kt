package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Brand palette + colour extraction from channel logos. */
object Accent {
    val ABYSS = 0xFF05060D.toInt()
    val SIGNAL = 0xFF3D8BFF.toInt()
    val ION = 0xFF7FE7FF.toInt()
    val INDIGO = 0xFF4B3BD6.toInt()
    val DEEP = 0xFF1B1A6B.toInt()
    val LIVE = 0xFFFF375F.toInt()
    val TEXT = 0xFFF4F6FF.toInt()
    val DIM = 0xFF9AA0C3.toInt()
    val FAINT = 0xFF5F6590.toInt()

    /** Makes any logo colour usable as a glow: saturated, mid-light. Greys fall back to brand blue. */
    fun normalize(c: Int?): Int {
        if (c == null) return SIGNAL
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return SIGNAL
        hsl[1] = max(hsl[1], 0.65f); hsl[2] = hsl[2].coerceIn(0.48f, 0.62f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** A neighbouring hue so the backdrop has depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = (hsl[0] + 48f) % 360f; hsl[2] = 0.42f
        return ColorUtils.HSLToColor(hsl)
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(SIGNAL); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/** Shared easing: quick start, long soft landing (feels like tvOS). */
val Ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)

/**
 * Frosted-glass surface. Focus is animated (fill tint, inner accent glow and lit edge fade in),
 * and it never casts an elevation shadow – translucent views + elevation shadows is what used to
 * leave dark smudges / black bars on the home screen.
 * radiusDp < 0 gives a pill / circle.
 */
class GlassDrawable(private val dp: Float, private val radiusDp: Float = -1f) : Drawable() {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeIdleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgeFocusP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var rad = 0f
    private var amount = 0f            // 0 = idle … 1 = fully focused
    private var target = false
    private var anim: ValueAnimator? = null

    var accent: Int = Accent.SIGNAL
        set(v) { if (field != v) { field = v; rebuild(); invalidateSelf() } }
    var base: Int = Color.argb(20, 255, 255, 255)
        set(v) { field = v; invalidateSelf() }
    var showFill = true
    /** false = only drawn while focused (used for list rows in the channel guide). */
    var idleVisible = true
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    /** Animated focus change. */
    var focused: Boolean
        get() = target
        set(v) {
            if (target == v) return
            target = v
            anim?.cancel()
            anim = ValueAnimator.ofFloat(amount, if (v) 1f else 0f).apply {
                duration = if (v) 170 else 230; interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { amount = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        }
    /** Jump to a focus state without animating (used when a recycled view is re-bound). */
    fun snap(v: Boolean) { anim?.cancel(); target = v; amount = if (v) 1f else 0f; invalidateSelf() }

    /** Hold a partial focus level (used by floating panels so their edge glows softly in the channel colour). */
    fun hold(level: Float) { anim?.cancel(); target = level > 0f; amount = level; invalidateSelf() }

    override fun onBoundsChange(b: Rect) { rebuild() }

    private fun radiusFor(b: Rect) = if (radiusDp < 0) min(b.width(), b.height()) / 2f else radiusDp * dp

    private fun rebuild() {
        val b = bounds; if (b.isEmpty) return
        val inset = 1f * dp
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        rad = radiusFor(b)
        edgeIdleP.strokeWidth = 1f * dp
        edgeFocusP.strokeWidth = 2f * dp
        tintP.color = ColorUtils.setAlphaComponent(accent, 70)
        glowP.shader = RadialGradient(b.exactCenterX(), b.bottom.toFloat(), max(b.width(), b.height()) * 0.85f,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 135), ColorUtils.setAlphaComponent(accent, 30), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        sheenP.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.6f,
            Color.argb(26, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        edgeIdleP.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(36, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        edgeFocusP.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.argb(255, 255, 255, 255), ColorUtils.setAlphaComponent(accent, 220), Color.argb(170, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val vis = if (idleVisible) 1f else amount
        if (vis <= 0.01f || r.isEmpty) return
        val f = amount
        if (showFill) {
            fillP.color = ColorUtils.setAlphaComponent(base, (Color.alpha(base) * vis).toInt())
            c.drawRoundRect(r, rad, rad, fillP)
            if (f > 0.01f) {
                tintP.color = ColorUtils.setAlphaComponent(accent, (70 * f).toInt())
                c.drawRoundRect(r, rad, rad, tintP)
            }
        }
        if (f > 0.01f) { glowP.alpha = (255 * f).toInt(); c.drawRoundRect(r, rad, rad, glowP) }
        sheenP.alpha = (255 * vis).toInt(); c.drawRoundRect(r, rad, rad, sheenP)
        if (f < 0.99f) { edgeIdleP.alpha = (255 * (1f - f) * vis).toInt(); c.drawRoundRect(r, rad, rad, edgeIdleP) }
        if (f > 0.01f) { edgeFocusP.alpha = (255 * f).toInt(); c.drawRoundRect(r, rad, rad, edgeFocusP) }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    /** Empty outline = no elevation shadow, ever. */
    override fun getOutline(o: Outline) { o.setEmpty() }
}

/** Soft radial light that sits behind the hero logo and follows the channel colour. */
class GlowDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    var color = Accent.SIGNAL
        private set
    fun animateTo(c: Int) {
        val from = color; if (from == c) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650; interpolator = DecelerateInterpolator()
            addUpdateListener { color = ColorUtils.blendARGB(from, c, it.animatedValue as Float); rebuild(); invalidateSelf() }
            start()
        }
    }
    override fun onBoundsChange(b: Rect) { rebuild() }
    private fun rebuild() {
        val b = bounds; if (b.isEmpty) return
        p.shader = RadialGradient(b.exactCenterX(), b.exactCenterY(), min(b.width(), b.height()) / 2f,
            intArrayOf(ColorUtils.setAlphaComponent(color, 150), ColorUtils.setAlphaComponent(color, 55), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) { val b = bounds; c.drawCircle(b.exactCenterX(), b.exactCenterY(), min(b.width(), b.height()) / 2f, p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: Outline) { o.setEmpty() }
}

/** Row container that fades as ONE draw (no offscreen layer), so dimming never clips tiles or glows. */
class RowLayout @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : LinearLayout(c, a) {
    override fun hasOverlappingRendering() = false
}

/** Living backdrop: three slow-drifting light fields that flow into the focused item's colours. */
class AuroraView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val cols = intArrayOf(Accent.SIGNAL, Accent.INDIGO, Accent.DEEP)
    // x, y (fractions of size), radius (fraction of width), phase
    private val blobs = arrayOf(
        floatArrayOf(0.80f, 0.22f, 0.70f, 0f),
        floatArrayOf(0.08f, 0.00f, 0.55f, 2.1f),
        floatArrayOf(0.45f, 1.10f, 0.80f, 4.2f))
    private val alphas = intArrayOf(150, 105, 120)
    private val shaders = arrayOfNulls<RadialGradient>(3)
    private val m = Matrix()
    private val p = Paint()
    private var t = 0f
    private var last = 0L
    private var colorAnim: ValueAnimator? = null
    private val drift = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 48000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            val now = SystemClock.uptimeMillis()
            if (now - last > 33) { last = now; invalidate() }
        }
    }

    fun setPalette(a: Int, b: Int) {
        val fromA = cols[0]; val fromB = cols[1]
        if (fromA == a && fromB == b) return
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                cols[0] = ColorUtils.blendARGB(fromA, a, f); cols[1] = ColorUtils.blendARGB(fromB, b, f)
                cols[2] = ColorUtils.blendARGB(Accent.DEEP, b, 0.35f * f)
                rebuild(); invalidate()
            }
            start()
        }
    }

    fun pause() { if (drift.isRunning) drift.pause() }
    fun resume() { if (drift.isPaused) drift.resume() else if (!drift.isStarted && isAttachedToWindow) drift.start() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!drift.isStarted) drift.start() }
    override fun onDetachedFromWindow() { drift.cancel(); colorAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }

    private fun rebuild() {
        if (width == 0) return
        for (i in 0..2) {
            val rad = blobs[i][2] * width; val c = cols[i]
            shaders[i] = RadialGradient(0f, 0f, rad,
                intArrayOf(ColorUtils.setAlphaComponent(c, alphas[i]), ColorUtils.setAlphaComponent(c, alphas[i] / 3), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Accent.ABYSS)
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0..2) {
            val s = shaders[i] ?: continue; val b = blobs[i]
            val cx = w * (b[0] + 0.05f * sin(t + b[3])); val cy = h * (b[1] + 0.07f * cos(2 * t + b[3]))
            m.setTranslate(cx, cy); s.setLocalMatrix(m); p.shader = s
            cv.drawRect(0f, 0f, w, h, p)
        }
    }
}

/** Keeps the focused item pinned to the start edge, like Netflix rows – with a soft eased glide. */
class SnapLayoutManager(c: Context, orientation: Int) : LinearLayoutManager(c, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val d = if (orientation == RecyclerView.HORIZONTAL) child.left - lp.leftMargin - parent.paddingLeft
                else child.top - lp.topMargin - parent.paddingTop
        if (d == 0) return false
        val dx = if (orientation == RecyclerView.HORIZONTAL) d else 0
        val dy = if (orientation == RecyclerView.HORIZONTAL) 0 else d
        if (immediate) parent.scrollBy(dx, dy) else parent.smoothScrollBy(dx, dy, Ease, 300)
        return true
    }
}

/**
 * Netflix-style opening: a light line draws, the AKTV letters land one after another,
 * a light sweep runs through the wordmark, then the camera pushes through it into the app.
 */
class SplashView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var onDone: (() -> Unit)? = null
    private val total = 3700L
    private var p = 0f
    private var finished = false
    private val word = "AKTV"
    private val face = ResourcesCompat.getFont(c, R.font.sora_bold) ?: Typeface.DEFAULT_BOLD
    private val faceTag = ResourcesCompat.getFont(c, R.font.sora_semibold) ?: Typeface.DEFAULT
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = faceTag; color = Accent.DIM; textAlign = Paint.Align.CENTER }
    private val paths = Array(word.length) { Path() }
    private val boxes = Array(word.length) { RectF() }
    private val shaders = arrayOfNulls<LinearGradient>(word.length)
    private var sweepShader: LinearGradient? = null
    private val m = Matrix()
    private var cx = 0f; private var cy = 0f; private var sz = 0f
    private var wl = 0f; private var wr = 0f; private var bl = 0f
    private var anim: ValueAnimator? = null

    private fun c01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x
    private fun out3(x: Float): Float { val k = 1f - c01(x); return 1f - k * k * k }
    private fun smooth(x: Float): Float { val k = c01(x); return k * k * (3f - 2f * k) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        cx = w / 2f; cy = h / 2f
        sz = min(w * 0.205f, h * 0.36f)
        measure.textSize = sz
        val gap = sz * 0.035f
        val widths = FloatArray(word.length) { measure.measureText(word, it, it + 1) }
        val total = widths.sum() + gap * (word.length - 1)
        val capB = Rect(); measure.getTextBounds("A", 0, 1, capB)
        bl = cy + capB.height() / 2f - sz * 0.05f
        var x = cx - total / 2f; wl = x; wr = x + total
        for (i in word.indices) {
            paths[i].reset(); measure.getTextPath(word, i, i + 1, x, bl, paths[i])
            paths[i].computeBounds(boxes[i], true)
            val top = bl - capB.height().toFloat()
            shaders[i] = if (i < 2) LinearGradient(0f, top, 0f, bl, 0xFFFFFFFF.toInt(), 0xFFB9CCFF.toInt(), Shader.TileMode.CLAMP)
                         else LinearGradient(0f, top, 0f, bl, Accent.ION, Accent.SIGNAL, Shader.TileMode.CLAMP)
            x += widths[i] + gap
        }
        val bw = sz * 0.55f
        sweepShader = LinearGradient(-bw, 0f, bw, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(235, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glowP.shader = RadialGradient(cx, cy, w * 0.5f,
            intArrayOf(ColorUtils.setAlphaComponent(Accent.SIGNAL, 130), ColorUtils.setAlphaComponent(Accent.INDIGO, 50), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        lineP.shader = LinearGradient(wl, 0f, wr, 0f, Accent.SIGNAL, Accent.ION, Shader.TileMode.CLAMP)
        tagP.textSize = sz * 0.13f; tagP.letterSpacing = 0.12f
    }

    fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total; interpolator = LinearInterpolator()
            addUpdateListener { p = it.animatedValue as Float; if (p >= 1f) done() else invalidate() }
            start()
        }
    }
    /** Any key press jumps ahead to the push-through. */
    fun skip() { anim?.let { if (it.currentPlayTime < total * 0.78f) it.currentPlayTime = (total * 0.78f).toLong() } }
    private fun done() {
        if (finished) return
        finished = true; anim?.cancel(); visibility = View.GONE; onDone?.invoke()
    }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = p * total / 1000f                       // seconds
        val z = smooth((t - 2.95f) / 0.75f)             // push-through progress
        val bg = 1f - smooth((t - 3.05f) / 0.55f)
        cv.drawColor(Color.argb((255 * bg).toInt(), 5, 6, 13))

        // ambient light
        val gA = smooth((t - 0.45f) / 1.3f) * (1f - smooth((t - 3.0f) / 0.5f))
        val flash = max(0f, 1f - kotlin.math.abs(t - 1.35f) / 0.35f)
        glowP.alpha = (255 * min(1f, gA * 0.9f + flash * 0.5f)).toInt()
        cv.drawRect(0f, 0f, w, h, glowP)

        cv.save()
        val s = 1f + z * z * 6f
        cv.scale(s, s, cx, cy)
        val fade = 1f - smooth((t - 3.1f) / 0.45f)

        // the line that draws out of the centre
        val lineA = smooth(t / 0.4f)
        val half = (wr - wl) / 2f * out3((t - 0.05f) / 0.85f)
        if (half > 1f) {
            lineP.alpha = (255 * lineA * fade).toInt()
            val ly = bl + sz * 0.17f; val th = max(2f, sz * 0.018f)
            cv.drawRoundRect(cx - half, ly - th / 2, cx + half, ly + th / 2, th, th, lineP)
        }

        // letters land one after another
        for (i in word.indices) {
            val q = out3((t - (0.55f + i * 0.15f)) / 0.62f)
            if (q <= 0f) continue
            val bx = boxes[i].centerX(); val by = boxes[i].centerY()
            cv.save()
            val ls = 1.4f - 0.4f * q
            cv.translate(0f, (1f - q) * sz * 0.10f)
            cv.scale(ls, ls, bx, by)
            fill.shader = shaders[i]; fill.alpha = (255 * q * fade).toInt()
            cv.drawPath(paths[i], fill)
            // light sweep through the letters
            val sw = smooth((t - 1.55f) / 0.95f)
            if (sw > 0f && sw < 1f) {
                val sx = wl - sz * 0.6f + (wr - wl + sz * 1.2f) * sw
                m.setTranslate(sx, 0f); sweepShader?.setLocalMatrix(m)
                sweepP.shader = sweepShader; sweepP.alpha = (255 * fade).toInt()
                cv.drawPath(paths[i], sweepP)
            }
            cv.restore()
        }

        // tagline
        val ta = smooth((t - 2.05f) / 0.55f) * fade
        if (ta > 0f) { tagP.alpha = (255 * ta).toInt(); cv.drawText("BY AKSHANSH SAHU", cx, bl + sz * 0.50f, tagP) }
        cv.restore()
    }
}
