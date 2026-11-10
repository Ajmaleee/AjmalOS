package com.ajmal.os

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class HomeRoot(ctx: Context) : FrameLayout(ctx)

class HomeActivity : Activity(), IconHost, PagerCallbacks {
    lateinit var app: LauncherApp
    lateinit var prefs: Prefs
    lateinit var model: LauncherModel
    lateinit var widgets: WidgetManager
    lateinit var drag: DragController
    lateinit var root: HomeRoot
    lateinit var pager: HomePager
    lateinit var dockFrame: DockFrame
    lateinit var dockLayout: CellLayout
    lateinit var overlayLayer: FrameLayout
    lateinit var dragLayer: FrameLayout
    private lateinit var dim: View
    private lateinit var dots: PageDots
    private lateinit var pill: GlassFrame
    private lateinit var editBar: LinearLayout

    var den = 1f
    var screenW = 0
    var screenH = 0
    var insetTop = 0
    var insetBottom = 0
    var cols = 4
    var rows = 6
    var dockCount = 4
    var iconPx = 0
    private var dockIconPx = 0
    private var pagerH = 0
    private var hs = 0
    private var dockVisible = true
    private var dockW = 0

    private val viewCache = HashMap<Long, View>()
    private val pageLayouts = HashMap<Int, CellLayout>()
    private var editMode = false
    private var appliedRev = -1
    private var hotView: AppIconView? = null
    private var menuView: View? = null
    private var pendingIconPkg: String? = null
    var folderOverlay: FolderOverlay? = null
    private var library: LibraryOverlay? = null
    private var search: SearchOverlay? = null
    private var overview: OverviewOverlay? = null
    private var fps: TextView? = null
    private var fpsOn = false

    override val editing: Boolean get() = editMode

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        app = application as LauncherApp
        prefs = app.prefs
        model = app.model
        den = resources.displayMetrics.density
        screenW = app.glass.screenW
        screenH = app.glass.screenH
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
        insetTop = if (sb > 0) resources.getDimensionPixelSize(sb) else dpi(24)

        widgets = WidgetManager(this)
        drag = DragController(this)
        buildChrome()
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            insetTop = ins.stableInsetTop
            insetBottom = ins.stableInsetBottom
            val ime = ins.systemWindowInsetBottom
            library?.setBottomInset(ime)
            search?.setBottomInset(ime)
            if (model.ready) { applyChrome(); rebuildIfMetricsChanged() }
            ins
        }
        model.listeners.add { if (!drag.active) refreshAll(); library?.reload() }
        app.glass.changeCbs.add { for (v in viewCache.values) v.invalidate() }
        model.whenReady { applyAll() }
    }

    override fun onStart() { super.onStart(); widgets.start() }

    override fun onResume() {
        super.onResume()
        app.glass.reloadIfChanged()
        if (model.ready && prefs.rev != appliedRev) applyAll()
        root.post {
            try { WallpaperManager.getInstance(this).setWallpaperOffsets(root.windowToken, 0.5f, 0.5f) } catch (t: Throwable) { }
        }
        updateFps()
    }

    override fun onPause() {
        super.onPause()
        model.flush()
        stopFps()
    }

    override fun onStop() { super.onStop(); widgets.stop() }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) {
            dismissMenu()
            closeOverlays()
            if (editMode) setEditing(false)
            if (model.ready) pager.goTo(0)
        }
    }

    override fun onBackPressed() {
        when {
            menuView != null -> dismissMenu()
            folderOverlay != null -> folderOverlay?.dismiss(true)
            library != null -> library?.close()
            search != null -> search?.close()
            overview != null -> overview?.close()
            editMode -> setEditing(false)
            model.ready && pager.index != 0 -> pager.goTo(0)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_BIND, REQ_CONFIG -> widgets.onResult(req, res, data)
            REQ_ICON -> {
                val pkg = pendingIconPkg
                val uri = data?.data
                pendingIconPkg = null
                if (res == RESULT_OK && pkg != null && uri != null) {
                    app.icons.importCustom(uri, pkg) { ok ->
                        Toast.makeText(this, if (ok) "Icon updated" else "Could not read image", Toast.LENGTH_SHORT).show()
                        prefs.touch(); applyAll()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun buildChrome() {
        root = HomeRoot(this)
        root.clipChildren = false
        dim = View(this)
        dim.setBackgroundColor(Color.BLACK)
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))

        pager = HomePager(this, this)
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        dots = PageDots(this)
        root.addView(dots, FrameLayout.LayoutParams(dpi(200), dpi(14), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        pill = GlassFrame(this)
        pill.radiusDp = 17f
        pill.shadowScale = 0.5f
        val pt = label(this, "🔍  Search", 14f, Color.WHITE)
        pt.gravity = Gravity.CENTER
        pill.addView(pt, FrameLayout.LayoutParams(-1, -1))
        pill.setOnClickListener { openSearch() }
        root.addView(pill, FrameLayout.LayoutParams(dpi(150), dpi(34), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        dockFrame = DockFrame(this)
        dockFrame.radiusDp = 30f
        dockLayout = CellLayout(this)
        dockFrame.addView(dockLayout, FrameLayout.LayoutParams(-1, -1))
        root.addView(dockFrame, FrameLayout.LayoutParams(-1, dpi(80), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        editBar = LinearLayout(this)
        editBar.gravity = Gravity.CENTER_VERTICAL
        val add = label(this, "＋ Widgets", 15f, Color.WHITE)
        add.setPadding(dpi(14), dpi(8), dpi(14), dpi(8))
        add.background = roundBg(0x66000000, dpf(16f))
        add.setOnClickListener { widgets.showPicker() }
        val spacer = View(this)
        val done = label(this, "Done", 15f, Color.WHITE)
        done.setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
        done.background = roundBg(0x66000000, dpf(16f))
        done.setOnClickListener { setEditing(false) }
        editBar.addView(add)
        editBar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        editBar.addView(done)
        editBar.visibility = View.GONE
        root.addView(editBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        overlayLayer = FrameLayout(this)
        overlayLayer.clipChildren = false
        root.addView(overlayLayer, FrameLayout.LayoutParams(-1, -1))
        dragLayer = FrameLayout(this)
        dragLayer.clipChildren = false
        root.addView(dragLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private var lastSig = ""

    private fun computeMetrics() {
        cols = prefs.int(K.COLS)
        rows = prefs.int(K.ROWS)
        dockCount = prefs.int(K.DOCK_COUNT)
        hs = dpi(prefs.int(K.HSPACE))
        var s = dpi(prefs.int(K.ICON_SIZE))
        if (prefs.bool(K.LARGE_ICONS)) s = (s * 1.25f).toInt()
        dockVisible = prefs.bool(K.DOCK_VISIBLE)
        dockW = screenW - dpi(28)
        val dockCellW = (dockW - dpi(16)) / dockCount
        dockIconPx = min(dpi(prefs.int(K.DOCK_ICON)), (dockCellW * 0.8f).toInt())
        val dockH = (dockIconPx * 1.32f).toInt() + dpi(8)
        val dockBottom = insetBottom + dpi(prefs.int(K.DOCK_MARGIN))
        var y = dockBottom + (if (dockVisible) dockH else 0)
        val pillOn = prefs.bool(K.SEARCH_PILL)
        var pillB = 0
        if (pillOn) { pillB = y + dpi(10); y = pillB + dpi(34) }
        val dotsOn = prefs.bool(K.DOTS)
        var dotsB = 0
        if (dotsOn) { dotsB = y + dpi(6); y = dotsB + dpi(14) }
        val pagerBottom = y + dpi(6)
        val pagerTop = insetTop + dpi(8) + dpi(prefs.int(K.VSPACE))
        pagerH = screenH - pagerTop - pagerBottom
        val cellW = (screenW - 2 * hs) / cols
        val cellH = pagerH / rows
        val lp = if (prefs.bool(K.LABELS)) prefs.int(K.LABEL_SIZE) * den * 1.6f else 0f
        iconPx = max(dpi(24), min(s, min((cellW * 0.82f).toInt(), (cellH - lp - dpi(6)).toInt())))

        metrics.dockH = dockH; metrics.dockBottom = dockBottom; metrics.pillOn = pillOn; metrics.pillB = pillB
        metrics.dotsOn = dotsOn; metrics.dotsB = dotsB; metrics.pagerBottom = pagerBottom; metrics.pagerTop = pagerTop
        lastSig = "$cols,$rows,$dockCount,$iconPx,$dockIconPx,$pagerH,$hs,$dockVisible"
    }

    private class Metrics {
        var dockH = 0; var dockBottom = 0; var pillOn = true; var pillB = 0
        var dotsOn = true; var dotsB = 0; var pagerBottom = 0; var pagerTop = 0
    }
    private val metrics = Metrics()

    private fun applyChrome() {
        computeMetrics()
        val m = metrics
        (pager.layoutParams as FrameLayout.LayoutParams).apply { topMargin = m.pagerTop; bottomMargin = m.pagerBottom }
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = m.dotsB
        dots.visibility = if (m.dotsOn) View.VISIBLE else View.GONE
        (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = m.pillB
        pill.visibility = if (m.pillOn) View.VISIBLE else View.GONE
        (dockFrame.layoutParams as FrameLayout.LayoutParams).apply {
            height = m.dockH; bottomMargin = m.dockBottom; width = dockW
        }
        dockFrame.visibility = if (dockVisible) View.VISIBLE else View.GONE
        dockFrame.drawBg = prefs.bool(K.DOCK_BG)
        dockFrame.strength = 0.4f + prefs.int(K.DOCK_GLASS) / 100f
        dockFrame.onSwipeUp = if (prefs.int(K.G_DOCK_UP) != Act.NONE) ({ perform(prefs.int(K.G_DOCK_UP)) }) else null
        (editBar.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insetTop + dpi(6); leftMargin = dpi(14); rightMargin = dpi(14)
        }
        dim.alpha = prefs.int(K.DIM) / 100f
        root.requestLayout()
    }

    private fun rebuildIfMetricsChanged() {
        val old = lastSig
        computeMetrics()
        if (old != lastSig) { applyChrome(); refreshAll() }
    }

    private fun applyAll() {
        appliedRev = prefs.rev
        applyChrome()
        model.normalize(cols, rows, dockCount)
        val it = viewCache.entries.iterator()
        while (it.hasNext()) if (it.next().value !is WidgetFrame) it.remove()
        refreshAll()
        app.glass.reload()
        library?.reload()
        updateFps()
    }

    // ------------------------------------------------------------------ building views

    fun pagerCellW(): Int = (screenW - 2 * hs) / cols
    fun pagerCellH(): Int = pagerH / rows
    fun pagerHeight(): Int = pagerH
    fun pageLayoutFor(pid: Int): CellLayout? = pageLayouts[pid]
    fun currentPageId(): Int {
        val v = model.visiblePages()
        return v.getOrElse(pager.index) { v[0] }
    }

    fun goToPageId(pid: Int) {
        val i = model.visiblePages().indexOf(pid)
        if (i >= 0) pager.goTo(i)
    }

    private fun viewFor(m: Item, size: Int, label: Boolean): View? {
        if (m.type == Item.WIDGET) {
            var v = viewCache[m.id]
            if (v == null) {
                v = widgets.createView(m) ?: return null
                viewCache[m.id] = v
            }
            v.tag = m
            v.alpha = 1f
            return v
        }
        val a = if (m.type == Item.APP) (model.byKey[m.key] ?: return null) else null
        var v = viewCache[m.id] as? AppIconView
        if (v == null) { v = AppIconView(this, this); viewCache[m.id] = v }
        v.bind(m, a, size, label)
        return v
    }

    fun refreshAll() {
        if (!model.ready) return
        val vis = model.visiblePages()
        for (v in viewCache.values) (v.parent as? ViewGroup)?.removeView(v)
        dockLayout.removeAllViews()
        val used = HashSet<Long>()
        val dead = ArrayList<Item>()
        val labels = prefs.bool(K.LABELS)
        val layouts = ArrayList<CellLayout>()
        pageLayouts.keys.retainAll(vis.toSet())
        for (pid in vis) {
            val cl = pageLayouts.getOrPut(pid) { CellLayout(this) }
            cl.removeAllViews()
            cl.cols = cols; cl.rows = rows; cl.padH = hs; cl.padV = 0
            cl.clearHint()
            for (m in model.pageItems(pid)) {
                val v = viewFor(m, iconPx, labels)
                if (v == null) { if (m.type == Item.WIDGET) dead.add(m); continue }
                used.add(m.id)
                cl.addView(v)
            }
            layouts.add(cl)
        }
        dockLayout.cols = dockCount; dockLayout.rows = 1
        dockLayout.padH = dpi(8); dockLayout.padV = dpi(4)
        dockLayout.clearHint()
        for (m in model.dockItems()) {
            val v = viewFor(m, dockIconPx, false) ?: continue
            used.add(m.id)
            dockLayout.addView(v)
        }
        // views for folder children are not cached here; drop anything no longer present
        viewCache.keys.retainAll(used)
        if (dead.isNotEmpty()) { model.items.removeAll(dead.toSet()); model.save() }
        pager.setPages(layouts, pager.index)
        dots.count = layouts.size
        dots.pos = pager.index.toFloat()
        if (editMode) for (v in viewCache.values) v.invalidate()
        hotView = null
    }

    // ------------------------------------------------------------------ edit mode

    fun setEditing(on: Boolean) {
        if (on == editMode) return
        if (on && prefs.bool(K.LOCK_LAYOUT)) {
            Toast.makeText(this, "Home screen layout is locked", Toast.LENGTH_SHORT).show()
            return
        }
        editMode = on
        pager.editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            val s = Spring { t -> editBar.alpha = t; editBar.translationY = (1f - t) * -dpf(20f) }
            s.snap(0f); s.to(1f, 300f)
        }
        for (v in viewCache.values) v.invalidate()
        if (on) startWiggle() else for (v in viewCache.values) v.rotation = 0f
    }

    private val wiggle = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!editMode) return
            val ph = t / 1e9f
            for (v in viewCache.values) {
                if (v is AppIconView) v.rotation = sin(ph * 18f + ((v.item?.id ?: 0L) % 7L).toFloat()) * 1.6f
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startWiggle() {
        if (!prefs.bool(K.DECOR_ANIM) || prefs.bool(K.REDUCE_MOTION)) return
        Choreographer.getInstance().postFrameCallback(wiggle)
    }

    // ------------------------------------------------------------------ IconHost

    override fun onTap(v: View) {
        val m = v.tag as? Item ?: return
        when (m.type) {
            Item.APP -> {
                val a = model.byKey[m.key] ?: return
                folderOverlay?.dismiss(false)
                launch(a, v)
            }
            Item.FOLDER -> openFolder(m, v)
        }
    }

    override fun onRemoveBadge(v: View) {
        val m = v.tag as? Item ?: return
        removeItem(m)
    }

    override fun canDrag(v: View): Boolean = !prefs.bool(K.LOCK_LAYOUT)
    override fun dragStart(v: View, rx: Float, ry: Float) { drag.start(v, rx, ry) }
    override fun dragMove(rx: Float, ry: Float) { drag.move(rx, ry) }
    override fun dragEnd(rx: Float, ry: Float, cancel: Boolean) { drag.end(rx, ry, cancel) }

    override fun onLongPress(v: View) {
        val m = v.tag as? Item ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val ax = loc[0] + v.width / 2f
        val ay = loc[1].toFloat()
        val rows = ArrayList<Pair<String, () -> Unit>>()
        val locked = prefs.bool(K.LOCK_LAYOUT)
        when (m.type) {
            Item.APP -> {
                val a = model.byKey[m.key] ?: return
                rows.add("Open" to { folderOverlay?.dismiss(false); launch(a, v) })
                for (si in shortcutsFor(a.pkg).take(4)) {
                    val name = si.shortLabel?.toString() ?: continue
                    rows.add(name to { startShortcut(si) })
                }
                rows.add("App info" to { appInfo(a.pkg) })
                if (!locked) {
                    rows.add("Rename shortcut" to {
                        inputDialog("Rename", m.title.ifEmpty { a.label }) { t -> m.title = t; model.save(); refreshAll(); folderOverlay?.dismiss(false) }
                    })
                    rows.add("Change icon" to { pickIcon(a.pkg) })
                    if (app.icons.hasCustom(a.pkg)) rows.add("Reset icon" to { app.icons.clearCustom(a.pkg); prefs.touch(); applyAll() })
                    if (m.container != Item.IN_FOLDER) rows.add("Add to folder" to { chooseFolder(m) })
                }
                rows.add("Share application" to { shareApp(a.pkg) })
                if (!a.system) rows.add("Uninstall" to { uninstall(a.pkg) })
                if (!locked) {
                    rows.add((if (m.container == Item.IN_FOLDER) "Remove from folder" else "Remove from home screen") to {
                        if (m.container == Item.IN_FOLDER) moveOutOfFolder(m) else removeItem(m)
                    })
                }
            }
            Item.FOLDER -> {
                rows.add("Open" to { openFolder(m, v) })
                if (!locked) {
                    rows.add("Rename folder" to { inputDialog("Rename folder", m.title) { t -> m.title = t.ifEmpty { "Folder" }; model.save(); refreshAll() } })
                    rows.add("Remove folder" to { removeItem(m) })
                }
            }
            Item.WIDGET -> {
                if (!locked) {
                    rows.add("Resize" to { resizeDialog(m) })
                    rows.add("Remove widget" to { removeItem(m) })
                }
            }
        }
        if (rows.isNotEmpty()) showMenu(ax, ay, rows)
    }

    // ------------------------------------------------------------------ drag callbacks

    fun onDragBegan(m: Item) {
        dismissMenu()
        folderOverlay?.fadeKeep()
    }

    fun showHint(t: DragController.Target, m: Item) {
        for (p in pageLayouts.values) p.clearHint()
        dockLayout.clearHint()
        var hot: AppIconView? = null
        when (t.kind) {
            DragController.CELL -> pager.currentPage()?.setHint(t.x, t.y, m.sx, m.sy)
            DragController.DOCK -> dockLayout.setHint(t.x, 0, 1, 1)
            DragController.FOLDER_NEW, DragController.FOLDER_ADD -> hot = viewCache[t.other?.id ?: -1L] as? AppIconView
        }
        if (hot !== hotView) {
            hotView?.setHot(false)
            hot?.setHot(true)
            hotView = hot
        }
    }

    fun clearHints() {
        for (p in pageLayouts.values) p.clearHint()
        dockLayout.clearHint()
        hotView?.setHot(false)
        hotView = null
    }

    fun finishDrag(m: Item, proxy: View, ok: Boolean) {
        folderOverlay?.let { it.dismiss(false) }
        refreshAll()
        val dest = viewCache[m.id] ?: (if (m.container == Item.IN_FOLDER) viewCache[m.parent] else null)
        drag.land(proxy, dest)
    }

    fun dockRect(): Rect? {
        if (!dockVisible) return null
        val l = IntArray(2)
        dockFrame.getLocationOnScreen(l)
        return Rect(l[0], l[1], l[0] + dockFrame.width, l[1] + dockFrame.height)
    }

    // ------------------------------------------------------------------ pager callbacks / gestures

    override fun onPageSettled(index: Int) {}
    override fun onScroll(pos: Float) { dots.pos = pos }
    override fun onPinchIn() { onAction(prefs.int(K.G_PINCH)) }

    override fun onAction(a: Int) {
        if (drag.active || menuView != null || folderOverlay != null || library != null || search != null || overview != null) return
        perform(a)
    }

    override fun onLongPressEmpty(x: Float, y: Float) {
        if (drag.active || overlayBusy()) return
        val a = prefs.int(K.G_LONG_EMPTY)
        if (editMode && a == Act.EDIT) {
            val r = ArrayList<Pair<String, () -> Unit>>()
            r.add("Add widget" to { widgets.showPicker() })
            r.add("Page overview" to { openOverview() })
            r.add("Wallpaper" to { chooseWallpaper() })
            r.add("Launcher settings" to { openSettings() })
            showMenu(x, y + dpf(30f), r)
        } else perform(a)
    }

    private fun overlayBusy() = menuView != null || folderOverlay != null || library != null || search != null || overview != null

    fun perform(a: Int) {
        when (a) {
            Act.NEXT_PAGE -> pager.goTo(pager.index + 1)
            Act.PREV_PAGE -> pager.goTo(pager.index - 1)
            Act.LIBRARY -> openLibrary()
            Act.SEARCH -> openSearch()
            Act.EDIT -> setEditing(true)
            Act.OVERVIEW -> openOverview()
            Act.LOCK -> lockScreen()
            Act.SETTINGS -> openSettings()
            Act.NOTIFS -> expandNotifications()
            Act.WIDGETS -> { if (!prefs.bool(K.LOCK_LAYOUT)) widgets.showPicker() }
        }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(this, LockAdmin::class.java)
        if (dpm.isAdminActive(cn)) dpm.lockNow()
        else {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed only to lock the screen with a gesture.")
            try { startActivity(i) } catch (t: Throwable) { }
        }
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (t: Throwable) { }
    }

    fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    private fun chooseWallpaper() {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (t: Throwable) { }
    }

    // ------------------------------------------------------------------ overlays

    fun closeOverlays() {
        folderOverlay?.dismiss(false)
        library?.close(); search?.close(); overview?.close()
    }

    fun openLibrary() {
        if (library != null) return
        setEditing(false)
        library = LibraryOverlay(this).also { it.show() }
    }

    fun openSearch() {
        if (search != null) return
        setEditing(false)
        search = SearchOverlay(this).also { it.show() }
    }

    fun openOverview() {
        if (overview != null) return
        overview = OverviewOverlay(this).also { it.show() }
    }

    fun libraryGone() { library = null }
    fun searchGone() { search = null }
    fun overviewGone() { overview = null }
    fun folderGone(f: FolderOverlay) { if (folderOverlay === f) folderOverlay = null }

    private fun openFolder(m: Item, v: View) {
        if (folderOverlay != null) return
        val f = FolderOverlay(this, m, v)
        folderOverlay = f
        f.show()
    }

    // ------------------------------------------------------------------ menus & dialogs

    fun showMenu(ax: Float, ay: Float, rows: List<Pair<String, () -> Unit>>) {
        dismissMenu()
        val scrim = FrameLayout(this)
        scrim.setOnClickListener { dismissMenu() }
        val panel = GlassFrame(this)
        panel.radiusDp = 18f
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val fg = if (app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        for ((t, r) in rows) {
            val tv = label(this, t, 16f, fg)
            tv.setPadding(dpi(18), dpi(13), dpi(18), dpi(13))
            tv.setOnClickListener { dismissMenu(); r() }
            col.addView(tv)
        }
        panel.addView(col)
        val w = dpi(230)
        scrim.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlayLayer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        menuView = scrim
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST))
        val ph = panel.measuredHeight
        val x = (ax - w / 2f).coerceIn(dpf(12f), (screenW - w - dpf(12f)).coerceAtLeast(dpf(12f)))
        var y = ay - ph - dpf(8f)
        if (y < insetTop + dpf(12f)) y = ay + dpf(70f)
        y = y.coerceIn(insetTop + dpf(8f), (screenH - ph - dpf(8f)).coerceAtLeast(insetTop + dpf(8f)))
        panel.translationX = x
        panel.translationY = y
        panel.pivotX = (ax - x).coerceIn(0f, w.toFloat())
        panel.pivotY = if (y > ay) 0f else ph.toFloat()
        val sp = Spring { t -> panel.alpha = t; val s = 0.85f + 0.15f * t; panel.scaleX = s; panel.scaleY = s }
        sp.snap(0f); sp.to(1f, 600f)
    }

    fun dismissMenu() {
        val m = menuView ?: return
        menuView = null
        overlayLayer.removeView(m)
    }

    private fun listDialog(title: String, rows: List<Pair<String, () -> Unit>>) {
        val names = rows.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setItems(names) { _, i -> rows[i].second() }
            .show()
    }

    private fun inputDialog(title: String, initial: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.setText(initial)
        e.setSelection(e.text.length)
        e.setSingleLine(true)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(e)
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ item operations

    fun removeItem(m: Item) {
        when (m.type) {
            Item.APP -> model.items.remove(m)
            Item.FOLDER -> {
                for (c in model.children(m.id)) model.placeAnywhere(c, cols, rows, currentPageId())
                model.items.remove(m)
            }
            Item.WIDGET -> { widgets.delete(m.widgetId); model.items.remove(m) }
        }
        model.tidy()
        model.save()
        refreshAll()
    }

    private fun moveOutOfFolder(m: Item) {
        model.placeAnywhere(m, cols, rows, currentPageId())
        model.tidy()
        model.save()
        folderOverlay?.dismiss(false)
        refreshAll()
    }

    private fun chooseFolder(m: Item) {
        val folders = model.items.filter { it.type == Item.FOLDER }
        if (folders.isEmpty()) {
            Toast.makeText(this, "Drag one app onto another to create a folder", Toast.LENGTH_LONG).show()
            return
        }
        listDialog("Add to folder", folders.map { f ->
            f.title.ifEmpty { "Folder" } to {
                m.container = Item.IN_FOLDER; m.parent = f.id; m.x = model.children(f.id).size
                model.tidy(); model.save(); refreshAll()
            }
        })
    }

    private fun resizeDialog(m: Item) {
        fun apply(dx: Int, dy: Int) {
            val nx = (m.sx + dx).coerceIn(1, cols)
            val ny = (m.sy + dy).coerceIn(1, rows)
            val g = model.occupancy(m.page, cols, rows, m)
            if (model.fits(g, m.x, m.y, nx, ny)) { m.sx = nx; m.sy = ny; model.save(); refreshAll() }
            else Toast.makeText(this, "Not enough room", Toast.LENGTH_SHORT).show()
        }
        listDialog("Resize widget", listOf(
            "Wider" to { apply(1, 0) }, "Narrower" to { apply(-1, 0) },
            "Taller" to { apply(0, 1) }, "Shorter" to { apply(0, -1) }
        ))
    }

    private fun pickIcon(pkg: String) {
        pendingIconPkg = pkg
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        try { startActivityForResult(i, REQ_ICON) } catch (t: Throwable) { pendingIconPkg = null }
    }

    // ------------------------------------------------------------------ app actions

    fun launch(a: AppInfo, v: View?) {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(a.pkg, a.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            val opts = if (v != null && v.width > 0 && !prefs.bool(K.REDUCE_MOTION))
                ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle() else null
            startActivity(i, opts)
            model.noteLaunch(a)
        } catch (t: Throwable) {
            Toast.makeText(this, "Cannot open ${a.label}", Toast.LENGTH_SHORT).show()
            model.refresh()
        }
    }

    private fun shortcutsFor(pkg: String): List<ShortcutInfo> = try {
        val la = getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) emptyList()
        else {
            val q = LauncherApps.ShortcutQuery()
            q.setPackage(pkg)
            q.setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            la.getShortcuts(q, Process.myUserHandle()) ?: emptyList()
        }
    } catch (t: Throwable) { emptyList() }

    private fun startShortcut(si: ShortcutInfo) {
        try { getSystemService(LauncherApps::class.java).startShortcut(si, null, null) } catch (t: Throwable) { }
    }

    private fun appInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (t: Throwable) { }
    }

    private fun uninstall(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (t: Throwable) { }
    }

    private fun shareApp(pkg: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=$pkg")
        try { startActivity(Intent.createChooser(i, "Share application")) } catch (t: Throwable) { }
    }

    /** Actions for an app chosen from the App Library or Search (not placed on the grid). */
    fun appActions(a: AppInfo) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Open" to { launch(a, null) })
        val onHome = model.items.any { it.type == Item.APP && it.key == a.key }
        if (!onHome) acts.add("Add to home screen" to {
            val m = model.newItem(Item.APP)
            m.pkg = a.pkg; m.cls = a.cls
            model.placeAnywhere(m, cols, this@HomeActivity.rows, currentPageId())
            model.items.add(m)
            model.hiddenApps.remove(a.key)
            model.save(); refreshAll()
            Toast.makeText(this, "Added to home screen", Toast.LENGTH_SHORT).show()
            library?.reload()
        })
        val fav = model.favorites.contains(a.key)
        acts.add((if (fav) "Remove from favorites" else "Add to favorites") to {
            if (fav) model.favorites.remove(a.key) else model.favorites.add(a.key)
            model.save(); library?.reload()
        })
        val hid = model.hiddenApps.contains(a.key)
        acts.add((if (hid) "Show on home screen" else "Hide from home screen") to {
            if (hid) model.hiddenApps.remove(a.key)
            else {
                model.hiddenApps.add(a.key)
                for (m in ArrayList(model.items)) if (m.type == Item.APP && m.key == a.key) model.items.remove(m)
                model.tidy()
                refreshAll()
            }
            model.save(); library?.reload()
        })
        acts.add("Set category" to { categoryDialog(a) })
        acts.add("App info" to { appInfo(a.pkg) })
        acts.add("Share application" to { shareApp(a.pkg) })
        if (!a.system) acts.add("Uninstall" to { uninstall(a.pkg) })
        listDialog(a.label, acts)
    }

    private fun categoryDialog(a: AppInfo) {
        val rows = ArrayList<Pair<String, () -> Unit>>()
        for (c in model.categories()) rows.add(c to { model.customCat[a.key] = c; model.save(); library?.reload() })
        rows.add("New category…" to {
            inputDialog("Category name", "") { t ->
                if (t.isNotEmpty()) { model.customCat[a.key] = t; model.save(); library?.reload() }
            }
        })
        rows.add("Reset to automatic" to { model.customCat.remove(a.key); model.save(); library?.reload() })
        listDialog("Category", rows)
    }

    // ------------------------------------------------------------------ FPS overlay

    private val fpsCb = object : Choreographer.FrameCallback {
        private var frames = 0
        private var start = 0L
        override fun doFrame(t: Long) {
            if (!fpsOn) return
            if (start == 0L) start = t
            frames++
            if (t - start >= 500_000_000L) {
                val f = frames * 1e9f / (t - start)
                val rt = Runtime.getRuntime()
                val mb = (rt.totalMemory() - rt.freeMemory()) / 1048576
                fps?.text = String.format("%.0f fps · %d MB heap", f, mb)
                frames = 0; start = t
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateFps() {
        if (prefs.bool(K.FPS)) {
            if (fps == null) {
                val t = label(this, "", 12f, Color.GREEN)
                t.setPadding(dpi(8), dpi(2), dpi(8), dpi(2))
                t.background = roundBg(0xAA000000.toInt(), dpf(8f))
                overlayLayer.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).also {
                    it.topMargin = insetTop + dpi(4); it.rightMargin = dpi(8)
                })
                fps = t
            }
            if (!fpsOn) { fpsOn = true; Choreographer.getInstance().postFrameCallback(fpsCb) }
        } else {
            stopFps()
            fps?.let { overlayLayer.removeView(it) }
            fps = null
        }
    }

    private fun stopFps() { fpsOn = false }

    companion object {
        const val REQ_BIND = 11
        const val REQ_CONFIG = 12
        const val REQ_ICON = 13
    }
}
