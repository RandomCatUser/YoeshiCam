# Zaizai Cam — Retro Y2K Digicam Camera App

An Android camera app that recreates the "Su Zaizai" / *When I Fly Towards
You* digicam aesthetic (warm color grade, film grain, vignette, date stamp,
soft border) in real time, plus an on-device beauty smoothing filter, and
saves photos straight to the phone's gallery.

## How to build

1. Open this folder (`SuzaizaiCam/`) in **Android Studio** (Koala/2024.1 or
   newer recommended). Studio will detect it as a Gradle project and sync
   automatically — it downloads CameraX, ML Kit, and AndroidX from Google's
   Maven repo, so you'll need normal internet access the first time.
2. Let Gradle sync finish, then hit **Run ▶** on a physical device (an
   emulator's virtual camera works but looks flat — a real device shows the
   effect much better).
3. Grant the camera permission when prompted.

Minimum SDK 24 (Android 7.0), target/compile SDK 34.

## What's implemented

- **Live filtered preview** — CameraX `Preview` feeds a `SurfaceTexture`
  that's rendered through a single GLSL fragment shader
  (`GLRenderer.kt`) applying, every frame:
  - warm Y2K color grading (lifted blacks, amber-shifted highlights)
  - animated film grain
  - vignette
  - a cheap real-time "beauty" smoothing blend (9-tap blur mixed with the
    original, driven by the Beauty slider)

  This all runs on the GPU, so the preview stays smooth without taxing the
  CPU — no per-frame Bitmap allocation, no blocking work on the UI thread.

- **Full-resolution capture** — `PhotoProcessor.kt` bakes the same look
  into the actual saved photo (not just the low-res preview):
  - on-device ML Kit face detection (`FaceBeautyHelper.kt`) finds faces so
    skin smoothing concentrates on them rather than blurring the whole
    image; falls back to a gentle whole-frame smoothing if no face is found
  - warm color grade + vignette + grain baked in with `Canvas`/`ColorMatrix`
  - a cosmetic border and a live date-stamp drawn in the corner
- **Saves to the gallery** via `MediaStore` (`Pictures/ZaizaiCam/`), fully
  scoped-storage compliant — no legacy storage permission needed on Android
  10+.
- **Beauty and filter intensity sliders**, front/back camera switch, shutter
  flash animation, and a live-updating on-screen timestamp.

## Performance notes

- All preview effects are one GPU shader pass — no extra render targets, so
  it should hold 30fps+ on mid-range hardware.
- Face detection + Canvas-based baking only happen once per shutter press
  (on a background executor), not per frame, so they don't affect preview
  smoothness.
- The `boxBlurDownscaled` beauty approximation (shrink → blur → grow) is
  deliberately cheap; if you want true bilateral/edge-aware smoothing,
  swap it for a proper GPU bilateral filter or a library like GPUImage.

## Honest limitations

- This recreates the *aesthetic* (grain, warm grade, vignette, timestamp,
  soft border) — it isn't reverse-engineered from any specific proprietary
  app's source code, since that's genuinely unavailable.
- The beauty filter here is a fast blur-based approximation, not a
  full landmark-warp (eye enlarge / face slim) — extending
  `FaceBeautyHelper` with `FaceDetectorOptions.LANDMARK_MODE_ALL` gives you
  landmark points if you want to add mesh-warp effects later.
- Front-camera mirroring is applied to the saved photo but not yet to the
  live GL preview texture matrix — cosmetic, easy to add if you want the
  live preview mirrored too (flip `vTexCoord.x` in the vertex shader when
  `frontCamera` is true).
- No Gradle wrapper jar is bundled (binary file) — Android Studio will
  generate/download it automatically on first sync. If you build from the
  command line instead, run `gradle wrapper` once first.

## Project structure

```
app/src/main/java/com/suzaizai/retrocam/
  MainActivity.kt            – CameraX setup, permissions, UI wiring, capture flow
  CameraGLSurfaceView.kt     – GLSurfaceView wrapper exposing the renderer
  GLRenderer.kt              – OpenGL ES renderer + fragment shader (the "look")
  ShaderProgram.kt           – shader compile/link helper
  PhotoProcessor.kt          – bakes effects into the full-res still + saves to MediaStore
  FaceBeautyHelper.kt        – ML Kit face detection wrapper
app/src/main/res/
  layout/activity_main.xml   – viewfinder, sliders, shutter/switch buttons
  drawable/                  – border frame & shutter button shapes
  values/                    – strings/colors/theme
```
