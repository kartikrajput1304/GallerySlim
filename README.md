## GallerySlim
### APK Download Link : https://github.com/kartikrajput1304/GallerySlim/releases/tag/v1.0.0
Free up space on your Android phone without losing your memories — a personal learning project.

GallerySlim shrinks photos and videos into smaller copies that are checked to look the same, finds what's worth
deleting (old screenshots, chat forwards, blurry shots, duplicates, expired tickets), and moves photos to your
computer over your own Wi-Fi with the **GallerySlim Receiver** — then lets you bring them back. Everything happens
on your devices: no account, no cloud, no tracking. Nothing is deleted without your confirmation; originals go to
Android's 30-day trash first.

## What's in this repository

| Folder | What it is |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose, Room, WorkManager, Media3, on-device ML Kit). minSdk 31, target 36. |
| `receiver/` | GallerySlim Receiver for Windows, macOS and Linux (Go, standard library only). |
| `docs/` | Design (`GallerySlim-Design-v2.md`), protocol (`protocol-gs2.md`), architecture review, feature reference (`features/GallerySlim-Features.pdf`), test plans and results (`testing/`). |
| `tools/` | Helper scripts (e.g. `phone.sh` for wireless adb). |

## Try it

Download the latest test build from the **Releases** page: `GallerySlim.apk` for Android; the Receiver can be
downloaded from the phone itself (Send to computer → open the shown address on your computer).

This is a test build and the Receiver is not code-signed, so Android, Windows and macOS show "unknown app"
warnings on first install. **Back up your photos before testing.**

## Build it yourself

Android (JDK 17, Android SDK 36):

```
./gradlew :app:assembleDebug        # debug build
./gradlew :app:assemblePhone        # smaller, shrunk build for nearly full phones
./gradlew :app:testDebugUnitTest    # unit tests
```

Receiver (Go 1.24+):

```
cd receiver
go test ./...
./build.sh                          # builds Windows, macOS and Linux into ../dist/receiver
```

## How it keeps photos safe

- Every smaller copy is verified against its original before anything is queued.
- Originals leave the gallery only after you confirm, and go to Android's 30-day trash.
- Before any clear or delete, the app re-checks the original's fingerprint; changed files are never touched.
- Only two actions delete for good, each with an in-app confirmation plus Android's own dialog.
- Transfers to the Receiver are paired with a QR code + fingerprint, encrypted, and verified byte for byte.

See `docs/GallerySlim-Design-v2.md` §7–8 for the full safety and security model.
>>>>>>> 69c9245 (GallerySlim: Android app, GallerySlim Receiver, docs and tests)
