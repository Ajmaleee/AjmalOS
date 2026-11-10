package com.ajmal.os

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface IconHost {
    val editing: Boolean
    fun onTap(v: View)
    fun onLongPress(v: View)
    fun onRemoveBadge(v: View)
    fun canDrag(v: View): Boolean
    fun dragStart(v: View, rx: Float, ry: Float)
    fun dragMove(rx: Float, ry: Float)
    fun dragEnd(rx: Float, ry: Float, cancel: Boolean)
}

/** A single home/dock/folder icon, drawn directly on a Canvas (no child views, no TextView). */
class AppIconView(ctx: Context, private val host: IconHost) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val spring = Spring { v -> scaleX = v; scaleY = v }

    var item: Item? = null
        private set
    private var appInfo: AppInfo? = null
    private var iconBmp: Bitmap? = null
    private var iconKey = ""
    private var s = 0
    private var pad = 0
    private var showLabel = true
    private var labelText = ""
    private var shown = ""
    private var labelPx = 0f
    private var kids: List<AppInfo> = emptyList()
    private var kidKeys: Array<String> = emptyArray()
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var dragging = false
    private var moved = false
    private val lp = Runnable { fireLong() }

    init { spring.snap(1f) }

    fun setHot(hot: Boolean) { spring.to(if (hot) 1.15f else 1f, 500f) }

    fun bind(m: Item, a: AppInfo?, size: Int, label: Boolean) {
        item = m
        tag = m
        appInfo = a
        s = size
        pad = IconCache.padFor(size)
        showLabel = label
        labelText = if (m.type == Item.FOLDER) m.title.ifEmpty { "Folder" } else if (m.title.isNotEmpty()) m.title else (a?.label ?: "")
        labelPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, app.prefs.int(K.LABEL_SIZE).toFloat(), resources.displayMetrics
        )
        alpha = 1f
        rotation = 0f
        spring.snap(1f)
        shown = ""
        if (m.type == Item.FOLDER) bindFolder(m) else loadIcon()
        invalidate()
    }

    private fun bindFolder(m: Item) {
        val list = ArrayList<AppInfo>()
        for (c in app.model.children(m.id)) app.model.byKey[c.key]?.let { list.add(it) }
        kids = list
        kidKeys = Array(min(9, list.size)) { i ->
            val k = app.icons.key(list[i], s)
            if (app.icons.peek(k) == null) app.icons.request(list[i], k, s) { invalidate() }
            k
        }
    }

    private fun loadIcon() {
        val a = appInfo ?: return
        val k = app.icons.key(a, s)
        iconKey = k
        iconBmp = app.icons.peek(k)
        if (iconBmp == null) {
            app.icons.request(a, k, s) {
                if (iconKey == k) { iconBmp = app.icons.peek(k); invalidate() }
            }
        }
    }

    private fun labelColor(): Int = when (app.prefs.int(K.LABEL_COLOR)) {
        1 -> Color.WHITE
        2 -> Color.BLACK
        else -> if (app.glass.avgLum > 0.78f && app.glass.blurred != null) 0xFF111111.toInt() else Color.WHITE
    }

    private fun cfg(): TextPaint {
        val p = TP
        p.textSize = labelPx
        val c = labelColor()
        p.color = c
        p.textAlign = Paint.Align.CENTER
        if (c == Color.WHITE) p.setShadowLayer(2f * dp, 0f, dp, 0x80000000.toInt()) else p.clearShadowLayer()
        return p
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shown = ""
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        val tot = s + 2 * pad
        val tp = cfg()
        val labelH = if (showLabel) labelPx * 1.45f else 0f
        val top = max(0f, (h - tot - labelH) / 2f)
        val left = (w - tot) / 2f
        val m = item ?: return

        if (m.type == Item.FOLDER) {
            drawFolder(c, left + pad, top + pad)
        } else {
            val b = iconBmp
            if (b != null) c.drawBitmap(b, left, top, null)
            else {
                fp.style = Paint.Style.FILL
                fp.color = 0x33808080
                rf.set(left + pad, top + pad, left + pad + s, top + pad + s)
                val r = s * app.prefs.int(K.ICON_RADIUS) / 100f
                c.drawRoundRect(rf, r, r, fp)
            }
        }
        if (showLabel) {
            if (shown.isEmpty()) shown = TextUtils.ellipsize(labelText, tp, w - 6f * dp, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, w / 2f, top + pad + s + labelPx * 1.15f + 3f * dp, tp)
        }
        if (host.editing) {
            val cx = left + pad
            val cy = top + pad
            fp.style = Paint.Style.FILL
            fp.color = 0xDD3A3A3C.toInt()
            c.drawCircle(cx, cy, 9f * dp, fp)
            fp.color = Color.WHITE
            c.drawRect(cx - 4.5f * dp, cy - 1f * dp, cx + 4.5f * dp, cy + 1f * dp, fp)
        }
    }

    private fun drawFolder(c: Canvas, l: Float, t: Float) {
        val r = s * app.prefs.int(K.ICON_RADIUS) / 100f
        rf.set(l, t, l + s, t + s)
        fp.style = Paint.Style.FILL
        fp.shader = null
        fp.color = if (app.isDark()) Color.argb(95, 255, 255, 255) else Color.argb(120, 255, 255, 255)
        c.drawRoundRect(rf, r, r, fp)
        fp.style = Paint.Style.STROKE
        fp.strokeWidth = max(1f, s * 0.012f)
        fp.color = Color.argb(90, 255, 255, 255)
        c.drawRoundRect(rf, r, r, fp)
        val p2 = s * 0.09f
        val cell = (s - 2 * p2) / 3f
        val sz = cell * 1.1f
        val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in kidKeys.indices) {
            val b = app.icons.peek(kidKeys[i]) ?: continue
            val cx = l + p2 + (i % 3) * cell + cell / 2f
            val cy = t + p2 + (i / 3) * cell + cell / 2f
            rf.set(cx - sz / 2f, cy - sz / 2f, cx + sz / 2f, cy + sz / 2f)
            c.drawBitmap(b, null, rf, bp)
        }
    }

    fun snapshot(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val sx = scaleX
        scaleX = 1f; scaleY = 1f
        draw(c)
        scaleX = sx; scaleY = sx
        return b
    }

    private fun fireLong() {
        longFired = true
        if (app.prefs.bool(K.HAPTIC)) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        host.onLongPress(this)
    }

    private fun release() {
        if (app.prefs.bool(K.ICON_ANIM) || scaleX != 1f) spring.to(1f, 700f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                longFired = false; dragging = false; moved = false
                if (app.prefs.bool(K.ICON_ANIM)) spring.to(0.95f, 700f)
                if (host.editing) parent?.requestDisallowInterceptTouchEvent(true)
                else postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) { host.dragMove(e.rawX, e.rawY); return true }
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dx * dx + dy * dy > slop * slop) {
                    moved = true
                    if (host.editing || longFired) {
                        if (host.canDrag(this)) {
                            removeCallbacks(lp)
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            host.dragStart(this, e.rawX, e.rawY)
                        }
                    } else {
                        removeCallbacks(lp)
                        release()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lp)
                if (dragging) {
                    dragging = false
                    host.dragEnd(e.rawX, e.rawY, false)
                    release()
                    return true
                }
                release()
                if (!moved && !longFired) {
                    if (host.editing) {
                        val tot = s + 2 * pad
                        val bx = (width - tot) / 2f + pad
                        val by = max(0f, (height - tot - (if (showLabel) labelPx * 1.45f else 0f)) / 2f) + pad
                        if (abs(e.x - bx) < 16f * dp && abs(e.y - by) < 16f * dp) host.onRemoveBadge(this)
                    } else host.onTap(this)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                if (dragging) { dragging = false; host.dragEnd(e.rawX, e.rawY, true) }
                release()
                return true
            }
        }
        return true
    }

    companion object {
        private val TP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Grid container. Children carry their Item in `tag`; positions come from the Item, not LayoutParams. */
class CellLayout(ctx: Context) : ViewGroup(ctx) {
    var cols = 4
    var rows = 6
    var padH = 0
    var padV = 0
    /** Flow mode (folders): ignore item x/y and place children left-to-right by child index. */
    var flow = false
    private var hint: IntArray? = null
    private val hp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hr = RectF()
    private val dp = ctx.resources.displayMetrics.density

    init { clipChildren = false }

    fun cellW(): Float = (width - 2 * padH) / cols.toFloat()
    fun cellH(): Float = (height - 2 * padV) / rows.toFloat()

    fun cellAt(px: Float, py: Float): IntArray {
        val x = ((px - padH) / cellW()).toInt().coerceIn(0, cols - 1)
        val y = ((py - padV) / cellH()).toInt().coerceIn(0, rows - 1)
        return intArrayOf(x, y)
    }

    fun setHint(x: Int, y: Int, sx: Int, sy: Int) {
        val h = hint
        if (h != null && h[0] == x && h[1] == y && h[2] == sx && h[3] == sy) return
        hint = intArrayOf(x, y, sx, sy)
        invalidate()
    }

    fun clearHint() {
        if (hint != null) { hint = null; invalidate() }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val cw = (w - 2 * padH) / cols
        val ch = (h - 2 * padV) / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val m = v.tag as? Item ?: continue
            val sx = if (flow) 1 else m.sx
            val sy = if (flow) 1 else m.sy
            v.measure(
                MeasureSpec.makeMeasureSpec(max(1, cw * sx), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(1, ch * sy), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l - 2 * padH) / cols
        val ch = (b - t - 2 * padV) / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val m = v.tag as? Item ?: continue
            val gx = if (flow) i % cols else m.x
            val gy = if (flow) i / cols else m.y
            val sx = if (flow) 1 else m.sx
            val sy = if (flow) 1 else m.sy
            val x = padH + gx * cw
            val y = padV + gy * ch
            v.layout(x, y, x + cw * sx, y + ch * sy)
        }
    }

    override fun dispatchDraw(c: Canvas) {
        val h = hint
        if (h != null) {
            val cw = cellW()
            val ch = cellH()
            hr.set(padH + h[0] * cw + 3 * dp, padV + h[1] * ch + 3 * dp, padH + (h[0] + h[2]) * cw - 3 * dp, padV + (h[1] + h[3]) * ch - 3 * dp)
            hp.color = Color.argb(50, 255, 255, 255)
            c.drawRoundRect(hr, 16 * dp, 16 * dp, hp)
        }
        super.dispatchDraw(c)
    }
}

class PageDots(ctx: Context) : View(ctx) {
    var count = 1
        set(v) { field = v; invalidate() }
    var pos = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        if (count <= 1) return
        val gap = 13f * dp
        val x0 = (width - (count - 1) * gap) / 2f
        val cy = height / 2f
        p.setShadowLayer(2f * dp, 0f, 0f, 0x55000000)
        for (i in 0 until count) {
            val near = 1f - min(1f, abs(i - pos))
            p.color = Color.argb((90 + 165 * near).toInt(), 255, 255, 255)
            c.drawCircle(x0 + i * gap, cy, (3f + 1.2f * near) * dp, p)
        }
    }
}

/** Glass dock with an optional swipe-up gesture. */
class DockFrame(ctx: Context) : GlassFrame(ctx) {
    var onSwipeUp: (() -> Unit)? = null
    private var sy = 0f
    private var sx = 0f
    private var fired = false
    private val dp = ctx.resources.displayMetrics.density

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sy = e.y; sx = e.x; fired = false }
            MotionEvent.ACTION_MOVE -> {
                val cb = onSwipeUp
                if (cb != null && !fired && sy - e.y > 36f * dp && abs(e.x - sx) < abs(sy - e.y)) {
                    fired = true
                    cb()
                }
            }
        }
        return false
    }
}

/** Wraps a real AppWidgetHostView; adds long-press + drag without stealing normal widget touches. */
class WidgetFrame(ctx: Context, private val host: IconHost) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val dp = ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var dragging = false
    private val lp = Runnable {
        longFired = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        host.onLongPress(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val hv = getChildAt(0) as? android.appwidget.AppWidgetHostView ?: return
        val wd = (w / dp).toInt()
        val hd = (h / dp).toInt()
        try { hv.updateAppWidgetSize(null, wd, hd, wd, hd) } catch (t: Throwable) { }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; longFired = false; dragging = false
                if (!host.editing) postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!longFired && dx * dx + dy * dy > slop * slop) removeCallbacks(lp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return longFired || host.editing
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) host.dragMove(e.rawX, e.rawY)
                else {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dx * dx + dy * dy > slop * slop && (host.editing || longFired) && host.canDrag(this)) {
                        dragging = true
                        host.dragStart(this, e.rawX, e.rawY)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                if (dragging) { dragging = false; host.dragEnd(e.rawX, e.rawY, e.actionMasked == MotionEvent.ACTION_CANCEL) }
                else if (host.editing && !longFired && e.actionMasked == MotionEvent.ACTION_UP) host.onLongPress(this)
                longFired = false
            }
        }
        return true
    }

    fun snapshot(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(b))
        return b
    }
}
