# GallerySlim — Phase 1 Implementation Plan (Scanner & Insights)

Design source: ../2026-10-17-galleryslim-design.md (approved 2026-10-17, all five review calls accepted).

## Scope (Phase 1 only — read-only, zero write risk)

A runnable Android app that:
1. Requests media-read permission (READ_MEDIA_IMAGES/VIDEO on 13+, READ_EXTERNAL_STORAGE on 12).
2. Scans the gallery via MediaStore (images + videos).
3. Classifies each item: JPEG / PNG / HEIC / other; video codec (H.264 vs HEVC/AV1) via MediaExtractor; flags motion photos and Ultra HDR photos by header/trailer signatures.
4. Estimates reclaimable space per design §4 ratios and shows a dashboard:
   "N photos, M videos → free up to X GB", with breakdown rows (JPEG→Jpegli, PNG→HEIC, H.264→HEVC, hidden motion-photo video, skipped: Ultra HDR / already-efficient).

Out of scope here: any writing, encoding, deleting (Phases 2+).

## Structure (single :app module for Phase 1; split into :core-* modules when Phase 2 adds the NDK encoder)

```
galleryslim/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/galleryslim/
    MainActivity.kt                 — permission gate + Compose host
    ui/DashboardScreen.kt           — stats dashboard
    ui/theme/Theme.kt               — Material 3, light/dark
    scan/MediaItem.kt               — data model + classification enums
    scan/MediaScanner.kt            — MediaStore queries (Android-dependent)
    scan/VideoCodecProber.kt        — MediaExtractor codec check (Android-dependent)
    scan/SpecialFormatDetector.kt   — pure-JVM byte-signature detection (motion photo, Ultra HDR, embedded-video size)
    scan/SavingsEstimator.kt        — pure-JVM savings math
    scan/ScanViewModel.kt           — orchestration, StateFlow<ScanUiState>
  app/src/test/java/com/galleryslim/scan/
    SpecialFormatDetectorTest.kt    — synthetic byte-array fixtures
    SavingsEstimatorTest.kt         — ratio & categorization cases
```

## Tech pins

Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 (+ compose plugin) · compileSdk 35 · minSdk 31 · Compose BOM · JUnit4 for JVM tests. No Room/Coil yet (nothing to persist or thumbnail in Phase 1).

## Detection heuristics (v1, refined later with real-device corpus)

- **Motion photo:** XMP in first 128 KiB contains `MotionPhoto` (Google `GCamera:MotionPhoto="1"`, `MicroVideo`) OR Samsung SEF trailer (`SEFT` in last 16 bytes) OR an `ftyp` MP4 box after the JPEG EOI marker. Embedded-video size ≈ bytes from the `ftyp` box start −4 to EOF.
- **Ultra HDR:** first 128 KiB contains `hdrgm:Version` or `urn:iso:std:iso:ts:21496`.
- Both operate on byte buffers → unit-testable without a device.

## Savings ratios (design §4; shown as "up to")

JPEG (plain) 35% · PNG 60% · H.264 video 45% · motion photo: embedded video bytes (strip) — JPEG part counted as skipped until stripped · Ultra HDR: skipped (0) · HEIC/HEVC/AV1/WebP/GIF: 0.

## Verification

1. JVM unit tests pass (`:app:testDebugUnitTest`).
2. `assembleDebug` produces an APK on this machine (SDK installed under ~/Android/Sdk).
3. On-device check is the user's step: enable USB debugging, `adb install` or Android Studio Run (design §12).

## Steps

1. Toolchain: unzip cmdline-tools + Gradle; `sdkmanager platform-tools "platforms;android-35" "build-tools;35.0.0"`; accept licenses.
2. Write Gradle config + manifest + theme + MainActivity skeleton (compiles empty).
3. Write pure-JVM logic + tests (SpecialFormatDetector, SavingsEstimator); run tests.
4. Write MediaScanner, VideoCodecProber, ScanViewModel, DashboardScreen.
5. Full build: unit tests + assembleDebug; generate Gradle wrapper so the project is self-contained for Android Studio.
6. Hand over: APK path + install instructions.
