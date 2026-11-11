# Ajmal OS — native Android launcher (Liquid Glass edition)

Pure Kotlin + Android framework APIs. **Zero third-party dependencies**, no WebView/Flutter/RN, no network code,
no analytics. Package `com.ajmal.os`, minSdk 28, targetSdk 28 (Android 9 behaviour), ARM64-compatible (no native code).

## Build
**GitHub:** push this folder to a repo → Actions → *Build Ajmal OS* → download artifact `AjmalOS-debug-apk`.
The workflow also prints package / SDK levels / HOME-filter check from the built APK.

**Local:** `gradle wrapper --gradle-version 8.5` once, then `./gradlew assembleDebug`
(APK: `app/build/outputs/apk/debug/app-debug.apk`). Needs JDK 17 + Android SDK platform 34.

## Install (Nokia TA-1004)
1. Enable *Install unknown apps* for your file manager / browser, install the APK (or `adb install -r app-debug.apk`).
2. Press Home → choose **Ajmal OS** → *Always*. (Or Settings → Apps → Default apps → Home app.)
3. Optional: open **Ajmal OS Settings** → *Wallpaper → Grant wallpaper access* so the glass can blur your wallpaper
   (Android 9 requires storage permission to read it; without it the glass is flat translucent).

## Architecture
| File | Role |
|---|---|
| `Model.kt` | app snapshot (LauncherApps, refreshed only on package callbacks), layout JSON, folders/pages logic |
| `Icons.kt` | off-thread icon renderer + LRU cache; light/dark/clear/tinted; custom icons & zip packs |
| `Glass.kt` | wallpaper → 1/8-res blurred cache → `GlassFrame` panels; 3 profiles; no GL thread |
| `Spring.kt` | one shared Choreographer callback, only alive while a spring runs |
| `Pager.kt` / `Views.kt` | pager + gesture engine, canvas-drawn icon view, grid, dock, widget frame |
| `Drag.kt` | drag & drop, folder creation by hover, edge page switching |
| `Library.kt`, `Folder.kt`, `Overview.kt` | App Library, Search, folders, page overview |
| `Widgets.kt` | real `AppWidgetHost` widgets (bind/configure/resize/remove/persist) |
| `SettingsActivity.kt` | every row writes a Prefs key that `HomeActivity` re-reads |

## Honest notes / known limits (please read)
- **Never compiled or run by the author of this code.** Expect to fix a few compile errors on the first CI run;
  paste the log and they get fixed. No performance number here has been measured on a real device — use
  *Settings → Performance → FPS and memory overlay* / *Memory diagnostics* and Android Studio profiler.
- **Ultra Glass is not a live GLSL shader.** Refraction + RGB dispersion are pre-baked on tiny bitmaps and cached
  (no continuous GL rendering, per the spec's idle-cost goal). Backdrop alignment assumes the system wallpaper is
  centre-cropped at offset 0.5 (the launcher pins offsets there). Parallax is not implemented.
- Live wallpapers work (system draws them) but glass falls back to flat translucency.
- Icons: only your own / licensed images (long-press → *Change icon*, or import a zip of `<package>.png`).
- Portrait only. Swipe left/right are page navigation when set to Next/Previous page; set them to other actions
  and paging is replaced by those actions.
- "Lock screen" uses a device-admin *force-lock* policy (no accessibility service).


## What changed in 1.1

**Pure Liquid glass** (Settings → Appearance → Glass profile → *Pure Liquid (water glass)*)
A thick, water-filled glass slab with continuous (squircle) corners: the wallpaper is lens-refracted at the rim with
colour dispersion, gently rippled inside, saturated, and finished with a bright top-left fresnel rim, a refractive
bulge with colour fringing at the bottom-right, a dark meniscus and faint water-surface caustics. Refraction
intensity, transparency, edge highlights and shadow sliders all still apply. "Reduced refraction" halves the lens
and drops the colour fringe. Panels that move (folder open/close, drags) reuse their last lens map and re-render
once they settle, so animations stay smooth.

**App icons for your whole app list**
35 new original vector glyph icons (YouTube, YouTube Music, Telegram, WhatsApp, Discord, ChatGPT, Claude, Reddit,
Instagram, Snapchat, Spotify, Roblox, GitHub, Google, Gemini, Drive, Assistant, Meet, Books, TV, Digital Wellbeing,
News, Play Games, Android Auto, Gboard, VLC, Wavelet, Morse, Diary, MissionJeet, Homework, Doc Reader, Regain,
HMD Support, Battery). They are drawn in colour, dark and clear/tinted modes like the existing ones.
Apps that still have no glyph no longer turn into white blobs in Clear/Tinted: a real glyph silhouette is used only
if the app's icon really is one, otherwise a clean letter-mark is drawn on the glass.

**App Library is now the right-most page**, as on iOS: swipe left from the last home page. The dock, search pill and
page dots fade away while it slides in. The old swipe-up gesture default is now "None" (you can still assign it).

**Hidden folder** in the App Library: a locked card. Tap it, then enter the passcode (default **7429**) or use your
fingerprint. Apps hidden with *Hide App (needs passcode)* are removed from the home screen, the library categories,
the A-Z list and search. Change the passcode or turn fingerprint off in Settings → Privacy. The passcode is kept out
of exported backups and survives "Reset all settings".

**Long-press tuning**: a press now tolerates about 20 dp of finger drift for 380 ms before it can turn into a swipe
(fast flicks still swipe immediately). Applies to icons, widgets, empty-space long press and the App Library.

**iOS names**: Chrome → Safari, Gmail → Mail, Play Store → App Store, Google Photos → Photos, Meet → FaceTime,
Digital Wellbeing → Screen Time, Videos → TV, Assistant → Siri, Android Auto → CarPlay, etc.
(Settings → Icons → *iOS app names*; duplicates are never created).

**Liquid Glass icons (Settings → Icons → Icon appearance → Liquid Glass)** were reworked: see-through glass body,
thick refractive rim with falloff, top-left specular dome, bottom glow, dark meniscus, colour fringe on the lower-right
edge, and soft contact shadows under glyphs. Works on the iOS-style glyph icons and on apps' own icons.
Third-party apps (YouTube, WhatsApp, Instagram ...) keep their real icon in every appearance except Clear/Tinted.

## Updating without uninstalling
Every build (debug and release, local and GitHub Actions) is now signed with the fixed key `app/ajmal.keystore`, and
`versionCode` rises automatically. Install a new APK straight over the old one (or `adb install -r app-debug.apk`).
**One-time catch:** the first build with this key has a different signature from the old random debug-key build, so
uninstall once (Settings → Backup/Export first if you want your layout), and never again after that.
Keep `ajmal.keystore` in the repo and don't change it; if it changes, Android treats the app as a different one.
