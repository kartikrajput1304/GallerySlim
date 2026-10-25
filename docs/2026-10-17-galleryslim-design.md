# GallerySlim — Final Design & Implementation Plan

**One-line pitch:** Free up space without deleting a single memory — an Android app that shrinks the phone's photos and videos on-device, with a verified quality guarantee and a reversible safety vault.

Date: 2026-10-17 · Status: approved-pending-review · Owner: Kartik Rajput

---

## 1. Intent & why

People with low-storage phones (62% of Indian users run out of space every 3 months; 65% have regretted deleting precious photos — SanDisk/YouGov surveys) face a bad choice: pay for cloud subscriptions or delete memories. Android users are subscription-averse (~31% pay for storage vs 70% on iOS). GallerySlim reclaims space **on the device, for free, without deleting anything**, by re-encoding media into modern formats at visually identical quality.

Market gap verified (Oct 2026): no Android app shrinks photos AND videos together with hardware HEVC transcoding; closest competitors are video-only (Easy Video Compressor) or iOS-only (Photo Squash, CompressAll). Competitors paywall metadata preservation; we do it free by default.

## 2. Goals / Non-goals / Anti-goals

**Goals**
- Reclaim 30–80% of gallery space with no visible quality loss, verified per file.
- Shrunk media stays in the normal gallery, viewable by every app, no special viewer.
- Zero subscription; fully on-device; works offline.
- Safe by construction: an original is never deleted until its replacement is verified.

**Non-goals (v1)**
- No cloud backup/sync of any kind.
- No iOS version (iPhones already shoot HEIC/HEVC and have iCloud Optimize Storage).
- No photo editing features.
- No neural/learned compression (researched and rejected: on-device models are too heavy and their output needs the same network to view — breaks the "opens anywhere" requirement; revisit ~2028).

**Anti-goals (failure even if it "works")**
- Any photo visibly degraded without the user choosing an aggressive preset.
- Any silent data destruction: dropped Ultra HDR layers, lost EXIF dates/GPS, stripped motion-photo video without consent.
- Battery drain or thermal throttling the user notices.

## 3. Core insight

Phone photos are already compressed (JPEG/HEIC), so literal "compress → decompress to view" gains ~0–5%. The working approach is **transcoding to more efficient codecs** — and Android 12+ displays HEIC/AVIF natively, so there is no decompress step at all. The original "restore when I want it" idea survives as the reversible JPEG XL vault (tier C below).

## 4. Compression tiers (user picks per album; Gentle is default)

| Tier | Mechanism | Savings | Compatibility |
|---|---|---|---|
| **A. Gentle** | JPEG → JPEG re-encode via **Jpegli** (Google 2024; libjxl repo) | ~35% at high quality | Output is a standard JPEG — works on *any* Android version and every app |
| **B. Max** | JPEG → **HEIC** via androidx HeifWriter (hardware HEVC encoder) | 50–80% | Android 12+ system-wide |
| **C. Vault** | Originals packed to **JPEG XL** losslessly (bit-exact reversible, ~20% smaller than the JPEG) before tier A/B replaces them | n/a (safety feature) | App-internal only; restore returns the exact original file |
| **Video** | H.264 → **HEVC** via Media3 Transformer (hardware) | 40–60% | Universal playback |
| **Video (bonus)** | AV1 where a hardware encoder exists (Tensor G3+; detect via MediaCodecList at runtime) | more | Decode universal on Android 12+ |

**Non-JPEG images (builder's call, settled):** PNG screenshots — usually a big share of a low-storage gallery — go through the HEIC tier under the same verify loop (§5), which guards against text artifacts; animated GIFs and WebP are skipped in v1.

**Presets = one axis, not two.** A per-album policy is a single preset that fixes both the codec and the quality target: **Gentle** = Jpegli @ score ≥ 90, **Balanced** = HEIC @ ≥ 87, **Aggressive** = HEIC @ ≥ 75 (screenshots/forwards). Room stores the preset per album; the tier table above describes the mechanisms behind these presets, not a second user choice.

Notes:
- `.jpg` and `.jpeg` are the same format; all of the above applies to both.
- Jpegli and JPEG XL ship in one native library (libjxl) → a single NDK build covers tiers A and C.
- ffmpeg-kit is retired (binaries pulled from Maven Central); do NOT use it or unofficial forks. Media3 Transformer is the video engine.
- Skip files already efficient (HEIC/HEVC/AV1 input → leave alone or video-only re-check).

## 5. Quality verification (the trust mechanism)

Never trust the encoder's quality setting; verify the output:

1. Encode at a starting quality.
2. Score result vs original with **SSIMULACRA2** (images; ≥ ~90 = indistinguishable) or **VMAF** (video).
3. Below target → re-encode higher and re-check. Far above target → optionally step down to reclaim more.
4. Only a passing file may replace an original.

Presets map to score targets, not encoder settings (see §4 for the preset definitions). SSIMULACRA2 comes from libjxl tooling (same NDK build); if VMAF proves too heavy on-device, fall back to SSIM via a lightweight implementation and document the weaker guarantee — builder's call after benchmarking.

## 6. Safety rules (non-negotiable, in priority order)

1. **Write new → re-open and fully decode → verify score → only then request deletion of original.** Android's MediaStore `createTrashRequest`/`createDeleteRequest` shows a system confirmation dialog — a free second gate. **Automation model (settled):** these dialogs can't appear while the phone sleeps, so overnight runs only transcode and verify; verified originals go into a pending-deletion queue, and the user confirms the whole batch with one tap in the morning (`createTrashRequest` accepts a list of URIs → one dialog per batch). Consequence: the device needs headroom for both copies until confirmation. `MANAGE_MEDIA` (a settings-level grant that removes the dialogs) is a possible later power-user option, not v1.
   **Recorded exception — "Free this space now" (dashboard):** `createDeleteRequest` is called in exactly one place in the app (`ShrinkViewModel.freeSpaceIntentSender`) and nowhere else. It may only include originals that GallerySlim itself moved to the trash (records with status CONFIRMED whose original row is verifiably `IS_TRASHED = 1`), only while their smaller copy is verified present in the gallery (the record's `newUri` resolves to a published, non-pending, non-trashed row with size > 0), with both conditions re-checked record by record immediately before the request is built, and only after an in-app confirmation dialog ("Delete these originals for good?") followed by the system's own delete dialog. Anything failing a check is left in the trash to expire on its own; nothing else in the app ever deletes permanently.
2. **Ultra HDR detection:** photos carrying a gain map (recent Pixel/Samsung flagships) are either processed through libultrahdr preserving the gain map, or skipped with a count shown ("214 HDR photos skipped — support coming"). v1 may ship with skip-only; silent HDR stripping is forbidden.
3. **EXIF carry-over:** date, GPS, orientation copied to every output (ExifInterface), so gallery timelines never break. *Known v1 limitation (verified):* ExifInterface cannot write EXIF into HEIC — on HEIC outputs, orientation is baked into pixels and dates carried via MediaStore, but GPS tags are lost until an EXIF-capable HEIF muxer is adopted. Jpegli-tier (JPEG→JPEG) outputs keep full EXIF. Surface this in the preset UI copy.
4. **Undo window:** in v1 (Phases 2–4) replaced originals go to the system trash (30-day restore). The JXL vault (Phase 5) becomes an *additional* opt-in layer on top — until it ships, the honest claim is "30-day undo", not "forever reversible", and UI copy must say so.
5. **Motion photos:** embedded video is only stripped with explicit user consent; offer "export clips to a folder, then strip". The compression pipeline **skips unstripped motion photos by default** (re-encoding would silently drop the embedded video), reporting the count like Ultra HDR skips — so the strip-with-consent flow ships in Phase 2, not Phase 4.
6. **Cloud-sync warning:** if Google Photos backup is detected, warn that replaced files re-upload as new items.
7. Exclusions by default: favorites, last 30 days, and any album the user locks.

## 7. Smart cleanup layer (removes what compression can't shrink)

- **Motion-photo scanner:** Samsung/Pixel photos hide a ~3 s video inside the JPEG, often 2–3× the file size; no mainstream bulk tool exists. Report ("412 motion photos holding 2.1 GB of hidden video") and batch-strip with consent. Parser must handle both Samsung and Pixel container variants.
- **Exact duplicates:** file-hash (SHA-256) pass.
- **Near-duplicates:** perceptual hash + Hamming distance, hierarchical after the exact pass (per De Geest et al., IEEE CE Magazine 2026); present as groups, user picks keepers.
- **Blurry shots:** Laplacian-variance sharpness score → review screen, never auto-delete.
- **Category policies:** screenshots / WhatsApp / camera roll get separate default presets.

## 8. Technology stack (decided)

| Layer | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose |
| Min / target SDK | minSdk 31 (Android 12) / target SDK 36 (Play requirement for new apps since Aug 31 2026 — see docs/plans/play-store-launch-guide.md). Optional later: lower minSdk with tier-A-only reduced mode for older phones |
| Gallery access | MediaStore API (scoped storage), `createWriteRequest`/`createDeleteRequest`/`createTrashRequest` |
| Image encode | androidx HeifWriter (HEIC); libjxl NDK build (Jpegli + JXL + SSIMULACRA2) |
| Ultra HDR | libultrahdr (also in AOSP external/) |
| Video | Media3 Transformer (HEVC; AV1 if `MediaCodecList` reports a hardware encoder) |
| Metadata | androidx ExifInterface |
| Background | WorkManager — constraints: charging + idle + battery-not-low; small batches, thermal-aware (`PowerManager.getThermalHeadroom`) |
| DB | Room — processed-file ledger, savings stats, vault index, undo records |
| Image loading (UI) | Coil |
| Build | Android Studio, Gradle, NDK r27+ (16 KB page-size compliant — mandatory for Play from Nov 2025) |

Architecture: MVVM; modules `:app` (UI), `:core-scan`, `:core-encode` (JNI boundary), `:core-cleanup`, `:core-vault`, `:data`. All encode work behind a single `MediaTranscoder` interface so codecs can be swapped.

## 9. Build phases

**Phase 1 — Scanner & insights (a weekend; demo-able, zero write risk).**
MediaStore scan → format/size/age breakdown, savings estimate ("2,314 photos, 86 videos → free up to 9.4 GB"), motion-photo and duplicate counts. Deliverable: read-only dashboard.

**Phase 2 — Image pipeline.**
Tier A (Jpegli) and Tier B (HEIC, including PNG screenshots) with the verify loop (§5), EXIF carry-over, Ultra HDR detect-and-skip, motion-photo detect-skip-and-strip (consented), pending-deletion queue + morning batch confirmation, batch UI with progress + pause. First shippable value.

**Phase 3 — Video pipeline.**
Media3 Transformer H.264→HEVC with VMAF/SSIM check, AV1 capability detection. The headline gigabytes.

**Phase 4 — Smart cleanup + automation.**
Duplicate/blur review screens, per-album policies, overnight WorkManager automation (transcode-and-queue; see §6 rule 1), savings dashboard ("You've freed 12 GB").

**Phase 5 — JXL vault.**
Reversible archive of originals, restore flow, vault browser. Lives in a **user-chosen SAF location** (Documents folder, SD card, or USB drive) so it survives app uninstall — never app-internal storage. JPEG originals are repacked to JXL (~20% smaller); video/HEIC/PNG originals are vaulted as plain copies (no JXL savings apply). Shares the libjxl build from Phase 2.

Rough effort: polished v1 (through Phase 4) ≈ 4–8 weeks part-time for one developer newer to Android; Phase 5 adds ~1–2 weeks.

## 10. Device support

- Any brand on Android 12+ (standard APIs only). Galaxy S24-class devices are ideal (fast HEVC encoder, many motion photos).
- Hardware HEVC encode exists on essentially all chips of the last ~8 years, including budget.
- OEM background-kill behavior (Samsung One UI, Xiaomi MIUI/HyperOS) must be tested explicitly; follow dontkillmyapp.com mitigations.

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Data loss | §6 rule 1 ordering; system trash; vault; verify-decode before delete |
| Lossy is one-way | Vault (tier C); honest UI copy; Gentle default |
| Ultra HDR stripping | Detect-and-skip in v1; libultrahdr pass-through later |
| Google Photos re-upload | Detect + warn |
| NDK build complexity (libjxl) | Isolate in `:core-encode`; Phase 2 can ship HEIC-only if Jpegli build stalls, Jpegli follows |
| On-device metric cost | Benchmark SSIMULACRA2 early (Phase 2 spike); fallback path documented in §5 |
| OEM battery killers | Constraint-based WorkManager + foreground service during active batches + user guidance |
| Play policy (broad photo access) | Core use case fits the Photos & Video permission exception; write the declaration around "storage management of user media" |

## 12. Installation & distribution

**Development (day one):** enable Developer Options (tap Build number ×7) → USB debugging → phone appears as run target in Android Studio → Run ▶ installs directly. Wireless debugging works on same Wi-Fi.
**Testers:** signed release APK shared directly (sideload), or Firebase App Distribution.
**Google Play:** $25 one-time account; new personal accounts need a closed test (~12 testers, 14 days) before production; 16 KB page-size requirement already covered by the stack; expect photo-permission policy review.

## 13. Left to the builder

- Exact SSIMULACRA2 score thresholds per preset (tune on a ~200-photo test corpus: sky/faces/grass/text/night).
- Whether VMAF is feasible on-device or SSIM fallback is used.
- Vault storage format details (per-file JXL + Room index vs container).
- UI design language beyond Material 3 defaults.
- App name (GallerySlim is a working title).

## 14. Key sources

Jpegli: opensource.googleblog.com (Apr 2024), Cloudinary Pareto-front analysis · JPEG XL reversible transcode: jpegxl.info · SSIMULACRA2: libjxl tools man page, ModPageSpeed verify-then-re-encode · Ultra HDR: developer.android.com/media/platform/hdr-image-format, libultrahdr · AV1 encoders: Mishaal Rahman (Tensor G3), Qualcomm SD8G3 product brief (decode-only) · ffmpeg-kit retirement: arthenica/ffmpeg-kit README · Near-duplicate hashing: De Geest et al., IEEE CE Magazine 2026 · Storage-pressure surveys: SanDisk/YouGov via Eastern Mirror, Voice&Data · MobileNVC (WACV 2024) for the neural-codec rejection.


### §6 addendum (2026-10-22): second permanent-delete path
"Send the trash to computer" may permanently delete (createDeleteRequest) items that (a) GallerySlim itself moved
to the trash, (b) are still in the trash at the moment the request is built (re-queried), and (c) were fully sent
to an approved computer in this sharing session. In-app confirmation names the consequence ("the copy on your
computer will be the only one"), then Android's own dialog. Needs human review like the first exception.


### §6 addendum update (2026-10-24): "delete for good" needs a verified copy
The second permanent-delete path ("Send the trash to computer") is tightened: condition (c) is now that the file was
**verified** by the GallerySlim Receiver — the laptop re-read its saved copy from disk and its SHA-256 and size match
the phone's whole current file (protocol v2, docs/protocol-gs2.md). Files sent only through the browser page
("fully sent", not verified) can no longer be deleted for good; they stay in the trash and expire normally.
Verified state is stored in history.db (action VERIFIED, with the SHA-256). Needs human review with protocol v2.
