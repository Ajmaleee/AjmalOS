package com.ajmal.os

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Real third-party widgets through AppWidgetHost. Rendering is independent from the glass engine. */
class WidgetManager(private val act: HomeActivity) {
    val host = AppWidgetHost(act, 4242)
    val mgr: AppWidgetManager = AppWidgetManager.getInstance(act)
    private var pendingId = -1

    fun start() { try { host.startListening() } catch (t: Throwable) { } }
    fun stop() { try { host.stopListening() } catch (t: Throwable) { } }
    fun delete(id: Int) { try { host.deleteAppWidgetId(id) } catch (t: Throwable) { } }

    fun createView(item: Item): View? {
        val info = mgr.getAppWidgetInfo(item.widgetId) ?: return null
        val hv = try { host.createView(act, item.widgetId, info) } catch (t: Throwable) { return null }
        val fr = WidgetFrame(act, act)
        fr.addView(hv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fr.tag = item
        return fr
    }

    fun showPicker() {
        val list = ArrayList<AppWidgetProviderInfo>(mgr.installedProviders)
        val pm = act.packageManager
        list.sortBy { it.loadLabel(pm).lowercase() }
        if (list.isEmpty()) { Toast.makeText(act, "No widgets available", Toast.LENGTH_SHORT).show(); return }
        val den = act.resources.displayMetrics.densityDpi
        val adapter = object : ArrayAdapter<AppWidgetProviderInfo>(act, 0, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val info = getItem(position)!!
                val row = (convertView as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(act.dpi(12), act.dpi(8), act.dpi(12), act.dpi(8))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    addView(iv, LinearLayout.LayoutParams(act.dpi(96), act.dpi(64)))
                    val tv = TextView(act); tv.textSize = 15f; tv.setPadding(act.dpi(12), 0, 0, 0)
                    addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val iv = row.getChildAt(0) as ImageView
                val tv = row.getChildAt(1) as TextView
                val d = try { info.loadPreviewImage(act, 0) ?: info.loadIcon(act, den) } catch (t: Throwable) { null }
                iv.setImageDrawable(d)
                val dm = act.resources.displayMetrics
                tv.text = info.loadLabel(pm) + "  (" + (info.minWidth / dm.density).toInt() + "×" + (info.minHeight / dm.density).toInt() + " dp)"
                return row
            }
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add widget")
            .setAdapter(adapter) { _, i -> bind(list[i]) }
            .show()
    }

    private fun bind(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        val ok = try { mgr.bindAppWidgetIdIfAllowed(id, info.provider) } catch (t: Throwable) { false }
        if (ok) configureOrFinish(id)
        else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try { act.startActivityForResult(i, HomeActivity.REQ_BIND) } catch (t: Throwable) { cancel() }
        }
    }

    private fun cancel() {
        if (pendingId != -1) delete(pendingId)
        pendingId = -1
    }

    private fun configureOrFinish(id: Int) {
        val info = mgr.getAppWidgetInfo(id)
        if (info == null) { cancel(); return }
        if (info.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(act, id, 0, HomeActivity.REQ_CONFIG, null) }
            catch (t: Throwable) { place(id) }
        } else place(id)
    }

    fun onResult(req: Int, res: Int, data: Intent?) {
        val id = if (pendingId != -1) pendingId else data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (res != Activity.RESULT_OK) { cancel(); return }
        if (req == HomeActivity.REQ_BIND) configureOrFinish(id)
        else if (req == HomeActivity.REQ_CONFIG) place(id)
    }

    private fun place(id: Int) {
        pendingId = -1
        val info = mgr.getAppWidgetInfo(id)
        if (info == null) { delete(id); return }
        val m = act.model.newItem(Item.WIDGET)
        m.widgetId = id
        m.pkg = info.provider.packageName
        m.cls = info.provider.className
        val cw = act.pagerCellW().toFloat()
        val ch = act.pagerCellH().toFloat()
        m.sx = max(1, min(act.cols, ceil(info.minWidth / cw).toInt()))
        m.sy = max(1, min(act.rows, ceil(info.minHeight / ch).toInt()))
        act.model.placeAnywhere(m, act.cols, act.rows, act.currentPageId())
        act.model.items.add(m)
        act.model.save()
        act.refreshAll()
        act.goToPageId(m.page)
        Toast.makeText(act, "Widget added", Toast.LENGTH_SHORT).show()
    }
}
