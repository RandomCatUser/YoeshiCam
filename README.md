# OldCam 2011

A Flutter camera app with a 2011-era skeuomorphic chrome UI and a warm
"When I Fly Towards You" (Su Zaizai digicam) film look.

## Features

- **Photo & video** capture (sliding PHOTO/VIDEO mode switch)
- **Vintage FX** — warm faded film grade (lifted blacks, soft highlight bloom,
  vignette, fine grain) applied to captured photos in a background isolate;
  the viewfinder shows a matching live overlay
- Saves to **DCIM/Camera** on Android (via `gal`) / Photos library on iOS
- Tap-to-focus/exposure with animated focus bracket
- Flash cycle (auto/on/off), 3x3 grid, front/back flip
- Recording timer with blinking REC dot, last-shot thumbnail viewer

## Build the APK

The APK is built automatically by GitHub Actions on every push to `main`:

1. Open the **Actions** tab → **Build APK** run.
2. Download the `yoeshi-cam-apk` artifact; it contains `app-release.apk`.

To build locally (needs the Android SDK):

```
flutter pub get
flutter build apk --release
```

APK output: `build/app/outputs/flutter-apk/app-release.apk`

## Tests

```
flutter analyze
flutter test
```
