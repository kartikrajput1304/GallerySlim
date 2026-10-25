# Agent coordination — GallerySlim continuous improvement

Two agents work on this repo at the same time while the developer tests on
their Galaxy S24. These rules are binding for both.

## Roles
- **Medic** (live-fix): watches the phone's logs, diagnoses failures from
  evidence, fixes them, and is the ONLY agent that installs builds on the phone.
- **Scholar** (research): finds techniques in papers/engineering write-ups,
  implements the ones that fit, never installs.
- **Lens** (feature): builds the original-vs-shrunk comparison screens
  (ui/compare/**), never installs.

## Hard rules (both)
1. **Safety invariants are untouchable** (design doc §6): originals leave only
   via `MediaStore.createTrashRequest` after user confirmation; nothing is
   published unless verified; the overnight worker never deletes; Ultra HDR and
   unstripped motion photos are never lossy-converted; fail CLOSED on doubt.
2. **No new dependencies** (gradle/libs.versions.toml, build.gradle.kts) —
   company policy requires explicit developer approval. Write proposals in
   `PROPOSALS.md` instead.
3. **Snapshot before every change**:
   `cd ~/galleryslim && tar czf .snapshots/src-$(date +%Y%m%d-%H%M%S)-<agent>-<slug>.tgz app/src`
4. **Claim before editing**: append `CLAIM <agent> <path> <time>` under
   "Claims" below; skip/coordinate any path the other agent has claimed;
   replace with `DONE <agent> <path> <time>` when your build is green.
5. **One build at a time** — always wrap Gradle in the lock:
   `flock ~/galleryslim/.build.lock ~/tools/gradle-8.11.1/bin/gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug`
   A change is only "done" with ALL unit tests green. Never delete or weaken
   an existing test to make it pass.
6. **Every change gets a test** for its pure logic, and a line in "Changelog".
7. **Phone is read-mostly**: allowed adb = `logcat -d`, `run-as … cat`
   (pull app log/db), `dumpsys`, `df`, `content query`, `grep/stat/ls/od` on
   media files, and (Medic only) `install -r`. Never delete, move, edit or
   push files on the phone; never change device settings.
8. **Privacy**: logs contain the developer's file names. Never put file names,
   paths or log lines into web searches or any external service.
9. Small, reviewable changes. If unsure whether a change is safe, propose it
   in `PROPOSALS.md` instead of implementing it.

## Install protocol (Medic only)
Install only when no batch is running on the phone: the newest `[batch] … start`
line in the app log must have a matching later `[batch] … end`. Install under
the build lock: `flock ~/galleryslim/.build.lock ~/galleryslim/tools/phone.sh install`.

## Claims
(append below)
CLAIM Lens app/src/main/java/com/galleryslim/ui/compare/** 2026-10-20 12:13
CLAIM Lens app/src/test/java/com/galleryslim/ui/compare/** 2026-10-20 12:13
CLAIM Lens app/src/main/java/com/galleryslim/ui/PendingConfirmScreen.kt 2026-10-20 12:13
CLAIM Lens app/src/main/java/com/galleryslim/ui/AppNavHost.kt 2026-10-20 12:13
CLAIM Lens app/src/main/java/com/galleryslim/data/GsDatabase.kt 2026-10-20 12:13 (additive ShrinkDao query only)

RELEASED all Lens claims 2026-10-20 12:23 — agents stopped by developer; no files were changed.
DONE Lens app/src/main/java/com/galleryslim/ui/compare/CompareMath.kt 2026-10-20 13:07
DONE Lens app/src/test/java/com/galleryslim/ui/compare/CompareMathTest.kt 2026-10-20 13:07
DONE Lens-photo app/src/main/java/com/galleryslim/ui/compare/Photo*.kt 2026-10-20 13:35
DONE Lens-photo app/src/test/java/com/galleryslim/ui/compare/Photo*Test.kt 2026-10-20 13:35
DONE Lens-video app/src/main/java/com/galleryslim/ui/compare/Video*.kt 2026-10-20 13:34
DONE Lens-video app/src/test/java/com/galleryslim/ui/compare/Video*Test.kt 2026-10-20 13:34
DONE Lens-integrate app/src/main/java/com/galleryslim/ui/compare/CompareScreen.kt 2026-10-20 13:41
DONE Lens-integrate app/src/main/java/com/galleryslim/ui/PendingConfirmScreen.kt 2026-10-20 13:41
DONE Lens-integrate app/src/main/java/com/galleryslim/ui/AppNavHost.kt 2026-10-20 13:41
DONE Lens-integrate app/src/main/java/com/galleryslim/data/GsDatabase.kt (additive ShrinkDao query only) 2026-10-20 13:41
DONE Lens-fix app/src/main/java/com/galleryslim/ui/compare/** 2026-10-20 13:56
DONE Lens-fix app/src/test/java/com/galleryslim/ui/compare/** 2026-10-20 13:56
DONE Lens-fix app/src/main/java/com/galleryslim/ui/PendingConfirmScreen.kt 2026-10-20 13:56
DONE Lens-fix app/src/main/java/com/galleryslim/ui/AppNavHost.kt 2026-10-20 13:56

RELEASED all claims 2026-10-20 14:00 — all background work stopped by developer; build green (338 tests).

## Changelog
(append below: `<time> <agent> <what changed> — <tests> — installed? y/n`)
2026-10-20 13:07 Lens added ui/compare/CompareMath.kt (pure-JVM compare logic: RTL-safe wipe, synced zoom/pan clamp, fit/1:1, viewport->source region + pow2 inSampleSize with heap cap, EXIF raw-region mapping, SSIM verdict, size/duration/bitrate formatting, video drift re-seek) + CompareMathTest (81 tests) — tests 214 failures 0 errors 0 — installed? n
2026-10-20 13:34 Lens-video added ui/compare/Video*.kt video comparer (not yet wired into nav): VideoCompareScreen (Side by side = 2 platform MediaPlayers on TextureViews, one transport, follower re-seek on >100 ms drift with learned seek lead, original muted by default; Flip = one shared surface, switch keeps position; seek bar + time labels; "Compare this frame" = getFrameAtTime(OPTION_CLOSEST) for both on IO, heap-capped; info card via VideoCodecProber; Keep original / Looks good callbacks only, no trash/delete path; players released on ON_STOP + dispose; prepare errors shown in-pane), pure VideoCompareLogic + VideoSyncCoordinator + VideoCompareLogicTest (31) + VideoSyncCoordinatorTest (36) — tests 314 failures 0 errors 0 — installed? n
2026-10-20 13:35 Lens-photo added ui/compare/Photo*.kt photo comparer (not yet wired into nav): PhotoCompareScreen (Uri pair) + PhotoCompareScreen/PhotoComparer (caller-owned Bitmaps, for video "Compare this frame"); Wipe (RTL-safe draggable divider, TalkBack slider) / Side by side (row or column), press-and-hold flip, ONE synced zoom/pan (pinch, fling, double-tap, "100%"/"Fit" toggle), screen-sized ImageDecoder base (software, EXIF-oriented) + debounced off-main BitmapRegionDecoder tiles for BOTH files at high zoom (EXIF raw-region mapping, ImageDecoder-crop fallback, heap-scaled budget, stale decodes cancelled, old tiles recycled, both sides swapped together); info card (sizes/% smaller, resolution+format, SSIM verdict + raw score); Keep original / Looks good callbacks only, read-only, no trash/delete path; pure PhotoCompareModel + PhotoCompareModelTest (33) — tests 314 failures 0 errors 0 — installed? n
2026-10-20 13:41 Lens-integrate wired Compare: data/GsDatabase.kt additive read-only ShrinkLookupDao.byId (separate DAO so existing ShrinkDao test fakes stay valid; no schema/version change); ui/compare/CompareScreen.kt (loads record off-main, loading state, IMAGE/MOTION_STRIP -> PhotoCompareScreen, VIDEO -> VideoCompareScreen with 'Compare this frame' -> photo Wipe comparer over caller-owned bitmaps recycled on leave; not-found / no-longer-pending / file-missing states; Keep original -> ShrinkViewModel.keepOriginal, Looks good -> back; no trash/delete path); route compare/{recordId} in ui/AppNavHost.kt (pop guarded to RESUMED entry); 'Compare' 48dp affordance on each PendingConfirmScreen row (TalkBack 'Compare original and shrunk <name>') + CompareEntryTest (10) — tests 324 failures 0 errors 0 — installed? n
2026-10-20 13:56 Lens-fix lens-fix-1 (12 confirmed Compare review issues): AppNavHost Keep original gated on RESUMED entry like the pop; PendingConfirmScreen blocks Compare/Keep original/CTA while a trash request is in flight + saveable launched ids re-resolved if the snapshot was lost (CompareEntry.resolveLaunchedBatch), row actions wrap to a 2nd line at fontScale>=1.3; PhotoDecoders never demotes BitmapRegionDecoder on a decode failure, OOM retried once at 2x sample, exponential failure backoff (RegionDecodeBackoff), ImageDecoder crop fallback only when its whole-image target fits the region budget; CompareScreen keeps video comparer state (position/mode/sound) across 'Compare this frame' via SaveableStateHolder; wipe handle ignores downs while a canvas gesture is active (pinch 2nd finger); VideoComparePlayer tracks pending seeks (OnSeekComplete, stale after 3 s) and VideoSyncCoordinator.tick skips calibration/re-seek while the follower seeks; completion ends playback when no active player runs after a leader switch; photo layout: landscape side panel + canvas min height; failed photo load hides 'Looks good' + live-region message; video 'Visually identical' tint gs.primary — tests 338 failures 0 errors 0 — installed? n
