package com.ajmal.os

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Renders iOS-style squircle-ish icons off the UI thread and keeps them in a size-capped LRU.
 * Each cached bitmap is (size + 2*pad)^2 and already contains its soft shadow.
 */
class IconCache(private val ctx: Context) {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val exec: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val waiters = HashMap<String, ArrayList<() -> Unit>>()
    @Volatile private var stamps: Map<String, Long> = emptyMap()
    private val dir = File(ctx.filesDir, "icons")

    private fun cacheBytes(): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return min(24, max(8, am.memoryClass / 6)) * 1024 * 1024
    }

    fun trim() { cache.evictAll() }
    fun usedBytes(): Int = cache.size()
    fun hasCustom(pkg: String): Boolean = stamps.containsKey(pkg)

    fun key(a: AppInfo, size: Int): String {
        val app = LauncherApp.get()
        val p = app.prefs
        return a.key + "|" + size + "|" + p.int(K.ICON_APPEARANCE) + "|" + p.int(K.ICON_RADIUS) + "|" +
            p.int(K.SHADOW) + "|" + p.int(K.TINT) + "|" + (if (app.isDark()) 1 else 0) + "|" + (stamps[a.pkg] ?: 0L)
    }

    fun peek(key: String): Bitmap? = cache.get(key)

    /** Must be called on the main thread. Callbacks run on the main thread. */
    fun request(a: AppInfo, key: String, size: Int, cb: () -> Unit) {
        var l = waiters[key]
        if (l != null) { l.add(cb); return }
        l = ArrayList()
        l.add(cb)
        waiters[key] = l
        exec.execute {
            val b = try { render(a, size) } catch (t: Throwable) { fallback(size) }
            main.post {
                cache.put(key, b)
                val w = waiters.remove(key)
                if (w != null) for (r in w) r()
            }
        }
    }

    private fun fallback(size: Int): Bitmap {
        val total = size + padFor(size) * 2
        val b = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF8E8E93.toInt()
        val pad = padFor(size).toFloat()
        c.drawRoundRect(RectF(pad, pad, pad + size, pad + size), size * 0.23f, size * 0.23f, p)
        return b
    }

    // ------------------------------------------------------------------ rendering

    private fun render(a: AppInfo, s: Int): Bitmap {
        val app = LauncherApp.get()
        val p = app.prefs
        val mode = p.int(K.ICON_APPEARANCE)
        val rad = p.int(K.ICON_RADIUS) / 100f
        val shadow = p.int(K.SHADOW) / 100f
        val dark = app.isDark()
        val pad = padFor(s)
        val total = s + pad * 2
        val bmp = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = s * rad
        val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + s).toFloat(), (pad + s).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (shadow > 0.01f) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.color = Color.argb((95 * shadow).toInt().coerceIn(0, 255), 0, 0, 0)
            sp.maskFilter = BlurMaskFilter(pad * 0.6f, BlurMaskFilter.Blur.NORMAL)
            val off = pad * 0.35f
            c.drawRoundRect(RectF(rect.left, rect.top + off, rect.right, rect.bottom + off), r, r, sp)
        }

        c.saveLayer(rect, null)
        drawContent(c, a, rect, s, mode, dark, paint)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.BLACK
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRoundRect(rect, r, r, paint)
        paint.xfermode = null
        c.restore()

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, s * 0.012f)
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(55, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        val ins = paint.strokeWidth / 2f
        c.drawRoundRect(
            RectF(rect.left + ins, rect.top + ins, rect.right - ins, rect.bottom - ins),
            r - ins, r - ins, paint
        )
        return bmp
    }

    private fun drawContent(c: Canvas, a: AppInfo, rect: RectF, s: Int, mode: Int, dark: Boolean, paint: Paint) {
        if (stamps.containsKey(a.pkg)) {
            val b = BitmapFactory.decodeFile(File(dir, a.pkg + ".png").path)
            if (b != null) {
                c.drawBitmap(b, null, rect, paint)
                b.recycle()
                return
            }
        }
        val pm = ctx.packageManager
        val d: Drawable = try { pm.getActivityIcon(ComponentName(a.pkg, a.cls)) } catch (e: Exception) { pm.defaultActivityIcon }
        val tint = LauncherApp.get().tintColor()
        val tintDark = mix(tint, Color.BLACK, 0.55f)
        val tintLight = mix(tint, Color.WHITE, 0.55f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        if (d is AdaptiveIconDrawable) {
            val bg = d.background
            val fg = d.foreground
            val infl = (s * 0.25f).toInt()
            val l = rect.left.toInt() - infl; val t = rect.top.toInt() - infl
            val rr = rect.right.toInt() + infl; val bb = rect.bottom.toInt() + infl
            fun layer(dr: Drawable?, f: ColorFilter?) {
                if (dr == null) return
                dr.setBounds(l, t, rr, bb)
                dr.colorFilter = f
                dr.draw(c)
                dr.colorFilter = null
            }
            when (mode) {
                3 -> {
                    fill.color = Color.argb(60, 255, 255, 255); c.drawRect(rect, fill)
                    layer(fg, PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN))
                }
                4 -> {
                    fill.color = tintDark; c.drawRect(rect, fill)
                    layer(fg, PorterDuffColorFilter(tintLight, PorterDuff.Mode.SRC_IN))
                }
                else -> {
                    layer(bg, null)
                    if (mode == 2) { fill.color = Color.argb(115, 0, 0, 0); c.drawRect(rect, fill) }
                    if (mode == 1) { fill.color = Color.argb(40, 255, 255, 255); c.drawRect(rect, fill) }
                    layer(fg, null)
                }
            }
            return
        }

        // Legacy (non-adaptive) icon.
        val leg = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val lc = Canvas(leg)
        d.setBounds(0, 0, s, s)
        d.draw(lc)
        val fullBleed = Color.alpha(leg.getPixel((s * 0.05f).toInt(), (s * 0.05f).toInt())) > 230 &&
            Color.alpha(leg.getPixel(s - 1 - (s * 0.05f).toInt(), s / 2)) > 230
        if (fullBleed) {
            if (mode == 3 || mode == 4) {
                val gp = Paint(Paint.FILTER_BITMAP_FLAG)
                val cm = ColorMatrix(); cm.setSaturation(0f)
                gp.colorFilter = ColorMatrixColorFilter(cm)
                c.drawBitmap(leg, null, rect, gp)
                fill.color = if (mode == 3) Color.argb(90, 255, 255, 255) else Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
                c.drawRect(rect, fill)
            } else {
                c.drawBitmap(leg, null, rect, paint)
                if (mode == 2) { fill.color = Color.argb(115, 0, 0, 0); c.drawRect(rect, fill) }
                if (mode == 1) { fill.color = Color.argb(40, 255, 255, 255); c.drawRect(rect, fill) }
            }
        } else {
            val darkTile = mode == 2 || (mode == 0 && dark)
            val c1: Int; val c2: Int
            when {
                mode == 3 -> { c1 = Color.argb(70, 255, 255, 255); c2 = Color.argb(30, 255, 255, 255) }
                mode == 4 -> { c1 = tintDark; c2 = mix(tintDark, Color.BLACK, 0.3f) }
                darkTile -> { c1 = 0xFF3A3A3C.toInt(); c2 = 0xFF1C1C1E.toInt() }
                else -> { c1 = Color.WHITE; c2 = 0xFFE4E6EC.toInt() }
            }
            fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, c1, c2, Shader.TileMode.CLAMP)
            c.drawRect(rect, fill)
            val inset = s * 0.15f
            val dst = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            val gp = Paint(Paint.FILTER_BITMAP_FLAG)
            if (mode == 3) gp.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            if (mode == 4) gp.colorFilter = PorterDuffColorFilter(tintLight, PorterDuff.Mode.SRC_IN)
            c.drawBitmap(leg, null, dst, gp)
        }
        leg.recycle()
    }

    // ------------------------------------------------------------------ custom icons

    fun loadStamps() {
        val m = HashMap<String, Long>()
        dir.listFiles()?.forEach {
            if (it.name.endsWith(".png")) m[it.name.removeSuffix(".png")] = it.lastModified()
        }
        stamps = m
    }

    private fun decodeSquare(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var ss = 1
        while (o.outWidth / ss > 1024 || o.outHeight / ss > 1024) ss *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = ss
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
        val side = min(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(sq, 256, 256, true)
    }

    private fun saveBitmap(b: Bitmap, pkg: String) {
        dir.mkdirs()
        FileOutputStream(File(dir, "$pkg.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun importCustom(uri: Uri, pkg: String, done: (Boolean) -> Unit) {
        exec.execute {
            val ok = try {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val b = decodeSquare(bytes)
                if (b != null) { saveBitmap(b, pkg); true } else false
            } catch (t: Throwable) { false }
            if (ok) loadStamps()
            main.post { done(ok) }
        }
    }

    /** Zip of PNGs named <package>.png */
    fun importPack(uri: Uri, done: (Int) -> Unit) {
        exec.execute {
            var n = 0
            try {
                ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val name = e.name.substringAfterLast('/')
                        if (!e.isDirectory && name.endsWith(".png", true) && name.length > 4) {
                            val b = decodeSquare(z.readBytes())
                            if (b != null) { saveBitmap(b, name.dropLast(4)); n++ }
                        }
                        e = z.nextEntry
                    }
                }
            } catch (t: Throwable) { }
            loadStamps()
            main.post { done(n) }
        }
    }

    fun clearCustom(pkg: String) {
        File(dir, "$pkg.png").delete()
        loadStamps()
    }

    fun clearAllCustom() {
        dir.listFiles()?.forEach { it.delete() }
        loadStamps()
    }

    companion object {
        fun padFor(size: Int): Int = (size * 0.09f).toInt()
    }
}
