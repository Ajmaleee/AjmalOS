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
