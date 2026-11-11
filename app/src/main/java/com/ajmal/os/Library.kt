package com.ajmal.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SectionIndexer
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** Slide/fade an overlay in and out with one spring. */
class OverlayAnim(
    private val view: View, private val off: Float, private val layer: ViewGroup,
    private val onGone: (() -> Unit)?
) {
    private val sp = Spring { t -> view.alpha = t; view.translationY = (1f - t) * off }
    private var closing = false

    fun show() {
        layer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sp.snap(0f)
        sp.to(1f, 260f)
    }

    fun hide() {
        if (closing) return
        closing = true
        sp.to(0f, 320f) { layer.removeView(view); onGone?.invoke() }
    }
}

/**
 * Wraps an overlay's content. A drag in direction [dir] (+1 = down, -1 = up) that starts while the
 * content is already at its scroll edge follows the finger and dismisses the overlay on release.
 * [onDismiss] returns true when the overlay is really going away (content then stays where it is).
 */
class PullLayout(
    ctx: Context, private val dir: Int,
    private val atEdge: () -> Boolean, private val onDismiss: () -> Boolean
) : FrameLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var edgeAtDown = false
    private var pulling = false
    private var vt: VelocityTracker? = null

    private fun wantsPull(e: MotionEvent): Boolean {
        if (!edgeAtDown) return false
        val d = (e.rawY - downY) * dir
        return d > slop && d > abs(e.rawX - downX) * 1.3f
    }

    private fun startPull(e: MotionEvent) {
        pulling = true
        downY = e.rawY
        lastY = e.rawY
        vt?.recycle()
        vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; edgeAtDown = atEdge(); pulling = false }
            MotionEvent.ACTION_MOVE -> if (!pulling && wantsPull(e)) { startPull(e); return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; edgeAtDown = atEdge(); pulling = false }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling) { if (wantsPull(e)) startPull(e) else return true }
                vt?.addMovement(e)
                lastY = e.rawY
                val d = ((e.rawY - downY) * dir).coerceAtLeast(0f)
                translationY = d * 0.5f * dir
                alpha = (1f - d / (420f * dp)).coerceIn(0.35f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                pulling = false
                val d = ((lastY - downY) * dir).coerceAtLeast(0f)
                var v = 0f
                vt?.let { it.computeCurrentVelocity(1000); v = it.yVelocity * dir; it.recycle() }
                vt = null
                val go = e.actionMasked == MotionEvent.ACTION_UP && (d > 90f * dp || v > 1400f)
                if (!(go && onDismiss())) animate().translationY(0f).alpha(1f).setDuration(180).start()
            }
        }
        return true
    }
}

class Row(val type: Int, val text: String, val app: AppInfo?)

class AppRowView(ctx: Context) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPx = ctx.dpi(44)
    private var row: Row? = null
    private var bmp: Bitmap? = null
    private var key = ""

    fun bind(r: Row) {
        row = r
        bmp = null
        val a = r.app
        if (a != null) {
            val k = app.icons.key(a, iconPx)
            key = k
            bmp = app.icons.peek(k)
            if (bmp == null) app.icons.request(a, k, iconPx) {
                if (key == k) { bmp = app.icons.peek(k); invalidate() }
            }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val hh = if (row?.type == 0) (30 * dp).toInt() else (60 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(w), hh)
    }

    override fun onDraw(c: Canvas) {
        val r = row ?: return
        val fg = if (app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        when (r.type) {
            0 -> {
                p.color = fg; p.alpha = 150; p.textSize = 13f * dp; p.isFakeBoldText = true
                c.drawText(r.text, 20 * dp, height - 8 * dp, p)
                p.isFakeBoldText = false
            }
            2 -> {
                p.color = fg; p.textSize = 16f * dp
                c.drawText("↺  " + r.text, 20 * dp, height / 2f + 6 * dp, p)
            }
            else -> {
                val b = bmp
                val tot = iconPx + 2 * IconCache.padFor(iconPx)
                if (b != null) c.drawBitmap(b, 14 * dp, (height - tot) / 2f, null)
                p.color = fg; p.alpha = 255; p.textSize = 17f * dp
                val tx = 14 * dp + tot + 8 * dp
                val label = TextUtils.ellipsize(r.text, TextPaint(p), width - tx - 12 * dp, TextUtils.TruncateAt.END)
                c.drawText(label.toString(), tx, height / 2f + 6 * dp, p)
            }
        }
    }
}

class RowAdapter(private val ctx: Context) : BaseAdapter(), SectionIndexer {
    var rows: List<Row> = emptyList()
    private var secNames: Array<Any> = emptyArray()
    private var secPos = IntArray(0)

    fun set(list: List<Row>, letters: Boolean) {
        rows = list
        if (letters) {
            val n = ArrayList<Any>(); val p = ArrayList<Int>()
            for ((i, r) in list.withIndex()) if (r.type == 0) { n.add(r.text); p.add(i) }
            secNames = n.toTypedArray(); secPos = p.toIntArray()
        } else { secNames = emptyArray(); secPos = IntArray(0) }
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(i: Int): Any = rows[i]
    override fun getItemId(i: Int) = i.toLong()
    override fun getViewTypeCount() = 3
    override fun getItemViewType(i: Int) = rows[i].type
    override fun isEnabled(position: Int) = rows[position].type != 0
    override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
        val v = (cv as? AppRowView) ?: AppRowView(ctx).also {
            it.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        v.bind(rows[i])
        return v
    }

    override fun getSections(): Array<Any> = secNames
    override fun getPositionForSection(sectionIndex: Int): Int =
        if (secPos.isEmpty()) 0 else secPos[sectionIndex.coerceIn(0, secPos.size - 1)]
    override fun getSectionForPosition(position: Int): Int {
        var s = 0
        for (i in secPos.indices) if (secPos[i] <= position) s = i
        return s
    }
}

fun sectionRows(list: List<AppInfo>): List<Row> {
    val out = ArrayList<Row>()
    var last = ""
    for (a in list) {
        val ch = a.label.firstOrNull()?.uppercaseChar()
        val l = if (ch != null && ch.isLetter()) ch.toString() else "#"
        if (l != last) { out.add(Row(0, l, null)); last = l }
        out.add(Row(1, a.label, a))
    }
    return out
}

class MagnifierDrawable(private val color: Int, private val px: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = color
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        p.strokeWidth = w * 0.11f
        c.drawCircle(b.left + w * 0.43f, b.top + w * 0.43f, w * 0.27f, p)
        c.drawLine(b.left + w * 0.64f, b.top + w * 0.64f, b.left + w * 0.86f, b.top + w * 0.86f, p)
    }

    override fun setAlpha(a: Int) { p.alpha = Color.alpha(color) * a / 255 }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = px
    override fun getIntrinsicHeight(): Int = px
}

private fun styleEdit(h: HomeActivity, hint: String): EditText {
    val e = EditText(h)
    e.hint = hint
    e.setSingleLine(true)
    e.inputType = InputType.TYPE_CLASS_TEXT
    e.imeOptions = EditorInfo.IME_ACTION_SEARCH
    val dark = h.app.isDark()
    val sub = if (dark) 0xB3FFFFFF.toInt() else 0xB3000000.toInt()
    e.setTextColor(if (dark) Color.WHITE else 0xFF1C1C1E.toInt())
    e.setHintTextColor(sub)
    e.background = roundBg(if (dark) 0x40FFFFFF else 0x26000000, h.dpf(22f))
    e.setPadding(h.dpi(14), h.dpi(10), h.dpi(14), h.dpi(10))
    e.compoundDrawablePadding = h.dpi(8)
    e.setCompoundDrawablesRelativeWithIntrinsicBounds(MagnifierDrawable(sub, h.dpi(17)), null, null, null)
    e.textSize = 16f
    return e
}

private fun styleList(lv: ListView) {
    lv.divider = null
    lv.setSelector(android.R.color.transparent)
    lv.cacheColorHint = 0
    lv.overScrollMode = View.OVER_SCROLL_NEVER
}

private fun hideIme(h: HomeActivity, v: View) {
    (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}

/** Shared bitmap lookup that asks the icon cache for missing icons once and repaints when they arrive. */
private class IconSource(private val v: View) {
    private val app = LauncherApp.get()
    private val pending = HashSet<String>()

    fun get(a: AppInfo, size: Int): Bitmap? {
        val k = app.icons.key(a, size)
        val b = app.icons.peek(k)
        if (b == null && pending.add(k)) app.icons.request(a, k, size) { pending.remove(k); v.invalidate() }
        return b
    }
}

/**
 * One App Library card in the style of iOS: a frosted squircle holding four app slots. The first three
 * are full-size icons; the fourth is either a fourth icon or a 2x2 cluster of mini icons that opens the
 * whole category. The category name sits under the card.
 */
class CategoryCardView(
    ctx: Context, private val h: HomeActivity, private val title: String,
    private val apps: List<AppInfo>, private val cardPx: Int,
    private val onOpen: () -> Unit, private val onLaunched: () -> Unit,
    private val locked: Boolean = false
) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val slop = Touch.hold(ctx)
    private val src = IconSource(this)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    private val pad = cardPx * 0.085f
    private val gap = cardPx * 0.075f
    private val slot = (cardPx - 2 * pad - gap) / 2f
    private val titleH = 26f * dp
    private val bigOnly = apps.size <= 4
    private var pressedSlot = -1
    private var tapSlot = -1
    private var moved = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private val lp = Runnable {
        val a = appAt(tapSlot)
        if (a != null) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            h.appActions(a)
        }
    }

    private fun appAt(s: Int): AppInfo? = when {
        s in 0..2 -> apps.getOrNull(s)
        s == 3 && bigOnly -> apps.getOrNull(3)
        else -> null
    }

    private fun slotX(i: Int) = pad + (i % 2) * (slot + gap)
    private fun slotY(i: Int) = pad + (i / 2) * (slot + gap)

    private fun hit(x: Float, y: Float): Int {
        for (i in 0..3) {
            val l = slotX(i); val t = slotY(i)
            if (x >= l && x <= l + slot && y >= t && y <= t + slot) return i
        }
        return -1
    }

    override fun onMeasure(w: Int, hh: Int) {
        setMeasuredDimension(cardPx, (cardPx + titleH).toInt())
    }

    private fun drawIcon(c: Canvas, a: AppInfo, x: Float, y: Float, box: Float, alpha: Int) {
        val size = box.toInt()
        val b = src.get(a, size) ?: return
        val pd = IconCache.padFor(size)
        bp.alpha = alpha
        c.drawBitmap(b, x + (box - size) / 2f - pd, y + (box - size) / 2f - pd, bp)
    }

    override fun onDraw(c: Canvas) {
        val dark = app.isDark()
        val fg = if (dark) Color.WHITE else 0xFF1C1C1E.toInt()
        rf.set(0f, 0f, cardPx.toFloat(), cardPx.toFloat())
        val r = cardPx * 0.2f
        bg.style = Paint.Style.FILL
        bg.color = if (dark) Color.argb(58, 255, 255, 255) else Color.argb(118, 255, 255, 255)
        c.drawPath(Squircle.path(rf, r, path), bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = dp
        bg.color = Color.argb(if (dark) 40 else 90, 255, 255, 255)
        rf.set(dp / 2f, dp / 2f, cardPx - dp / 2f, cardPx - dp / 2f)
        c.drawPath(Squircle.path(rf, r, path), bg)

        if (locked) {
            drawLock(c, fg)
            drawTitle(c, fg)
            return
        }
        val big = if (bigOnly) 4 else 3
        for (i in 0 until big) {
            val a = apps.getOrNull(i) ?: break
            drawIcon(c, a, slotX(i), slotY(i), slot, if (pressedSlot == i) 150 else 255)
        }
        if (!bigOnly) {
            val g2 = gap * 0.55f
            val mini = (slot - g2) / 2f
            val x0 = slotX(3); val y0 = slotY(3)
            for (j in 0 until 4) {
                val a = apps.getOrNull(3 + j) ?: break
                drawIcon(c, a, x0 + (j % 2) * (mini + g2), y0 + (j / 2) * (mini + g2), mini, if (pressedSlot == 3) 150 else 255)
            }
        }

        drawTitle(c, fg)
    }

    private fun drawTitle(c: Canvas, fg: Int) {
        tp.textSize = 12.5f * dp
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.color = fg
        tp.alpha = 215
        val shown = TextUtils.ellipsize(title, tp, cardPx - 10f * dp, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, 7f * dp, cardPx + titleH * 0.72f, tp)
    }

    /** Padlock glyph for the locked Hidden folder (apps inside are not revealed). */
    private fun drawLock(c: Canvas, fg: Int) {
        val s = cardPx * 0.30f
        val cx = cardPx / 2f
        val cy = cardPx / 2f + s * 0.12f
        bg.style = Paint.Style.FILL
        bg.color = fg
        bg.alpha = 230
        rf.set(cx - s * 0.5f, cy - s * 0.05f, cx + s * 0.5f, cy + s * 0.55f)
        c.drawRoundRect(rf, s * 0.14f, s * 0.14f, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = s * 0.13f
        bg.strokeCap = Paint.Cap.ROUND
        rf.set(cx - s * 0.3f, cy - s * 0.62f, cx + s * 0.3f, cy + s * 0.1f)
        c.drawArc(rf, 180f, 180f, false, bg)
        c.drawLine(cx - s * 0.3f, cy - s * 0.27f, cx - s * 0.3f, cy - s * 0.04f, bg)
        c.drawLine(cx + s * 0.3f, cy - s * 0.27f, cx + s * 0.3f, cy - s * 0.04f, bg)
        bg.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false; longFired = false
                tapSlot = if (e.y <= cardPx) hit(e.x, e.y) else -1
                pressedSlot = tapSlot
                invalidate()
                // keep the parent scroller/pager from stealing the press while it may still become a long press
                parent?.requestDisallowInterceptTouchEvent(true)
                if (appAt(tapSlot) != null) postDelayed(lp, Touch.LONG_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && !longFired && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(lp)
                    pressedSlot = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lp)
                if (!moved && !longFired) {
                    val a = appAt(tapSlot)
                    if (a != null) { h.launch(a, this); onLaunched() } else onOpen()
                }
                pressedSlot = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }
}

/** Icon grid with labels, used inside a category sheet. */
class AppGridView(
    ctx: Context, private val h: HomeActivity, private val apps: List<AppInfo>,
    private val cols: Int, iconDp: Int, private val onLaunched: () -> Unit
) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val slop = Touch.hold(ctx)
    private val src = IconSource(this)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val iconPx = (iconDp * dp).toInt()
    private val total = iconPx + 2 * IconCache.padFor(iconPx)
    private val cellH = total + 22f * dp
    private var downIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longFired = false
    private val lp = Runnable {
        val a = apps.getOrNull(downIdx)
        if (a != null) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            h.appActions(a)
        }
    }

    fun gridHeight(): Int = (ceil(apps.size / cols.toFloat()) * cellH).toInt()

    override fun onMeasure(w: Int, hh: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), gridHeight())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / cols.toFloat()
        tp.textSize = 11f * dp
        tp.textAlign = Paint.Align.CENTER
        tp.color = if (app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        for ((i, a) in apps.withIndex()) {
            val cx = (i % cols + 0.5f) * cw
            val top = (i / cols) * cellH
            val b = src.get(a, iconPx)
            if (b != null) c.drawBitmap(b, cx - total / 2f, top, bp)
            val s = TextUtils.ellipsize(a.label, tp, cw - 6f * dp, TextUtils.TruncateAt.END).toString()
            c.drawText(s, cx, top + total + 11f * dp, tp)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val cw = width / cols.toFloat()
        val i = (x / cw).toInt() + (y / cellH).toInt() * cols
        return if (i in apps.indices) i else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; longFired = false
                downIdx = indexAt(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                if (downIdx >= 0) postDelayed(lp, Touch.LONG_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!moved && !longFired && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                moved = true; removeCallbacks(lp)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lp)
                val a = apps.getOrNull(downIdx)
                if (!moved && !longFired && a != null) { h.launch(a, this); onLaunched() }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return true
    }
}

private class Group(val title: String, val display: List<AppInfo>, val all: List<AppInfo>, val locked: Boolean = false)

/**
 * App Library, as on iOS: the right-most page of the home pager (swipe left from the last page). Search bar
 * on top, 2-column grid of category cards; tapping the search bar switches to an A-Z list. The Hidden folder
 * is shown as a locked card and needs the passcode (or a fingerprint) to open.
 */
class LibraryPage(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val edit = styleEdit(h, "App Library")
    private val cancel = label(h, "Cancel", 16f, if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt())
    private val scroll = ScrollView(h)
    private val gridHost = LinearLayout(h)
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var searching = false
    private var query = ""
    private var sheet: FrameLayout? = null
    private var pad: PinPad? = null
    private var ime = 0

    init {
        view.isClickable = true
        view.isFocusableInTouchMode = true
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.5f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))

        content.orientation = LinearLayout.VERTICAL
        applyPadding()

        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        cancel.setPadding(h.dpi(14), h.dpi(8), h.dpi(2), h.dpi(8))
        cancel.visibility = View.GONE
        cancel.setOnClickListener { exitSearch() }
        head.addView(cancel)
        content.addView(head)

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        gridHost.orientation = LinearLayout.VERTICAL
        scroll.addView(gridHost, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(16) })

        styleList(list)
        list.isFastScrollEnabled = true
        list.adapter = adapter
        list.visibility = View.GONE
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(8) })
        view.addView(content, FrameLayout.LayoutParams(-1, -1))

        edit.setOnFocusChangeListener { _, f -> if (f) enterSearch() }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                if (query.isNotEmpty() && !searching) enterSearch()
                if (searching) rebuildList()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        edit.setOnEditorActionListener { _, _, _ ->
            val first = adapter.rows.firstOrNull { it.type == 1 }?.app
            if (first != null) { h.launch(first, null); reset() }
            true
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) { h.launch(a, null); reset() }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) h.appActions(a)
            a != null
        }
        buildGrid()
    }

    private fun applyPadding() {
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), if (ime > h.insetBottom) ime else h.insetBottom)
    }

    fun setBottomInset(px: Int) { ime = px; applyPadding() }

    /** Back button: close the passcode pad, then the category sheet, then search, then return to the last home page. */
    fun back() {
        when {
            pad != null -> pad?.dismiss()
            sheet != null -> closeSheet()
            searching -> exitSearch()
            else -> close()
        }
    }

    fun close() {
        hideIme(h, edit)
        h.pager.goTo(h.pager.pages.size - 1)
    }

    /** Back to the plain grid without navigating (after launching an app / pressing Home). */
    fun reset() {
        pad?.dismiss()
        sheet?.let { view.removeView(it) }
        sheet = null
        if (searching) exitSearch()
        scroll.scrollTo(0, 0)
    }

    fun reload() {
        buildGrid()
        if (searching) rebuildList()
    }

    private fun enterSearch() {
        if (searching) return
        searching = true
        scroll.visibility = View.GONE
        list.visibility = View.VISIBLE
        cancel.visibility = View.VISIBLE
        rebuildList()
    }

    private fun exitSearch() {
        searching = false
        edit.setText("")
        hideIme(h, edit)
        view.requestFocus()
        list.visibility = View.GONE
        cancel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
    }

    private fun rebuildList() {
        val q = query.trim().lowercase()
        val hidden = h.model.hiddenApps
        val all = h.model.apps.filter { !hidden.contains(it.key) } // hidden apps never show up in search
        adapter.set(sectionRows(if (q.isEmpty()) all else all.filter { it.lower.contains(q) }), true)
    }

    private fun buildGrid() {
        gridHost.removeAllViews()
        val m = h.model
        val hidden = m.hiddenApps
        val vis = m.apps.filter { !hidden.contains(it.key) }
        val byUse = compareByDescending<AppInfo> { m.usage[it.key] ?: 0 }.thenBy { it.lower }

        val groups = ArrayList<Group>()
        val freq = m.frequent().filter { !hidden.contains(it.key) }
        if (freq.isNotEmpty()) groups.add(Group("Suggestions", freq.take(4), freq))
        val rec = vis.sortedByDescending { it.installTime }.take(15)
        if (rec.isNotEmpty()) groups.add(Group("Recently Added", rec.take(4), rec))
        for (cat in m.categories()) {
            val l = vis.filter { m.categoryOf(it) == cat }.sortedWith(byUse)
            if (l.isNotEmpty()) groups.add(Group(cat, l, l.sortedBy { it.lower }))
        }
        // Hidden folder: always present, locked, contents never drawn on the card.
        val hid = m.apps.filter { hidden.contains(it.key) }
        groups.add(Group("Hidden", emptyList(), hid.sortedBy { it.lower }, true))

        val dm = h.resources.displayMetrics
        val gap = h.dpi(14)
        val cw = (dm.widthPixels - h.dpi(32) - gap) / 2
        for (pair in groups.chunked(2)) {
            val row = LinearLayout(h)
            row.orientation = LinearLayout.HORIZONTAL
            for ((i, g) in pair.withIndex()) {
                val open: () -> Unit = if (g.locked) ({ unlockHidden() }) else ({ openSheet(g.title, g.all) })
                val card = CategoryCardView(h, h, g.title, g.display, cw, open, { reset() }, g.locked)
                val lp = LinearLayout.LayoutParams(cw, -2)
                if (i == 0) lp.rightMargin = gap
                row.addView(card, lp)
            }
            gridHost.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = h.dpi(6) })
        }
        gridHost.addView(View(h), LinearLayout.LayoutParams(-1, h.dpi(24)))
    }

    private fun unlockHidden() {
        if (pad != null || sheet != null) return
        hideIme(h, edit)
        val p = PinPad(h, { openSheet("Hidden", h.model.apps.filter { h.model.hiddenApps.contains(it.key) }.sortedBy { it.lower }, true) }, { pad = null })
        pad = p
        view.addView(p, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSheet(title: String, apps: List<AppInfo>, isHidden: Boolean = false) {
        if (sheet != null) return
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        val dm = h.resources.displayMetrics
        val layer = FrameLayout(h)
        layer.setBackgroundColor(0x66000000)
        layer.setOnClickListener { closeSheet() }

        val panel = GlassFrame(h)
        panel.radiusDp = 30f
        panel.strength = 1.3f
        val col = LinearLayout(h)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(h.dpi(16), h.dpi(18), h.dpi(16), h.dpi(12))
        val t = label(h, title, 20f, fg)
        t.setTypeface(t.typeface, Typeface.BOLD)
        col.addView(t)
        if (apps.isEmpty()) {
            val hint = label(h, if (isHidden) "No hidden apps yet.\nPress and hold any app and choose “Hide App” to put it here." else "Empty", 14f, fg)
            hint.alpha = 0.7f
            col.addView(hint, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = h.dpi(14); it.bottomMargin = h.dpi(10) })
        } else {
            val grid = AppGridView(h, h, apps, 4, 56) { reset() }
            val sv = ScrollView(h)
            sv.isVerticalScrollBarEnabled = false
            sv.overScrollMode = View.OVER_SCROLL_NEVER
            sv.addView(grid, FrameLayout.LayoutParams(-1, -2))
            val maxH = (dm.heightPixels * 0.6f).toInt()
            col.addView(sv, LinearLayout.LayoutParams(-1, min(grid.gridHeight(), maxH)).also { it.topMargin = h.dpi(10) })
        }
        panel.addView(col, FrameLayout.LayoutParams(-1, -2))
        layer.addView(panel, FrameLayout.LayoutParams(dm.widthPixels - h.dpi(32), -2, Gravity.CENTER))
        view.addView(layer, FrameLayout.LayoutParams(-1, -1))

        layer.alpha = 0f
        panel.scaleX = 0.92f; panel.scaleY = 0.92f
        layer.animate().alpha(1f).setDuration(170).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        sheet = layer
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(140).withEndAction { view.removeView(s) }.start()
    }
}

/** Lightweight local search: instant filtering by name, recents, usage-based suggestions. Pull up to close. */
class SearchOverlay(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val edit = styleEdit(h, "Search")
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var query = ""
    private val pull = PullLayout(h, -1, { !list.canScrollVertically(1) }, { close(); true })
    private val anim = OverlayAnim(view, -h.dpf(40f), h.overlayLayer) { h.searchGone() }

    init {
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.5f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), h.insetBottom)
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = label(h, "Cancel", 16f, fg)
        clear.setPadding(h.dpi(14), h.dpi(8), h.dpi(2), h.dpi(8))
        clear.setOnClickListener { if (query.isNotEmpty()) edit.setText("") else close() }
        head.addView(clear)
        content.addView(head)
        styleList(list)
        list.adapter = adapter
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(8) })
        pull.addView(content, FrameLayout.LayoutParams(-1, -1))
        view.addView(pull, FrameLayout.LayoutParams(-1, -1))

        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        edit.setOnEditorActionListener { _, _, _ ->
            val first = adapter.rows.firstOrNull { it.type == 1 }?.app
            if (first != null) pick(first)
            true
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val r = adapter.rows[i]
            if (r.type == 2) edit.setText(r.text).also { edit.setSelection(edit.text.length) }
            else if (r.app != null) pick(r.app)
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) h.appActions(a)
            a != null
        }
        rebuild()
    }

    private fun pick(a: AppInfo) {
        h.model.noteSearch(query)
        h.launch(a, null)
        close()
    }

    fun show() {
        anim.show()
        edit.postDelayed({
            edit.requestFocus()
            (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    fun close() {
        hideIme(h, edit)
        anim.hide()
    }

    fun setBottomInset(px: Int) {
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), if (px > h.insetBottom) px else h.insetBottom)
    }

    private fun rebuild() {
        val m = h.model
        val q = query.trim().lowercase()
        val out = ArrayList<Row>()
        if (q.isEmpty()) {
            val fr = m.frequent().filter { !m.hiddenApps.contains(it.key) }.take(6)
            if (fr.isNotEmpty()) {
                out.add(Row(0, "SUGGESTIONS", null))
                for (a in fr) out.add(Row(1, a.label, a))
            }
            if (m.searches.isNotEmpty()) {
                out.add(Row(0, "RECENT SEARCHES", null))
                for (s in m.searches) out.add(Row(2, s, null))
            }
        } else {
            val hits = m.apps.filter { it.lower.contains(q) && !m.hiddenApps.contains(it.key) }
                .sortedWith(compareBy<AppInfo>({ if (it.lower.startsWith(q)) 0 else 1 }, { -(m.usage[it.key] ?: 0) }, { it.lower }))
            for (a in hits.take(60)) out.add(Row(1, a.label, a))
        }
        adapter.set(out, false)
    }
}
