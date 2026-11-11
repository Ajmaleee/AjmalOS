package com.ajmal.os

import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Row of passcode dots; the first [filled] are solid. */
class PinDots(private val h: HomeActivity, private val n: Int) : View(h) {
    var filled = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = 6f * h.den
    private val gap = 18f * h.den

    override fun onMeasure(w: Int, hh: Int) {
        setMeasuredDimension(((n * 2 * r) + (n - 1) * gap + 4 * h.den).toInt(), (2 * r + 4 * h.den).toInt())
    }

    override fun onDraw(c: Canvas) {
        for (i in 0 until n) {
            val cx = 2f * h.den + r + i * (2 * r + gap)
            val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = if (i < filled) Color.WHITE else 0x33FFFFFF
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * h.den
            p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
        }
    }
}

/**
 * Full-page passcode pad for the Hidden folder. Accepts the passcode (default 7429, changeable in
 * Settings) or a fingerprint through the platform BiometricPrompt (API 28+).
 */
class PinPad(private val h: HomeActivity, private val onOk: () -> Unit, private val onClose: () -> Unit) : FrameLayout(h) {
    private val pin = h.prefs.int(K.HIDDEN_PIN).toString()
    private val entered = StringBuilder()
    private val dots = PinDots(h, pin.length)
    private val title: TextView = label(h, "Enter Passcode", 19f, Color.WHITE)
    private var sig: CancellationSignal? = null
    private var closed = false
    private var fails = 0
    private var lockUntil = 0L

    init {
        isClickable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xE0000000.toInt())
        val col = LinearLayout(h)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        title.gravity = Gravity.CENTER
        col.addView(title, LinearLayout.LayoutParams(-2, -2))
        col.addView(dots, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = h.dpi(22); it.bottomMargin = h.dpi(30) })

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("fp", "0", "del"))
        for (r in rows) {
            val row = LinearLayout(h)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in r) row.addView(key(k), LinearLayout.LayoutParams(h.dpi(74), h.dpi(74)).also { it.setMargins(h.dpi(9), h.dpi(7), h.dpi(9), h.dpi(7)) })
            col.addView(row)
        }
        val cancel = label(h, "Cancel", 16f, Color.WHITE)
        cancel.setPadding(h.dpi(24), h.dpi(12), h.dpi(24), h.dpi(12))
        cancel.setOnClickListener { dismiss() }
        col.addView(cancel, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = h.dpi(12) })
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        alpha = 0f
        animate().alpha(1f).setDuration(160).start()
        if (h.prefs.bool(K.HIDDEN_BIO)) postDelayed({ bio() }, 220)
    }

    private fun key(k: String): View {
        val t = label(h, when (k) { "fp" -> "Fingerprint"; "del" -> "Delete"; else -> k }, if (k.length == 1) 28f else 12f, Color.WHITE)
        t.gravity = Gravity.CENTER
        val off = k == "fp" && !h.prefs.bool(K.HIDDEN_BIO)
        val base = if (k.length == 1) 0x33FFFFFF else 0x00FFFFFF
        t.background = roundBg(base, h.dpf(37f))
        if (off) { t.alpha = 0.3f; return t }
        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.background = roundBg(0x80FFFFFF.toInt(), h.dpf(37f))
                MotionEvent.ACTION_UP -> { v.background = roundBg(base, h.dpf(37f)); press(k) }
                MotionEvent.ACTION_CANCEL -> v.background = roundBg(base, h.dpf(37f))
            }
            true
        }
        return t
    }

    private fun press(k: String) {
        if (closed) return
        if (SystemClock.uptimeMillis() < lockUntil) return
        when (k) {
            "fp" -> bio()
            "del" -> if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); dots.filled = entered.length }
            else -> {
                if (entered.length >= pin.length) return
                entered.append(k)
                dots.filled = entered.length
                if (entered.length == pin.length) {
                    if (entered.toString() == pin) success() else wrong()
                }
            }
        }
    }

    private fun wrong() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        fails++
        val d = 18f * h.den
        dots.animate().translationX(d).setDuration(55).withEndAction {
            dots.animate().translationX(-d).setDuration(90).withEndAction {
                dots.animate().translationX(0f).setDuration(55).start()
            }.start()
        }.start()
        postDelayed({ entered.setLength(0); dots.filled = 0 }, 260)
        if (fails >= 5) {
            lockUntil = SystemClock.uptimeMillis() + 30_000
            fails = 0
            title.text = "Too many attempts · wait 30 s"
            postDelayed({ if (!closed) title.text = "Enter Passcode" }, 30_000)
        }
    }

    private fun success() {
        if (closed) return
        closed = true
        sig?.cancel()
        animate().alpha(0f).setDuration(120).withEndAction { (parent as? FrameLayout)?.removeView(this) }.start()
        onOk()
        onClose()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        sig?.cancel()
        animate().alpha(0f).setDuration(140).withEndAction { (parent as? FrameLayout)?.removeView(this) }.start()
        onClose()
    }

    private fun bio() {
        if (closed || Build.VERSION.SDK_INT < 28) return
        try {
            sig?.cancel()
            val cs = CancellationSignal()
            sig = cs
            val ex = h.mainExecutor
            val bp = BiometricPrompt.Builder(h)
                .setTitle("Hidden")
                .setSubtitle("Unlock with your fingerprint")
                .setNegativeButton("Use passcode", ex, DialogInterface.OnClickListener { _, _ -> })
                .build()
            bp.authenticate(cs, ex, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { success() }
            })
        } catch (t: Throwable) { /* no sensor / nothing enrolled: the passcode pad stays available */ }
    }
}
