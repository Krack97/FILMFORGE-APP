# FILMFORGE

Native Android film-look camera. First device target: Samsung Galaxy Tab A9 (SM-X115). Android 10+.

## First version
- Live color recipes: Original, Amber 35, Silver, Night Blue, Soft Cinema.
- The same Media3 color effects feed preview and saved videos/photos.
- Photo and video modes, front/rear cameras, tap-to-focus, exposure compensation.
- Video targets 1080p/30fps, falling back to supported quality; actual capability depends on the camera.
- Optional microphone; camera works when microphone permission is denied.
- Photos go to Pictures/FILMFORGE; videos to Movies/FILMFORGE.
- No account, no network permission, no uploaded footage.

## Get the APK without Android Studio
1. Open the repository's Actions tab.
2. Open the latest successful **Build Android APK** run.
3. Download **FILMFORGE-Android** under Artifacts and unzip it.
4. Copy app-debug.apk to the tablet and install it.

This is a development APK, not a Play Store release. Physical camera, audio, rotation and performance testing is required on the tablet. Recipe rendering uses Google's experimental CameraX Media3 adapter.

## Development
Build on GitHub Actions. For a configured cloud terminal, use JDK 17, Gradle 8.11.1 and Android SDK 35, then run:
```sh
gradle :app:assembleDebug :app:lintDebug
```

## Next
Grain/halation, manual shutter/ISO/white balance, skin-tone controls and AI enhancement are not implemented yet. Cameras cannot switch mid-recording in this version; stop recording first.

## Device acceptance checks
- Deny camera, retry from Settings; deny only microphone and record a silent clip.
- Take one photo and record one clip with every recipe; verify Gallery files match the preview.
- Test both lenses, rotate in photo/video mode, background during recording.
- Check exposure limits, tap focus, denied/unsupported camera, insufficient storage.
- Confirm the actual video resolution and frame rate on the Tab A9.
