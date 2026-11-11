package com.ajmal.os

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One drawing primitive of an icon glyph. kind: 0 = fill path, 1 = stroke path, 2 = text. */
class IosShape(
    val kind: Int, val d: String, val col: Int, val ma: Float, val vis: Int, val eo: Boolean, val blend: Int,
    val w: Float, val txt: String, val size: Float, val style: Int, val x: Float, val y: Float
)

class IosSpec(
    val id: String, val c1: Int, val c2: Int, val ink: Int, val accent: Int,
    val darkNative: Boolean, val shapes: List<IosShape>
)

/**
 * iOS-inspired icons for common system apps (original vector artwork, not Apple's assets).
 * Appearance modes: 0 Default, 1 Light, 2 Dark, 3 Clear (glass), 4 Tinted, 5 Liquid Glass.
 * Shape visibility mask: 1 = colour modes, 2 = dark mode, 4 = clear/tinted (monochrome).
 */
object IosIcons {
    private const val INK = 1
    private val byId: Map<String, IosSpec> = (IosIconData.all + IosIconDataExtra.all).associateBy { it.id }
    private val matched = ConcurrentHashMap<String, String>()
    private val paths = ConcurrentHashMap<String, Path>()

    fun match(pkg: String, label: String): IosSpec? {
        val key = "$pkg|$label"
        var id = matched[key]
        if (id == null) { id = idFor(pkg, label) ?: ""; matched[key] = id }
        return if (id.isEmpty()) null else byId[id]
    }

    private val extraIds: Set<String> = IosIconDataExtra.all.map { it.id }.toSet()

    /** True for the redrawn third-party/Google glyphs: used only in Clear/Tinted, real app icons are used otherwise. */
    fun isExtra(sp: IosSpec): Boolean = sp.id in extraIds

    /** iOS display names for the spec ids that have an iOS counterpart. */
    private val names = mapOf(
        "settings" to "Settings", "phone" to "Phone", "contacts" to "Contacts", "messages" to "Messages",
        "camera" to "Camera", "photos" to "Photos", "clock" to "Clock", "calculator" to "Calculator",
        "calendar" to "Calendar", "maps" to "Maps", "notes" to "Notes", "music" to "Music",
        "browser" to "Safari", "mail" to "Mail", "files" to "Files", "weather" to "Weather",
        "store" to "App Store", "recorder" to "Voice Memos", "facetime" to "FaceTime", "books" to "Books",
        "tv" to "TV", "screentime" to "Screen Time", "news" to "News", "gamecenter" to "Game Center",
        "carplay" to "CarPlay", "assistant" to "Siri"
    )

    /** The iOS name for a system app (e.g. Chrome -> Safari), or the original label when there is none. */
    fun iosLabel(pkg: String, label: String): String {
        val id = idFor(pkg, label) ?: return label
        return names[id] ?: label
    }

    private fun idFor(pkg: String, label: String): String? {
        val p = pkg.lowercase()
        val l = label.lowercase().trim()
        return when {
            // third-party / Google apps first, so generic substring rules below cannot swallow them
            p == "com.google.android.youtube" -> "youtube"
            p.contains("youtube.music") -> "ytmusic"
            p == "org.telegram.messenger" || p.startsWith("org.telegram") -> "telegram"
            p == "com.whatsapp" || p.startsWith("com.whatsapp.") -> "whatsapp"
            p == "com.discord" -> "discord"
            p == "com.openai.chatgpt" -> "chatgpt"
            p == "com.anthropic.claude" -> "claude"
            p == "com.reddit.frontpage" -> "reddit"
            p == "com.instagram.android" -> "instagram"
            p == "com.snapchat.android" -> "snapchat"
            p == "com.spotify.music" -> "spotify"
            p == "com.roblox.client" -> "roblox"
            p == "com.github.android" -> "github"
            p == "com.google.android.googlequicksearchbox" -> "google"
            p == "com.google.android.apps.bard" || p.contains("gemini") -> "gemini"
            p == "com.google.android.apps.docs" -> "drive"
            p == "com.google.android.apps.googleassistant" -> "assistant"
            p == "com.google.android.apps.tachyon" -> "facetime"
            p == "com.google.android.apps.books" -> "books"
            p == "com.google.android.videos" -> "tv"
            p == "com.google.android.apps.wellbeing" -> "screentime"
            p == "com.google.android.apps.magazines" -> "news"
            p == "com.google.android.play.games" -> "gamecenter"
            p == "com.google.android.projection.gearhead" -> "carplay"
            p == "com.google.android.inputmethod.latin" -> "keyboard"
            p == "org.videolan.vlc" -> "vlc"
            p == "com.pittvandewitt.wavelet" -> "equalizer"
            p == "com.csparks113.morsecodeapp" -> "morse"
            p == "app.harukan.diary" -> "diary"
            p == "com.missionjeet.app" -> "mission"
            p.startsWith("com.aistudio.homework") -> "homework"
            p.startsWith("com.aistudio.docreader") -> "docreader"
            p == "ai.regainapp" -> "regain"
            p == "com.hmdglobal.support" -> "support"
            p.contains("powersaving") || p.contains("batteryprotect") || p.contains("powermonitor") -> "battery"
            // system apps
            p == "com.android.settings" || l == "settings" -> "settings"
            p.contains("dialer") || l == "phone" -> "phone"
            p.contains("contacts") || l == "contacts" -> "contacts"
            p.contains("messaging") || p.contains(".mms") || l == "messages" || l == "messaging" -> "messages"
            p.contains("camera") || l == "camera" -> "camera"
            p.contains("gallery") || p.contains("apps.photos") || l == "photos" || l == "gallery" -> "photos"
            p.contains("deskclock") || l == "clock" -> "clock"
            p.contains("calculator") || l == "calculator" -> "calculator"
            p.contains("calendar") || l == "calendar" -> "calendar"
            p.contains("apps.maps") || l == "maps" -> "maps"
            p == "com.google.android.keep" || p.contains("notes") || l == "notes" -> "notes"
            p.contains(".music") || l == "music" -> "music"
            p == "com.android.chrome" || p.contains("browser") || l == "chrome" || l == "browser" || l == "internet" -> "browser"
            p == "com.google.android.gm" || p.contains("email") || l == "gmail" || l == "email" || l == "mail" -> "mail"
            p.contains("documentsui") || p.contains("filemanager") || p.contains("nbu.files") || l == "files" || l == "file manager" -> "files"
            p.contains("weather") || l == "weather" -> "weather"
            p == "com.android.vending" -> "store"
            p.contains("recorder") || l.contains("voice memos") -> "recorder"
            else -> null
        }
    }

    /** Changes the cache key once a day for the Calendar icon, which shows today's date. */
    fun salt(a: AppInfo): Int {
        val sp = match(a.pkg, a.label) ?: return 0
        return if (sp.id == "calendar") Calendar.getInstance().get(Calendar.DAY_OF_YEAR) else 0
    }

    private fun path(d: String, eo: Boolean): Path = paths.getOrPut(d + if (eo) "#" else "") {
        val out = Path()
        out.fillType = if (eo) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val t = d.trim().split(Regex("\\s+"))
        var i = 0
        while (i < t.size) {
            when (t[i++]) {
                "M" -> { out.moveTo(t[i].toFloat(), t[i + 1].toFloat()); i += 2 }
                "L" -> { out.lineTo(t[i].toFloat(), t[i + 1].toFloat()); i += 2 }
                "C" -> {
                    out.cubicTo(t[i].toFloat(), t[i + 1].toFloat(), t[i + 2].toFloat(), t[i + 3].toFloat(), t[i + 4].toFloat(), t[i + 5].toFloat())
                    i += 6
                }
                "Z" -> out.close()
            }
        }
        out
    }

    /** Draws the glyph icon filling [rect] (the caller clips it to the squircle). */
    fun draw(c: Canvas, sp: IosSpec, rect: RectF, mode: Int, tint: Int) {
        val mono = mode == 3 || mode == 4
        val visBit = if (mono) 4 else if (mode == 2) 2 else 1
        var c1 = sp.c1
        var c2 = sp.c2
        when (mode) {
            1 -> { c1 = mix(c1, Color.WHITE, 0.25f); c2 = mix(c2, Color.WHITE, 0.25f) }
            2 -> if (!sp.darkNative) { c1 = 0xFF3A3A3F.toInt(); c2 = 0xFF121214.toInt() }
            3 -> { c1 = Color.argb(115, 255, 255, 255); c2 = Color.argb(46, 255, 255, 255) }
            4 -> { c1 = mix(tint, Color.BLACK, 0.55f); c2 = mix(tint, Color.BLACK, 0.75f) }
            5 -> { c1 = mix(c1, Color.WHITE, 0.16f); c2 = mix(c2, Color.WHITE, 0.05f) }
        }
        val ink = when (mode) {
            3 -> Color.WHITE
            4 -> mix(tint, Color.WHITE, 0.55f)
            2 -> sp.accent
            else -> sp.ink
        }
        c.save()
        c.translate(rect.left, rect.top)
        val sc = rect.width() / 100f
        c.scale(sc, sc)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 100f, c1, c2, Shader.TileMode.CLAMP)
        c.drawRect(-1f, -1f, 101f, 101f, p)
        p.shader = null

        val now = Calendar.getInstance()
        for (sh in sp.shapes) {
            if ((sh.vis and visBit) == 0) continue
            val base = if (mono || sh.col == INK) ink else sh.col
            val alpha = (Color.alpha(base) * (if (mono) sh.ma else 1f)).toInt().coerceIn(0, 255)
            if (alpha == 0) continue
            p.reset()
            p.isAntiAlias = true
            p.color = (base and 0x00FFFFFF) or (alpha shl 24)
            if (mode == 5 && sh.kind != 2 && sh.blend != 1) {
                // glyphs float above the glass: soft contact shadow
                val shp = Paint(Paint.ANTI_ALIAS_FLAG)
                shp.color = Color.argb(36, 0, 0, 0)
                if (sh.kind == 1) { shp.style = Paint.Style.STROKE; shp.strokeWidth = sh.w; shp.strokeCap = Paint.Cap.ROUND; shp.strokeJoin = Paint.Join.ROUND }
                c.save(); c.translate(0f, 1.9f)
                c.drawPath(path(sh.d, sh.kind == 0 && sh.eo), shp)
                c.restore()
            }
            when (sh.kind) {
                0 -> {
                    if (sh.blend == 1 && !mono) {
                        p.xfermode = PorterDuffXfermode(if (mode == 2) PorterDuff.Mode.SCREEN else PorterDuff.Mode.MULTIPLY)
                    }
                    c.drawPath(path(sh.d, sh.eo), p)
                }
                1 -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = sh.w
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    c.drawPath(path(sh.d, false), p)
                }
                else -> {
                    val s = sh.txt
                        .replace("{DOW}", now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())?.uppercase() ?: "")
                        .replace("{DAY}", now.get(Calendar.DAY_OF_MONTH).toString())
                    p.textAlign = Paint.Align.CENTER
                    p.textSize = sh.size
                    p.typeface = when (sh.style) {
                        1 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                        2 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                        else -> Typeface.DEFAULT
                    }
                    if (sh.style == 1) p.letterSpacing = 0.06f
                    c.drawText(s, sh.x, sh.y, p)
                }
            }
        }
        c.restore()
    }
}
