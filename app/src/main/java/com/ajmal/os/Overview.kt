package com.ajmal.os

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Page overview: live thumbnails with reorder / hide / delete / add controls. */
class OverviewOverlay(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val row = LinearLayout(h)
    private val anim = OverlayAnim(view, 0f, h.overlayLayer) { h.overviewGone() }
    private val thumbs = ArrayList<Bitmap>()

    init {
        view.setBackgroundColor(0xB0000000.toInt())
        view.setOnClickListener { close() }
        val hs = HorizontalScrollView(h)
        hs.isHorizontalScrollBarEnabled = false
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(h.dpi(24), 0, h.dpi(24), 0)
        row.gravity = Gravity.CENTER_VERTICAL
        hs.addView(row, FrameLayout.LayoutParams(-2, -1))
        view.addView(hs, FrameLayout.LayoutParams(-1, -1))
        val done = label(h, "Done", 17f, Color.WHITE)
        done.setPadding(h.dpi(20), h.dpi(10), h.dpi(20), h.dpi(10))
        done.background = roundBg(0x44FFFFFF, h.dpf(18f))
        done.setOnClickListener { close() }
        view.addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = h.insetTop + h.dpi(16) })
        rebuild()
    }

    fun show() = anim.show()
    fun close() { anim.hide(); releaseThumbs() }
    private fun releaseThumbs() { thumbs.clear() }

    private fun thumb(pid: Int): Bitmap? {
        val cl = h.pageLayoutFor(pid) ?: return null
        val pw = h.screenW
        val ph = h.pagerHeight()
        if (cl.width == 0) {
            cl.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
            cl.layout(0, 0, pw, ph)
        }
        val s = 0.3f
        val b = Bitmap.createBitmap((pw * s).toInt(), (ph * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(s, s)
        try { cl.draw(c) } catch (t: Throwable) { }
        thumbs.add(b)
        return b
    }

    private fun button(text: String, click: () -> Unit): TextView {
        val t = label(h, text, 14f, Color.WHITE)
        t.setPadding(h.dpi(10), h.dpi(6), h.dpi(10), h.dpi(6))
        t.setOnClickListener { click() }
        return t
    }

    private fun rebuild() {
        row.removeAllViews()
        releaseThumbs()
        val m = h.model
        for (pid in m.pageOrder.toList()) {
            val hidden = m.hiddenPages.contains(pid)
            val card = LinearLayout(h)
            card.orientation = LinearLayout.VERTICAL
            card.gravity = Gravity.CENTER_HORIZONTAL
            val iv = ImageView(h)
            iv.background = roundBg(0x33FFFFFF, h.dpf(18f))
            iv.setPadding(h.dpi(4), h.dpi(4), h.dpi(4), h.dpi(4))
            val b = if (hidden) null else thumb(pid)
            if (b != null) iv.setImageBitmap(b)
            iv.alpha = if (hidden) 0.4f else 1f
            iv.setOnClickListener { if (!hidden) { h.goToPageId(pid); close() } }
            val tw = (h.screenW * 0.3f).toInt() + h.dpi(8)
            val th = (h.pagerHeight() * 0.3f).toInt() + h.dpi(8)
            card.addView(iv, LinearLayout.LayoutParams(tw, th))
            val ctl = LinearLayout(h)
            ctl.gravity = Gravity.CENTER
            ctl.addView(button("◀") { m.movePage(pid, -1); changed() })
            ctl.addView(button("▶") { m.movePage(pid, 1); changed() })
            ctl.addView(button(if (hidden) "Show" else "Hide") {
                if (!m.togglePageHidden(pid)) android.widget.Toast.makeText(h, "Keep at least one page visible", android.widget.Toast.LENGTH_SHORT).show()
                changed()
            })
            ctl.addView(button("Delete") { confirmDelete(pid) })
            card.addView(ctl)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = h.dpi(16)
            row.addView(card, lp)
        }
        val add = label(h, "+", 40f, Color.WHITE)
        add.gravity = Gravity.CENTER
        add.background = roundBg(0x33FFFFFF, h.dpf(18f))
        add.setOnClickListener { m.addPage(); changed() }
        row.addView(add, LinearLayout.LayoutParams((h.screenW * 0.2f).toInt(), (h.pagerHeight() * 0.3f).toInt()))
    }

    private fun changed() {
        h.refreshAll()
        rebuild()
    }

    private fun confirmDelete(pid: Int) {
        if (h.model.pageOrder.size <= 1) return
        AlertDialog.Builder(h, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete page?")
            .setMessage("Icons and widgets on this page are removed from the home screen. Apps stay in the App Library.")
            .setPositiveButton("Delete") { _, _ ->
                for (w in h.model.deletePage(pid)) h.widgets.delete(w)
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
