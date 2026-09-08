# Yoeshi Cam — the Su Zaizai "When I Fly Towards You" camera

Recreates the warm, soft digicam "vibe" from *When I Fly Towards You*
("Su Zaizai's camera") in a modern, phone-grade Android camera app — with an
Apple-style camera UI, tap-to-focus, crisp full-resolution photos and
**GPU-rendered video with sound**.

## How to build

1. Open this folder in **Android Studio** (Koala/2024.1 or newer). Studio syncs
   automatically and downloads CameraX, AndroidX, ML Kit from Google's Maven
   repo, so you'll need internet the first time.
2. Let Gradle sync finish, hit **Run ▶** on a physical device (a real device
   shows the look and the focus/video features much better than an emulator).
3. Grant **camera** and **microphone** permissions when prompted (mic is needed
   for video sound).

Minimum SDK 24 (Android 7.0), target/compile SDK 34.

## What's implemented

### Apple-style camera UI
- Full-screen viewfinder with a minimal, iOS-like control set:
  - **Flash** button (Off → Auto → On cycle)
  - **Timer** button (Off → 3s → 10s)
  - **Vibe** toggle — the retro warm/soft grade, on/off
  - **Photo ⦿ Video** mode pill above the shutter
  - Large white **shutter** that turns into a red rounded square while recording
  - **Last photo thumbnail** (bottom-left) and **camera switch** (bottom-right)
  - A subtle live date-stamp in the corner, straight off the digicam

### Tap-to-focus
- Tap anywhere on the viewfinder → a yellow **focus ring** animates in, and the
  camera runs AF + AE metering at that point (`CameraControl.startFocusAndMetering`)
  with 3s auto-cancel. The ring auto-fades like iOS.
- The tap is mapped from viewfinder coordinates to sensor coordinates with the
  renderer's aspect-fit crop, sensor rotation, and front-camera mirroring taken
  into account, so the focus square lands where you actually touched.

### High-quality stills
- Full-resolution capture via CameraX `ImageCapture` in
  **`CAPTURE_MODE_MAXIMIZE_QUALITY`**, saved at JPEG quality 96.
- The "vibe" is applied as a *light, non-destructive* grade (subtle warm lift,
  gentle vignette, fine grain) so photos stay clean and crisp instead of the
  old heavy blur + harsh vignette.
- With the Vibe toggle off, the photo stays effectively natural — like a normal
  phone camera.
- A 1080p-class preview resolution strategy is requested so live view stays sharp.

### Fixed video (GPU + shader-matched)
- Video is no longer re-processed pixel-by-pixel on the CPU. Each frame is
  uploaded to the GPU and pushed through the **same vibe fragment shader the
  preview uses**, rendered into the video encoder's input surface and
  hardware-encoded (H.264, 8 Mbps, 30 fps). Recorded footage matches the
  viewfinder — no more shader/CPU mismatch, no more choppy re-encodes.
- **Audio**: the microphone is captured on a background thread (`AudioRecord`),
  encoded to **AAC** and muxed into the MP4 with the video (A/V timestamps
  aligned from a shared clock). If the mic permission is denied, it still
  records silently rather than failing.
- A front/back camera switch during recording safely finalises the clip first.

### Aspect-fit preview
- The landscape sensor feed is **center-cropped to fill the portrait view**
  instead of being stretched, so the viewfinder looks like a real camera.

### On-device everything
- No network calls at capture time. Face/beauty/grades all run locally.
- `MediaStore` drives gallery saving (scoped-storage safe, no legacy storage
  permission needed on API 29+): photos → `Pictures/YoeshiCam`, videos →
  `Movies/YoeshiCam`.

## Performance notes
- All preview effects are one GL shader pass. Still processing happens once per
  shutter press on a background executor.
- Video encoding is hardware Video/Audio `MediaCodec`; the filter runs on the
  GPU, so recording holds a smooth framerate on mid-range hardware.
- `ImageAnalysis` (fed to the recorder) is created only while recording, keeping
  the three-stream bind from breaking preview on picky devices.

## Honest limitations
- The vibe is an *aesthetic* recreation informed by the show — no proprietary
  source is reverse-engineered.
- "Beauty" smoothing is a fast blur-based approximation, not landmark-warp
  (eye enlarge / face slim).
- Video is recorded in the sensor orientation with an MP4 rotation hint
  (portrait clips play back upright).
- No Gradle wrapper jar is bundled — Android Studio generates it during the
  first sync. From the command line, run `gradle wrapper` once first.

## Project structure
```
app/src/main/java/com/suzaizai/retrocam/
  MainActivity.kt            – Apple-style UI, Photo/Video modes, tap-to-focus, capture flow
  CameraGLSurfaceView.kt     – GLSurfaceView wrapper + tap-to-focus plumbing
  GLRenderer.kt              – OpenGL ES renderer + vibe shader + aspect-fit crop
  ShaderProgram.kt           – shader compile/link helper
  PhotoProcessor.kt          – final still grade + save to MediaStore
  VideoRecorder.kt           – GPU render→H.264 surface encoder + AAC audio + mux
  YuvConverter.kt            – YUV_420_888 → ARGB conversion (video path)
  FaceBeautyHelper.kt        – ML Kit face detection wrapper (optional)
app/src/main/res/layout/activity_main.xml – Apple-style viewfinder layout
app/src/main/res/drawable/               – shutter, focus ring, flash/timer/vibe/switch icons
app/src/main/res/values/                 – strings/colors/theme
```