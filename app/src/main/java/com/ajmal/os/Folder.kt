package com.ajmal.os

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.min

/** Spring-animated folder panel with inline rename. Icons inside use the same IconHost (drag out works). */
class FolderOverlay(private val h: HomeActivity, val folder: Item, private val src: View) {
    val view = FrameLayout(h)
    private val scrim = View(h)
    private val panel = GlassFrame(h)
    private val title = EditText(h)
    private val sp = Spring { t ->
        panel.alpha = t; scrim.alpha = t
        val s = 0.25f + 0.75f * t
        panel.scaleX = s; panel.scaleY = s
    }
    private var closing = false

    init {
        scrim.setBackgroundColor(0x55000000)
        scrim.setOnClickListener { dismiss(true) }
        view.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.radiusDp = 32f
        panel.clipChildren = false
        val col = LinearLayout(h)
        col.orientation = LinearLayout.VERTICAL
        val pad = h.dpi(16)
        col.setPadding(pad, h.dpi(12), pad, h.dpi(14))
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        title.setText(folder.title.ifEmpty { "Folder" })
        title.setTextColor(fg)
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setSingleLine(true)
        title.background = null
        title.imeOptions = EditorInfo.IME_ACTION_DONE
        title.setOnEditorActionListener { _, _, _ -> commitTitle(); true }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))

        val n = h.model.children(folder.id).size
        val panelW = h.screenW - h.dpi(48)
        val cellW = (panelW - 2 * pad) / 3
        val sz = min(h.iconPx, (cellW * 0.66f).toInt())
        val cellH = (sz * 1.55f + h.dpf(18f)).toInt()
        val rows = ceil(n / 3f).toInt().coerceAtLeast(1)
        val grid = CellLayout(h)
        grid.cols = 3; grid.rows = rows; grid.flow = true
        for (c in h.model.children(folder.id)) {
            val a = h.model.byKey[c.key] ?: continue
            val v = AppIconView(h, h)
            v.bind(c, a, sz, true)
            grid.addView(v)
        }
        val gh = rows * cellH
        val maxH = (h.screenH * 0.55f).toInt()
        if (gh > maxH) {
            val sv = ScrollView(h)
            sv.addView(grid, FrameLayout.LayoutParams(-1, gh))
            col.addView(sv, LinearLayout.LayoutParams(-1, maxH).also { it.topMargin = h.dpi(8) })
        } else col.addView(grid, LinearLayout.LayoutParams(-1, gh).also { it.topMargin = h.dpi(8) })
        panel.addView(col)
        view.addView(panel, FrameLayout.LayoutParams(panelW, -2, Gravity.CENTER))
    }

    private fun commitTitle() {
        val t = title.text.toString().trim().ifEmpty { "Folder" }
        if (t != folder.title) { folder.title = t; h.model.save(); h.refreshAll() }
        (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(title.windowToken, 0)
        title.clearFocus()
    }

    fun show() {
        h.overlayLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
        panel.alpha = 0f; scrim.alpha = 0f
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                val l = IntArray(2); val p = IntArray(2)
                src.getLocationOnScreen(l); panel.getLocationOnScreen(p)
                panel.pivotX = l[0] + src.width / 2f - p[0]
                panel.pivotY = l[1] + src.height / 2f - p[1]
                sp.snap(0f)
                sp.to(1f, 300f)
                return true
            }
        })
    }

    fun dismiss(animated: Boolean) {
        if (closing) return
        closing = true
        val t = title.text.toString().trim().ifEmpty { "Folder" }
        if (t != folder.title) { folder.title = t; h.model.save() }
        val finish = {
            h.overlayLayer.removeView(view)
            h.folderGone(this)
            h.refreshAll()
        }
        if (animated) sp.to(0f, 360f) { finish() } else { sp.stop(); finish() }
    }

    /** During a drag-out the panel fades but stays attached so the source icon keeps receiving touches. */
    fun fadeKeep() { panel.alpha = 0f; scrim.alpha = 0f }
}
