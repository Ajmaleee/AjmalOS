package com.ajmal.os

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min

/**
 * Drag-and-drop for apps, folders and widgets. The dragged View stays in place (hidden) and keeps
 * receiving the touch stream; a bitmap proxy follows the finger on the top layer.
 * Hover over an app for ~420 ms to create a folder / add to a folder.
 */
class DragController(private val h: HomeActivity) {
    class Target {
        var kind = NONE
        var page = 0
        var x = 0
        var y = 0
        var other: Item? = null
    }

    var active = false
        private set
    private var item: Item? = null
    val draggedItem: Item? get() = item
    private var proxy: ImageView? = null
    private var grabX = 0f
    private var grabY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dwellId = -1L
    private var dwellReady = false
    private var edgeDir = 0
    private val handler = Handler(Looper.getMainLooper())
    private val lift = Spring { s -> proxy?.let { it.scaleX = s; it.scaleY = s } }
    private val dwellRun = Runnable { dwellReady = true; update(lastX, lastY) }
    private val edgeRun = Runnable { edgeSwitch() }

    fun start(v: View, rx: Float, ry: Float) {
        val m = v.tag as? Item ?: return
        val bmp: Bitmap = (when (v) {
            is AppIconView -> v.snapshot()
            is WidgetFrame -> v.snapshot()
            else -> null
        }) ?: return
        item = m
        active = true
        dwellReady = false
        dwellId = -1L
        edgeDir = 0
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        grabX = rx - loc[0]
        grabY = ry - loc[1]
        val p = ImageView(h)
        p.setImageBitmap(bmp)
        p.layoutParams = FrameLayout.LayoutParams(bmp.width, bmp.height)
        p.translationX = loc[0].toFloat()
        p.translationY = loc[1].toFloat()
        h.dragLayer.addView(p)
        proxy = p
        v.alpha = 0f
        lift.snap(1f)
        lift.to(1.07f, 600f)
        h.onDragBegan(m)
        update(rx, ry)
    }

    fun move(rx: Float, ry: Float) { if (active) update(rx, ry) }

    private fun update(rx: Float, ry: Float) {
        val p = proxy ?: return
        val m = item ?: return
        lastX = rx
        lastY = ry
        p.translationX = rx - grabX
        p.translationY = ry - grabY
        val t = compute(rx, ry)
        h.showHint(t, m)
        val edge = 36f * h.den
        val dir = if (t.kind == DOCK) 0 else if (rx < edge) -1 else if (rx > h.screenW - edge) 1 else 0
        if (dir != edgeDir) {
            edgeDir = dir
            handler.removeCallbacks(edgeRun)
            if (dir != 0) handler.postDelayed(edgeRun, 650)
        }
    }

    private fun edgeSwitch() {
        if (!active) return
        val dir = edgeDir
        if (dir == 0) return
        val next = h.pager.index + dir
        if (next >= 0 && next < h.pager.pages.size) h.pager.goTo(next)
        edgeDir = 0
        handler.postDelayed({ if (active) update(lastX, lastY) }, 350)
    }

    private fun dwellOn(o: Item?): Boolean {
        val id = o?.id ?: -1L
        if (id != dwellId) {
            dwellId = id
            dwellReady = false
            handler.removeCallbacks(dwellRun)
            if (id != -1L) handler.postDelayed(dwellRun, 420)
        }
        return dwellReady && id != -1L
    }

    private fun compute(rx: Float, ry: Float): Target {
        val m = item!!
        val t = Target()
        val dr = h.dockRect()
        if (m.type != Item.WIDGET && dr != null && ry >= dr.top - 10f * h.den) {
            val n = h.dockCount
            val dl = h.dockLayout
            val loc = IntArray(2)
            dl.getLocationOnScreen(loc)
            val cw = (dl.width - 2 * dl.padH) / n.toFloat()
            val idx = ((rx - loc[0] - dl.padH) / cw).toInt().coerceIn(0, n - 1)
            val o = h.model.dockItems().firstOrNull { it !== m && it.x == idx }
            t.kind = DOCK
            t.x = idx
            t.other = o
            val ready = dwellOn(o)
            if (o != null && ready && o.id != m.parent) {
                if (m.type == Item.APP) t.kind = if (o.type == Item.FOLDER) FOLDER_ADD else if (o.type == Item.APP) FOLDER_NEW else DOCK
            }
            return t
        }
        val page = h.pager.currentPage() ?: return t
        val loc = IntArray(2)
        page.getLocationOnScreen(loc)
        val cell = page.cellAt(rx - loc[0], ry - loc[1])
        val pid = h.currentPageId()
        val cols = page.cols
        val rows = page.rows
        t.page = pid
        if (m.type == Item.WIDGET) {
            val cx = min(cell[0], cols - m.sx)
            val cy = min(cell[1], rows - m.sy)
            val g = h.model.occupancy(pid, cols, rows, m)
            if (h.model.fits(g, cx, cy, m.sx, m.sy)) { t.kind = CELL; t.x = cx; t.y = cy }
            dwellOn(null)
            return t
        }
        val o = h.model.itemAt(pid, cell[0], cell[1], m)
        t.x = cell[0]
        t.y = cell[1]
        t.other = o
        if (o == null) { dwellOn(null); t.kind = CELL; return t }
        if (o.type == Item.WIDGET) { dwellOn(null); t.kind = NONE; return t }
        val ready = dwellOn(o)
        t.kind = CELL
        if (ready && m.type == Item.APP) t.kind = if (o.type == Item.FOLDER) FOLDER_ADD else FOLDER_NEW
        return t
    }

    private fun apply(t: Target, m: Item): Boolean {
        val md = h.model
        when (t.kind) {
            CELL -> {
                if (m.type == Item.WIDGET) {
                    m.container = Item.HOME; m.page = t.page; m.x = t.x; m.y = t.y
                    return true
                }
                val o = t.other
                if (o != null) {
                    val f = md.findFreeNear(t.page, h.cols, h.rows, t.x, t.y, m) ?: return false
                    o.x = f[0]; o.y = f[1]
                }
                m.container = Item.HOME; m.parent = 0; m.page = t.page; m.x = t.x; m.y = t.y
                return true
            }
            FOLDER_NEW -> {
                val o = t.other ?: return false
                val f = md.newItem(Item.FOLDER)
                f.title = "Folder"
                f.container = o.container; f.page = o.page; f.x = o.x; f.y = o.y
                md.items.add(f)
                o.container = Item.IN_FOLDER; o.parent = f.id; o.x = 0
                m.container = Item.IN_FOLDER; m.parent = f.id; m.x = 1
                return true
            }
            FOLDER_ADD -> {
                val o = t.other ?: return false
                if (m.container == Item.IN_FOLDER && m.parent == o.id) return false
                val n = md.children(o.id).size
                m.container = Item.IN_FOLDER; m.parent = o.id; m.x = n
                return true
            }
            DOCK -> {
                val list = ArrayList(md.dockItems().filter { it !== m })
                if (list.size >= h.dockCount) return false
                val idx = t.x.coerceAtMost(list.size)
                m.container = Item.DOCK; m.parent = 0
                list.add(idx, m)
                for ((i, d) in list.withIndex()) d.x = i
                return true
            }
        }
        return false
    }

    fun end(rx: Float, ry: Float, cancel: Boolean) {
        if (!active) return
        handler.removeCallbacks(dwellRun)
        handler.removeCallbacks(edgeRun)
        val m = item ?: return
        val p = proxy ?: return
        var ok = false
        if (!cancel) {
            val t = compute(rx, ry)
            if (t.kind != NONE) ok = apply(t, m)
        }
        active = false
        h.clearHints()
        if (ok) {
            h.model.tidy()
            h.model.save()
        }
        item = null
        proxy = null
        h.finishDrag(m, p, ok)
    }

    /** Animates the proxy into the destination view (or fades it), then removes it. */
    fun land(p: View, dest: View?) {
        val layer = h.dragLayer
        fun done() {
            layer.removeView(p)
            dest?.alpha = 1f
        }
        if (dest == null) {
            val s = Spring { v -> p.alpha = v }
            s.snap(1f)
            s.to(0f, 400f) { layer.removeView(p) }
            return
        }
        dest.alpha = 0f
        var started = false
        val l = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                dest.viewTreeObserver.removeOnPreDrawListener(this)
                if (started) return true
                started = true
                val loc = IntArray(2)
                dest.getLocationOnScreen(loc)
                val x0 = p.translationX
                val y0 = p.translationY
                val s0 = p.scaleX
                val sp = Spring { u ->
                    p.translationX = x0 + (loc[0] - x0) * u
                    p.translationY = y0 + (loc[1] - y0) * u
                    val sc = s0 + (1f - s0) * u
                    p.scaleX = sc; p.scaleY = sc
                }
                sp.snap(0f)
                sp.to(1f, 520f) { done() }
                return true
            }
        }
        dest.viewTreeObserver.addOnPreDrawListener(l)
        handler.postDelayed({ if (p.parent != null) done() }, 900)
    }

    companion object {
        const val NONE = 0
        const val CELL = 1
        const val FOLDER_NEW = 2
        const val FOLDER_ADD = 3
        const val DOCK = 4
    }
}
