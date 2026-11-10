package com.ajmal.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextView

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
        val hh = if (row?.type == 0) (30 * dp).toInt() else (62 * dp).toInt()
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
                val label = TextUtils.ellipsize(r.text, android.text.TextPaint(p), width - tx - 12 * dp, TextUtils.TruncateAt.END)
                c.drawText(label.toString(), tx, height / 2f - 2 * dp, p)
                p.alpha = 120; p.textSize = 12f * dp
                val pk = TextUtils.ellipsize(r.app?.pkg ?: "", android.text.TextPaint(p), width - tx - 12 * dp, TextUtils.TruncateAt.END)
                c.drawText(pk.toString(), tx, height / 2f + 16 * dp, p)
                p.alpha = 255
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

private fun styleEdit(h: HomeActivity, hint: String): EditText {
    val e = EditText(h)
    e.hint = hint
    e.setSingleLine(true)
    e.inputType = InputType.TYPE_CLASS_TEXT
    e.imeOptions = EditorInfo.IME_ACTION_SEARCH
    val dark = h.app.isDark()
    e.setTextColor(if (dark) Color.WHITE else 0xFF1C1C1E.toInt())
    e.setHintTextColor(if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt())
    e.background = roundBg(if (dark) 0x33FFFFFF else 0x22000000, h.dpf(14f))
    e.setPadding(h.dpi(14), h.dpi(10), h.dpi(14), h.dpi(10))
    e.textSize = 16f
    return e
}

private fun styleList(h: HomeActivity, lv: ListView) {
    lv.divider = null
    lv.setSelector(android.R.color.transparent)
    lv.cacheColorHint = 0
    lv.overScrollMode = View.OVER_SCROLL_NEVER
}

/** App Library: categories, favourites, recents, frequent, hidden, A–Z with fast scroll, search. */
class LibraryOverlay(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val chips = LinearLayout(h)
    private val edit = styleEdit(h, "Search apps")
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var mode = 0
    private var query = ""
    private var cats: List<String> = emptyList()
    private val anim = OverlayAnim(view, h.dpf(40f), h.overlayLayer) { h.libraryGone() }

    init {
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.7f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(h.dpi(12), h.insetTop + h.dpi(14), h.dpi(12), h.insetBottom)
        val dark = h.app.isDark()
        val fg = if (dark) Color.WHITE else 0xFF1C1C1E.toInt()
        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        val title = label(h, "App Library", 26f, fg)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = label(h, "✕", 22f, fg)
        close.setPadding(h.dpi(12), h.dpi(6), h.dpi(12), h.dpi(6))
        close.setOnClickListener { close() }
        head.addView(close)
        content.addView(head)
        content.addView(edit, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = h.dpi(8) })
        val hs = HorizontalScrollView(h)
        hs.isHorizontalScrollBarEnabled = false
        chips.orientation = LinearLayout.HORIZONTAL
        hs.addView(chips)
        content.addView(hs, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = h.dpi(8); it.bottomMargin = h.dpi(4) })
        styleList(h, list)
        list.isFastScrollEnabled = true
        list.adapter = adapter
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(content, FrameLayout.LayoutParams(-1, -1))

        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) { h.launch(a, null); close() }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) h.appActions(a)
            a != null
        }
        buildChips()
        rebuild()
    }

    fun show() = anim.show()

    fun close() {
        (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        anim.hide()
    }

    fun setBottomInset(px: Int) { content.setPadding(h.dpi(12), h.insetTop + h.dpi(14), h.dpi(12), max0(px, h.insetBottom)) }
    private fun max0(a: Int, b: Int) = if (a > b) a else b

    fun reload() { buildChips(); rebuild() }

    private fun buildChips() {
        cats = h.model.categories()
        chips.removeAllViews()
        val names = ArrayList<String>()
        names.add("All"); names.add("Favorites"); names.add("Recent"); names.add("Frequent")
        names.addAll(cats); names.add("Hidden")
        val dark = h.app.isDark()
        val fg = if (dark) Color.WHITE else 0xFF1C1C1E.toInt()
        for ((i, n) in names.withIndex()) {
            val t = label(h, n, 14f, fg)
            t.setPadding(h.dpi(14), h.dpi(7), h.dpi(14), h.dpi(7))
            t.background = roundBg(if (i == mode) 0x55FFFFFF else 0x22FFFFFF, h.dpf(16f))
            t.setOnClickListener { mode = i; buildChips(); rebuild() }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = h.dpi(6)
            chips.addView(t, lp)
        }
    }

    private fun rebuild() {
        val m = h.model
        val q = query.trim().lowercase()
        val all = m.apps
        var flat = false
        var src: List<AppInfo> = when {
            mode == 0 -> all
            mode == 1 -> all.filter { m.favorites.contains(it.key) }
            mode == 2 -> { flat = true; m.recent() }
            mode == 3 -> { flat = true; m.frequent() }
            mode >= 4 && mode - 4 < cats.size -> all.filter { m.categoryOf(it) == cats[mode - 4] }
            else -> all.filter { m.hiddenApps.contains(it.key) }
        }
        if (q.isNotEmpty()) src = src.filter { it.lower.contains(q) || it.pkg.lowercase().contains(q) }
        if (flat) adapter.set(src.map { Row(1, it.label, it) }, false)
        else adapter.set(sectionRows(src), true)
    }
}

/** Lightweight local search: instant filtering by name or package, recents, usage-based suggestions. */
class SearchOverlay(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val edit = styleEdit(h, "Search")
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var query = ""
    private val anim = OverlayAnim(view, -h.dpf(40f), h.overlayLayer) { h.searchGone() }

    init {
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.7f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(h.dpi(12), h.insetTop + h.dpi(14), h.dpi(12), h.insetBottom)
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = label(h, "Cancel", 16f, fg)
        clear.setPadding(h.dpi(14), h.dpi(8), h.dpi(6), h.dpi(8))
        clear.setOnClickListener { if (query.isNotEmpty()) edit.setText("") else close() }
        head.addView(clear)
        content.addView(head)
        styleList(h, list)
        list.adapter = adapter
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(8) })
        view.addView(content, FrameLayout.LayoutParams(-1, -1))

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
        (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        anim.hide()
    }

    fun setBottomInset(px: Int) { content.setPadding(h.dpi(12), h.insetTop + h.dpi(14), h.dpi(12), if (px > h.insetBottom) px else h.insetBottom) }

    private fun rebuild() {
        val m = h.model
        val q = query.trim().lowercase()
        val out = ArrayList<Row>()
        if (q.isEmpty()) {
            val fr = m.frequent().take(6)
            if (fr.isNotEmpty()) {
                out.add(Row(0, "SUGGESTIONS", null))
                for (a in fr) out.add(Row(1, a.label, a))
            }
            if (m.searches.isNotEmpty()) {
                out.add(Row(0, "RECENT SEARCHES", null))
                for (s in m.searches) out.add(Row(2, s, null))
            }
        } else {
            val hits = m.apps.filter { it.lower.contains(q) || it.pkg.lowercase().contains(q) }
                .sortedWith(compareBy<AppInfo>({ if (it.lower.startsWith(q)) 0 else 1 }, { -(m.usage[it.key] ?: 0) }, { it.lower }))
            for (a in hits.take(60)) out.add(Row(1, a.label, a))
        }
        adapter.set(out, false)
    }
}
