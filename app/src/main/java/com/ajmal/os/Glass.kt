package com.ajmal.os

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android 9 has no RenderEffect, so the backdrop is the wallpaper, down-sampled (1/8 or 1/12 screen),
 * box-blurred once on a background thread and cached. Panels sample that tiny bitmap through a
 * BitmapShader (Ultra Lite) or a small pre-refracted bitmap (Balanced / Ultra Glass). No GL thread,
 * no continuous rendering: panels only redraw when something invalidates them.
 */
class GlassRenderer(private val app: LauncherApp) {
    @Volatile var blurred: Bitmap? = null
    @Volatile var blurPix: IntArray? = null
    /** Lightly blurred copy used by Pure Liquid so the wallpaper stays readable through the lens. */
    @Volatile var clearPix: IntArray? = null
    /** Much stronger blur used behind full-screen overlays (App Library, Search, Folders, Overview). */
    @Volatile var heavy: Bitmap? = null
    var bw = 0
    var bh = 0
    var scale = 8
    var gen = 0
    var avgColor = 0xFF808080.toInt()
    var avgLum = 0.5f
    var screenW = 0
    var screenH = 0
    val changeCbs = ArrayList<() -> Unit>()

    private val listeners = ArrayList<GlassFrame>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var wpId = -2

    init {
        val dm = DisplayMetrics()
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        screenW = min(dm.widthPixels, dm.heightPixels)
        screenH = max(dm.widthPixels, dm.heightPixels)
    }

    fun add(g: GlassFrame) { if (!listeners.contains(g)) listeners.add(g) }
    fun remove(g: GlassFrame) { listeners.remove(g) }

    private fun notifyAll2() {
        for (g in ArrayList(listeners)) g.invalidate()
        for (c in ArrayList(changeCbs)) c()
    }

    private fun currentWpId(): Int = try {
        WallpaperManager.getInstance(app).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (t: Throwable) { -1 }

    fun reloadIfChanged() {
        if (currentWpId() != wpId) reload()
    }

    fun reload() {
        val p = app.prefs
        wpId = currentWpId()
        if (!p.bool(K.WP_BLUR)) {
            blurred = null; blurPix = null; clearPix = null; heavy = null; gen++; notifyAll2()
            return
        }
        val reduced = p.bool(K.REDUCED_BLUR)
        val sc = if (reduced) 12 else 8
        val rad = max(1, (p.int(K.BLUR) / 100f * (if (reduced) 3f else 8f)).roundToInt())
        val sw = screenW
        val sh = screenH
        exec.execute {
            var bmp: Bitmap? = null
            var pix: IntArray? = null
            var cpx: IntArray? = null
            var hv: Bitmap? = null
            var avg = 0xFF808080.toInt()
            var lum = 0.5f
            try {
                val wm = WallpaperManager.getInstance(app)
                val d = if (wm.wallpaperInfo == null) wm.drawable else null
                if (d != null) {
                    val w = max(8, sw / sc)
                    val h = max(8, sh / sc)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(b)
                    var iw = d.intrinsicWidth
                    var ih = d.intrinsicHeight
                    if (iw <= 0 || ih <= 0) { iw = w; ih = h }
                    val s = max(w / iw.toFloat(), h / ih.toFloat())
                    val dw = iw * s
                    val dh = ih * s
                    val l = ((w - dw) / 2f).toInt()
                    val t = ((h - dh) / 2f).toInt()
                    d.setBounds(l, t, l + dw.toInt(), t + dh.toInt())
                    d.draw(c)
                    val a = IntArray(w * h)
                    b.getPixels(a, 0, w, 0, 0, w, h)
                    var sr = 0L; var sg = 0L; var sb = 0L
                    for (v in a) { sr += (v shr 16) and 255; sg += (v shr 8) and 255; sb += v and 255 }
                    val n = a.size
                    val ar = (sr / n).toInt(); val ag = (sg / n).toInt(); val ab = (sb / n).toInt()
                    avg = Color.rgb(ar, ag, ab)
                    lum = (0.299f * ar + 0.587f * ag + 0.114f * ab) / 255f
                    val tmp = IntArray(a.size)
                    // Heavy copy first (from the sharp pixels): wide radius, 4 passes ~ Gaussian.
                    val ha = a.copyOf()
                    val hr = max(4, (rad * 1.8f).roundToInt())
                    repeat(4) { hPass(ha, tmp, w, h, hr); vPass(tmp, ha, w, h, hr) }
                    val hb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    hb.setPixels(ha, 0, w, 0, 0, w, h)
                    hv = hb
                    val ca = a.copyOf()
                    val cr = max(1, rad / 3)
                    repeat(2) { hPass(ca, tmp, w, h, cr); vPass(tmp, ca, w, h, cr) }
                    cpx = ca
                    // Normal glass blur: 3 box passes ~ Gaussian (2 left visible box artefacts).
                    repeat(3) { hPass(a, tmp, w, h, rad); vPass(tmp, a, w, h, rad) }
                    b.setPixels(a, 0, w, 0, 0, w, h)
                    bmp = b; pix = a
                }
            } catch (t: Throwable) { }
            val fb = bmp; val fp = pix; val fc = cpx; val fa = avg; val fl = lum; val fh = hv
            main.post {
                blurred = fb; blurPix = fp; clearPix = fc; heavy = fh; scale = sc
                if (fb != null) { bw = fb.width; bh = fb.height }
                avgColor = fa; avgLum = fl
                gen++
                notifyAll2()
            }
        }
    }

    private fun hPass(a: IntArray, b: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = a[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (x in 0 until w) {
                b[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pa = a[row + min(x + r + 1, w - 1)]
                val ps = a[row + max(x - r, 0)]
                sr += ((pa shr 16) and 255) - ((ps shr 16) and 255)
                sg += ((pa shr 8) and 255) - ((ps shr 8) and 255)
                sb += (pa and 255) - (ps and 255)
            }
        }
    }

    private fun vPass(a: IntArray, b: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = a[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (y in 0 until h) {
                b[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pa = a[min(y + r + 1, h - 1) * w + x]
                val ps = a[max(y - r, 0) * w + x]
                sr += ((pa shr 16) and 255) - ((ps shr 16) and 255)
                sg += ((pa shr 8) and 255) - ((ps shr 8) and 255)
                sb += (pa and 255) - (ps and 255)
            }
        }
    }
}

/** A frame that draws a liquid-glass surface behind its children. */
open class GlassFrame(ctx: Context) : FrameLayout(ctx) {
    var radiusDp = 26f
    var refract = true
    var fullscreen = false
    var strength = 1f
    var shadowScale = 1f
    var drawBg = true

    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val rect = RectF()
    private val tmp = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val loc = IntArray(2)
    private val mtx = Matrix()
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null
    private var panel: Bitmap? = null
    private var pShader: BitmapShader? = null
    private var pX = Int.MIN_VALUE
    private var pY = Int.MIN_VALUE
    private var pGen = -1
    private var pForce = false
    private var pAt = 0L
    private val pExact = Runnable { pForce = true; invalidate() }
    private var pRev = -1
    private var sW = 0f
    private var sH = 0f
    private var sEdge = -1f
    private var edgeShader: LinearGradient? = null
    private var specShader: LinearGradient? = null
    private var sheenShader: LinearGradient? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        app.glass.add(this)
    }

    override fun onDetachedFromWindow() {
        app.glass.remove(this)
        super.onDetachedFromWindow()
    }

    private fun al(x: Float): Int = (x.coerceIn(0f, 1f) * 255f).toInt()

    override fun onDraw(c: Canvas) {
        if (!drawBg) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val p = app.prefs
        val prof = p.int(K.PROFILE)
        val dark = app.isDark()
        val rad = if (fullscreen) 0f else min(radiusDp * dp, min(w, h) / 2f)
        val g = app.glass
        val bm = if (fullscreen) (g.heavy ?: g.blurred) else g.blurred
        rect.set(0f, 0f, w, h)
        getLocationOnScreen(loc)
        if (prof == 3 && !fullscreen) { drawLiquid(c, w, h, bm); return }

        // Soft layered shadow (drawn outside the bounds; parents must not clip children).
        val sh = p.int(K.SHADOW) / 100f * shadowScale
        if (!fullscreen && bm != null && sh > 0.02f) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            for (i in 1..4) {
                val e = i * 2.2f * dp
                tmp.set(-e * 0.7f, e * 0.25f, w + e * 0.7f, h + e * 0.9f)
                paint.color = Color.argb((70f * sh / i).toInt().coerceIn(0, 255), 0, 0, 0)
                c.drawRoundRect(tmp, rad + e * 0.7f, rad + e * 0.7f, paint)
            }
        }

        // Blurred wallpaper backdrop.
        paint.style = Paint.Style.FILL
        if (bm != null) {
            if (prof > 0 && refract && !fullscreen) {
                val pb = panelBitmap(g, w.toInt(), h.toInt(), prof)
                if (pb != null) {
                    if (pShader == null) pShader = BitmapShader(pb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    mtx.setScale(w / pb.width, h / pb.height)
                    pShader!!.setLocalMatrix(mtx)
                    paint.shader = pShader
                }
            }
            if (paint.shader == null) {
                if (shader == null || shaderBmp !== bm) {
                    shader = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    shaderBmp = bm
                }
                mtx.setScale(g.scale.toFloat(), g.scale.toFloat())
                mtx.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                shader!!.setLocalMatrix(mtx)
                paint.shader = shader
            }
            paint.color = Color.WHITE
            c.drawRoundRect(rect, rad, rad, paint)
            paint.shader = null
        }

        // Surface tint. "Transparency" slider: higher = clearer glass.
        val a = (0.06f + 0.56f * (100 - p.int(K.OPACITY)) / 100f) * strength + (if (bm == null) 0.22f else 0f)
        val base = app.glassBase(dark)
        paint.color = (base and 0xFFFFFF) or (al(a.coerceAtMost(0.92f)) shl 24)
        c.drawRoundRect(rect, rad, rad, paint)

        if (fullscreen) return

        val edge = (p.int(K.EDGE) / 100f).coerceIn(0f, 1f)
        if (w != sW || h != sH || edge != sEdge) {
            sW = w; sH = h; sEdge = edge
            edgeShader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.argb(al(0.75f * edge), 255, 255, 255),
                    Color.argb(al(0.06f * edge), 255, 255, 255),
                    Color.argb(al(0.38f * edge), 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            specShader = LinearGradient(
                0f, 0f, 0f, h * 0.5f,
                Color.argb(al(0.20f * edge + 0.03f), 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            sheenShader = LinearGradient(
                0f, h, 0f, h * 0.78f,
                Color.argb(al(0.10f * edge), 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        if (prof >= 1) {
            paint.shader = specShader
            c.drawRoundRect(rect, rad, rad, paint)
            if (prof == 2) {
                paint.shader = sheenShader
                c.drawRoundRect(rect, rad, rad, paint)
            }
            // Fresnel-style rim: three fading inner strokes.
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * dp
            for (k in 0..2) {
                val ins = (1.8f + k * 1.6f) * dp
                tmp.set(ins, ins, w - ins, h - ins)
                paint.color = Color.argb(al((0.10f - k * 0.03f) * edge), 255, 255, 255)
                val rr = max(0f, rad - ins)
                c.drawRoundRect(tmp, rr, rr, paint)
            }
        }
        if (edge > 0.02f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f * dp
            paint.shader = edgeShader
            val ins = 0.65f * dp
            tmp.set(ins, ins, w - ins, h - ins)
            c.drawRoundRect(tmp, max(0f, rad - ins), max(0f, rad - ins), paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    // =============================================================== Pure Liquid

    private val sqPath = Path()
    private val sqTmp = Path()
    private var liquid: Bitmap? = null
    private var lShader: BitmapShader? = null
    private var lX = Int.MIN_VALUE
    private var lY = Int.MIN_VALUE
    private var lGen = -1
    private var lRev = -1
    private var lAt = 0L
    private var lForce = false
    private val lExact = Runnable { lForce = true; invalidate() }
    private var lW = 0f
    private var lH = 0f
    private var lEdge = -1f
    private var rimShader: LinearGradient? = null
    private var rim2Shader: LinearGradient? = null
    private var topShader: LinearGradient? = null
    private var brShader: LinearGradient? = null
    private var fringeShader: LinearGradient? = null

    /**
     * Pure Liquid: a thick, water-filled glass slab with Apple-style continuous corners. The backdrop is
     * lens-refracted at the rim (with colour dispersion), gently rippled inside, saturated, and finished
     * with a bright fresnel rim, a refractive bulge at the bottom-right and a soft specular sheen.
     */
    private fun drawLiquid(c: Canvas, w: Float, h: Float, bm: Bitmap?) {
        val p = app.prefs
        val g = app.glass
        val dark = app.isDark()
        val rr = min(radiusDp * dp, min(w, h) / 2f)
        rect.set(0f, 0f, w, h)
        Squircle.path(rect, rr, sqPath)

        val sh = p.int(K.SHADOW) / 100f * shadowScale
        paint.style = Paint.Style.FILL
        paint.shader = null
        if (sh > 0.02f) {
            for (i in 1..4) {
                val e = i * 2.4f * dp
                tmp.set(-e * 0.7f, e * 0.3f, w + e * 0.7f, h + e)
                paint.color = Color.argb((60f * sh / i).toInt().coerceIn(0, 255), 0, 0, 0)
                c.drawPath(Squircle.path(tmp, rr + e * 0.7f, sqTmp), paint)
            }
        }

        val lb = if (bm != null) liquidBitmap(g, w.toInt(), h.toInt(), rr) else null
        if (lb != null) {
            if (lShader == null) lShader = BitmapShader(lb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            mtx.setScale(w / lb.width, h / lb.height)
            lShader!!.setLocalMatrix(mtx)
            paint.shader = lShader
            paint.color = Color.WHITE
            c.drawPath(sqPath, paint)
            paint.shader = null
        }

        // Very light surface tint (the water already carries the colour).
        val a = (0.03f + 0.30f * (100 - p.int(K.OPACITY)) / 100f) * strength + (if (lb == null) 0.26f else 0f)
        paint.color = (app.glassBase(dark) and 0xFFFFFF) or (al(a.coerceAtMost(0.8f)) shl 24)
        c.drawPath(sqPath, paint)

        val edge = (p.int(K.EDGE) / 100f).coerceIn(0.15f, 1f)
        if (w != lW || h != lH || edge != lEdge) {
            lW = w; lH = h; lEdge = edge
            rimShader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.argb(al(0.98f * edge), 255, 255, 255), Color.argb(al(0.16f * edge), 255, 255, 255),
                    Color.argb(al(0.30f * edge), 255, 255, 255), Color.argb(al(0.80f * edge), 255, 255, 255)),
                floatArrayOf(0f, 0.42f, 0.64f, 1f), Shader.TileMode.CLAMP)
            rim2Shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.argb(al(0.40f * edge), 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(al(0.30f * edge), 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            topShader = LinearGradient(0f, 0f, 0f, h * 0.46f, Color.argb(al(0.30f * edge + 0.03f), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            brShader = LinearGradient(w * 0.4f, h * 0.4f, w, h, Color.argb(0, 255, 255, 255), Color.argb(al(0.34f * edge), 255, 255, 255), Shader.TileMode.CLAMP)
            fringeShader = LinearGradient(w * 0.5f, h * 0.5f, w, h,
                intArrayOf(Color.argb(0, 120, 200, 255), Color.argb(al(0.30f * edge), 110, 190, 255), Color.argb(al(0.34f * edge), 255, 120, 225)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        // soft sheen from the top and refractive glow gathering at the bottom-right
        paint.shader = topShader
        c.drawPath(sqPath, paint)
        paint.style = Paint.Style.STROKE
        paint.shader = brShader
        paint.strokeWidth = 5.5f * dp
        tmp.set(3.2f * dp, 3.2f * dp, w - 3.2f * dp, h - 3.2f * dp)
        c.drawPath(Squircle.path(tmp, max(0f, rr - 3.2f * dp), sqTmp), paint)
        if (!p.bool(K.REDUCED_REFR)) {
            paint.shader = fringeShader
            paint.strokeWidth = 1.2f * dp
            tmp.set(3.8f * dp, 3.8f * dp, w - 3.8f * dp, h - 3.8f * dp)
            c.drawPath(Squircle.path(tmp, max(0f, rr - 3.8f * dp), sqTmp), paint)
        }
        // inner and outer fresnel rims
        paint.shader = rim2Shader
        paint.strokeWidth = 1.1f * dp
        tmp.set(2.4f * dp, 2.4f * dp, w - 2.4f * dp, h - 2.4f * dp)
        c.drawPath(Squircle.path(tmp, max(0f, rr - 2.4f * dp), sqTmp), paint)
        paint.shader = rimShader
        paint.strokeWidth = 1.5f * dp
        tmp.set(0.75f * dp, 0.75f * dp, w - 0.75f * dp, h - 0.75f * dp)
        c.drawPath(Squircle.path(tmp, max(0f, rr - 0.75f * dp), sqTmp), paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    /** Per-panel lens map: refraction + dispersion + ripples + water shading, sampled from the lightly blurred wallpaper. */
    private fun liquidBitmap(g: GlassRenderer, w: Int, h: Int, rr: Float): Bitmap? {
        val pix = g.clearPix ?: g.blurPix ?: return null
        val rev = app.prefs.rev
        var sc = 3
        while ((w / sc) * (h / sc) > 60000) sc++
        val pw = max(8, w / sc)
        val ph = max(8, h / sc)
        val cur = liquid
        if (cur != null && cur.width == pw && cur.height == ph && g.gen == lGen && rev == lRev) {
            val moved = abs(loc[0] - lX) + abs(loc[1] - lY)
            if (moved == 0) return cur
            if (!lForce && (moved <= 40 || SystemClock.uptimeMillis() - lAt < 90)) {
                removeCallbacks(lExact); postDelayed(lExact, 130); return cur
            }
        }
        lForce = false
        lAt = SystemClock.uptimeMillis()

        val bw = g.bw; val bh = g.bh
        val gs = g.scale.toFloat()
        val reduced = app.prefs.bool(K.REDUCED_REFR)
        val amt = (0.45f + app.prefs.int(K.REFRACT) / 100f * 0.9f).coerceAtMost(1.2f) * (if (reduced) 0.5f else 1f)
        val hw = w / 2f; val hh = h / 2f
        val R = min(rr * 1.35f, min(hw, hh))
        val band = (min(w, h) * 0.30f).coerceIn(9f * dp, 24f * dp).coerceAtMost(min(hw, hh) * 0.9f)
        val amp = band * (0.35f + 0.65f * amt)
        val mag = 1f + 0.035f * amt
        val rimW = 6.5f * dp
        val ripple = 7f * (0.5f + 0.5f * amt)
        val caustic = h > 56f * dp
        val out = IntArray(pw * ph)
        val ox = loc[0].toFloat(); val oy = loc[1].toFloat()
        val lx = -0.57f; val ly = -0.82f

        for (j in 0 until ph) {
            val y = (j + 0.5f) * h / ph
            val yy = y / h
            for (i in 0 until pw) {
                val x = (i + 0.5f) * w / pw
                val dx = abs(x - hw) - (hw - R)
                val dy = abs(y - hh) - (hh - R)
                val sx = if (x < hw) -1f else 1f
                val sy = if (y < hh) -1f else 1f
                var sd: Float; var nx: Float; var ny: Float
                if (dx > 0f && dy > 0f) {
                    val l3 = Math.cbrt((dx * dx * dx + dy * dy * dy).toDouble()).toFloat()
                    sd = l3 - R
                    val a2 = dx * dx; val b2 = dy * dy
                    val n = sqrt(a2 * a2 + b2 * b2) + 1e-4f
                    nx = sx * a2 / n; ny = sy * b2 / n
                } else if (dx > 0f) { sd = dx - R; nx = sx; ny = 0f }
                else if (dy > 0f) { sd = dy - R; nx = 0f; ny = sy }
                else if (dx > dy) { sd = dx - R; nx = sx; ny = 0f }
                else { sd = dy - R; nx = 0f; ny = sy }
                val d = max(0f, -sd)
                val u = (d / band).coerceIn(0f, 1f)
                val v = 1f - u
                val f = v * v * sqrt(v)

                // interior: slight magnification + slow water ripple
                val mx = hw + (x - hw) / mag
                val my = hh + (y - hh) / mag
                val wob = 1f - f * 0.6f
                val rx = (sin(x * 0.035f + y * 0.012f + 0.7f) + 0.6f * sin(y * 0.047f - x * 0.02f + 2.1f)) * ripple * 0.5f * wob
                val ry = (sin(x * 0.022f - y * 0.04f + 1.3f) + 0.6f * sin(x * 0.051f + y * 0.017f)) * ripple * 0.5f * wob
                val ex = nx * amp * f
                val ey = ny * amp * f
                var cr: Int; var cg: Int; var cb: Int
                if (f > 0.02f) {
                    val s1 = bilinear(pix, bw, bh, (ox + mx + rx + ex) / gs, (oy + my + ry + ey) / gs)
                    val s2 = bilinear(pix, bw, bh, (ox + mx + rx + ex * 0.93f) / gs, (oy + my + ry + ey * 0.93f) / gs)
                    val s3 = bilinear(pix, bw, bh, (ox + mx + rx + ex * 0.86f) / gs, (oy + my + ry + ey * 0.86f) / gs)
                    cr = (s1 shr 16) and 255; cg = (s2 shr 8) and 255; cb = s3 and 255
                } else {
                    val s1 = bilinear(pix, bw, bh, (ox + mx + rx) / gs, (oy + my + ry) / gs)
                    cr = (s1 shr 16) and 255; cg = (s1 shr 8) and 255; cb = s1 and 255
                }
                // saturated, slightly milky water
                val lum = 0.299f * cr + 0.587f * cg + 0.114f * cb
                var fr = lum + (cr - lum) * 1.30f
                var fg = lum + (cg - lum) * 1.30f
                var fb = lum + (cb - lum) * 1.30f
                fr += (255f - fr) * 0.05f; fg += (255f - fg) * 0.05f; fb += (255f - fb) * 0.05f

                // fresnel rim: brightest facing the light (top-left), secondary bulge bottom-right
                val ndl = nx * lx + ny * ly
                val t = (1f - d / rimW).coerceIn(0f, 1f)
                var hl = t * t * (0.16f + 0.60f * max(ndl, 0f) + 0.30f * max(-ndl, 0f))
                // dark meniscus just inside the rim gives the slab its thickness
                val dm = (d - rimW * 1.5f) / (rimW * 0.9f)
                val dark = 0.10f * exp(-dm * dm) * (1f - max(ndl, 0f) * 0.7f)
                // water surface caustics
                if (caustic && d > rimW) {
                    val yc = 0.70f + sin(x * 0.028f + 1.2f) * 0.012f + sin(x * 0.011f) * 0.008f
                    val q = (yy - yc) / 0.035f
                    hl += 0.07f * exp(-q * q)
                    hl += 0.05f * (yy - 0.72f).coerceIn(0f, 0.28f) / 0.28f
                }
                hl += 0.09f * (1f - yy) * (1f - yy)
                val k = 1f - dark
                fr = (fr * k + (255f - fr * k) * hl.coerceIn(0f, 1f))
                fg = (fg * k + (255f - fg * k) * hl.coerceIn(0f, 1f))
                fb = (fb * k + (255f - fb * k) * hl.coerceIn(0f, 1f))
                out[j * pw + i] = (0xFF shl 24) or (fr.toInt().coerceIn(0, 255) shl 16) or (fg.toInt().coerceIn(0, 255) shl 8) or fb.toInt().coerceIn(0, 255)
            }
        }
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, pw, 0, 0, pw, ph)
        liquid = bmp
        lShader = null
        lX = loc[0]; lY = loc[1]; lGen = g.gen; lRev = rev
        return bmp
    }

    private fun bilinear(pix: IntArray, bw: Int, bh: Int, x: Float, y: Float): Int {
        val fx = (x - 0.5f).coerceIn(0f, bw - 1f)
        val fy = (y - 0.5f).coerceIn(0f, bh - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, bw - 1); val y1 = min(y0 + 1, bh - 1)
        val tx = fx - x0; val ty = fy - y0
        val a = pix[y0 * bw + x0]; val b = pix[y0 * bw + x1]
        val c = pix[y1 * bw + x0]; val d = pix[y1 * bw + x1]
        fun ch(s: Int): Int {
            val top = ((a shr s) and 255) * (1f - tx) + ((b shr s) and 255) * tx
            val bot = ((c shr s) and 255) * (1f - tx) + ((d shr s) and 255) * tx
            return (top * (1f - ty) + bot * ty).toInt()
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Small pre-refracted copy of the blurred wallpaper region behind this panel. */
    private fun panelBitmap(g: GlassRenderer, w: Int, h: Int, prof: Int): Bitmap? {
        val pix = g.blurPix ?: return null
        val sc = g.scale
        val pw = max(4, w / sc)
        val ph = max(4, h / sc)
        val rev = app.prefs.rev
        val cur = panel
        if (cur != null && cur.width == pw && cur.height == ph && g.gen == pGen && rev == pRev) {
            val moved = abs(loc[0] - pX) + abs(loc[1] - pY)
            if (moved == 0) return cur
            // Moving panel (animation): reuse the old refraction and redo it exactly once things settle.
            if (!pForce && (moved <= 40 || SystemClock.uptimeMillis() - pAt < 90)) {
                removeCallbacks(pExact); postDelayed(pExact, 130); return cur
            }
        }
        pForce = false
        pAt = SystemClock.uptimeMillis()

        val bw = g.bw
        val bh = g.bh
        val out = IntArray(pw * ph)
        val reduced = app.prefs.bool(K.REDUCED_REFR)
        val amount = app.prefs.int(K.REFRACT) / 100f * (if (prof == 1) 0.6f else 1f) * (if (reduced) 0.5f else 1f)
        val band = max(2f, min(pw, ph) * 0.4f)
        val strengthPx = amount * min(pw, ph) * 0.45f
        val fx = (w.toFloat() / sc) / pw
        val fy = (h.toFloat() / sc) / ph
        val ox = loc[0] / sc.toFloat()
        val oy = loc[1] / sc.toFloat()
        val dispersion = prof == 2 && !reduced

        for (j in 0 until ph) {
            for (i in 0 until pw) {
                val gx0 = ox + (i + 0.5f) * fx
                val gy0 = oy + (j + 0.5f) * fy
                val dl = i + 0.5f
                val dr = pw - i - 0.5f
                val dt = j + 0.5f
                val db = ph - j - 0.5f
                val d = min(min(dl, dr), min(dt, db))
                if (d >= band || strengthPx < 0.05f) {
                    out[j * pw + i] = sample(pix, bw, bh, gx0, gy0)
                    continue
                }
                val t = 1f - d / band
                val f = t * t
                var gx = 1f / (1f + dl) - 1f / (1f + dr)
                var gy = 1f / (1f + dt) - 1f / (1f + db)
                val len = sqrt(gx * gx + gy * gy)
                if (len > 1e-4f) { gx /= len; gy /= len }
                val disp = f * strengthPx
                if (dispersion) {
                    val r = sample(pix, bw, bh, gx0 + gx * disp, gy0 + gy * disp)
                    val gg = sample(pix, bw, bh, gx0 + gx * disp * 0.93f, gy0 + gy * disp * 0.93f)
                    val b = sample(pix, bw, bh, gx0 + gx * disp * 0.86f, gy0 + gy * disp * 0.86f)
                    out[j * pw + i] = (0xFF shl 24) or (r and 0xFF0000) or (gg and 0x00FF00) or (b and 0x0000FF)
                } else {
                    out[j * pw + i] = sample(pix, bw, bh, gx0 + gx * disp, gy0 + gy * disp)
                }
            }
        }
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, pw, 0, 0, pw, ph)
        panel = bmp
        pShader = null
        pX = loc[0]; pY = loc[1]; pGen = g.gen; pRev = rev
        return bmp
    }

    private fun sample(pix: IntArray, bw: Int, bh: Int, x: Float, y: Float): Int {
        val xi = x.toInt().coerceIn(0, bw - 1)
        val yi = y.toInt().coerceIn(0, bh - 1)
        return pix[yi * bw + xi]
    }
}
