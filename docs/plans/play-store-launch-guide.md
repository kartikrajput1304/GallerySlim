# GallerySlim — Google Play Launch Guide

> **Status 2026-10-21.** Done in the app: `compileSdk`/`targetSdk` 36, `versionCode` 2 / `versionName` 1.0.0,
> predictive back enabled, edge-to-edge already on, dataSync services stop cleanly on the Android 15+ time limit,
> app notification icon, in-app Privacy screen (Settings > Privacy), privacy policy draft at `docs/privacy-policy.md`
> (needs a publisher contact and a hosted URL). Still to do: upload keystore and signed AAB, Play Console account,
> Data safety form, content rating, Photo and Video Permissions declaration.


Package: `com.galleryslim` · Developer: individual personal account, India · Date verified: **2026-10-17**
All policy facts below were re-verified against the cited official pages on this date. Where a fact could change, the citation is the page to re-check before acting.

---

## 1. One-page checklist timeline (today → live in production)

| When | Gate | Action |
|---|---|---|
| Day 0 | — | Pay $25, create **personal** Play Console account; start identity verification immediately (review "may take a few days") |
| Day 0–3 | — | In parallel: generate upload keystore, release build config, confirm targetSdk **36**, confirm 16 KB alignment of libjxl/libultrahdr `.so` files, build signed AAB |
| Day 2–5 | Identity verified | Create app in Play Console; accept Play App Signing ToS; fill **App content**: privacy policy URL, Data safety, content rating (IARC), **Photo and Video Permissions declaration** |
| Day 3–5 | — | Upload AAB to **Internal testing** first (no review friction, Data safety form not required there) and smoke-test the Play-delivered build on a real device |
| Day 5 | — | Promote to **Closed testing**; release reviewed (hours–days for a new account) |
| Day 5–7 | Closed test live | Recruit and opt in **≥ 12 testers** (aim 16–20); the 14-day clock starts only once the release is live and testers are opted in |
| Day 19–21 | 14 continuous days done | **Apply for production** from the Dashboard; answer the three questionnaire sections honestly |
| Day 21–28 | Production access granted | Review "usually takes seven days or less" |
| Day 28–30 | Production release review | Create production release, staged rollout 10% → live |
| **~Day 30–35** | **LIVE** | Realistic total: **4–6 weeks** from first upload; the 14-day test + two ~7-day reviews are the floor |

Risk buffers: photo-permission declaration pushback (+1–2 weeks if rejected once), tester count dropping below 12 mid-window (clock restarts — recruit extras).

---

## 2. Account setup & verification (personal account, India)

1. **Register** at play.google.com/console with a Google account you intend to keep forever. One-time **US$25** fee — MasterCard/Visa/Amex/Discover; prepaid cards rejected; use an Indian card with international transactions enabled (common failure point; enable it in your banking app first). Source: https://support.google.com/googleplay/android-developer/answer/6112435
2. **Choose "Personal" account type.** No D-U-N-S number needed (that's organizations only). You must be 18+.
3. **Identity verification** (blocks publishing until done): legal name + legal address, a government photo ID matching the legal name (for India: passport, driving licence, Aadhaar-class ID per the in-console list — the accepted list "depends on your geographic location"; the ID must be valid, in colour, not a photocopy), a private contact email and phone verified by OTP, plus a **public** developer email. Review takes a few days. Source: https://support.google.com/googleplay/android-developer/answer/14177239
4. **What the world sees** for a personal account: your **legal name**, your **country** (not full address), the public email, optional website. If "Kartik Rajput" as the public developer name is fine, proceed; there is no separate "brand name" for personal accounts.
5. **Android device verification:** new personal accounts must verify access to an Android device via the **Play Console mobile app** — install it on your test phone and complete this early. Source: https://support.google.com/googleplay/android-developer/answer/6112435
6. You will **not** need merchant/bank verification: GallerySlim is free with no IAP (zero-subscription is a design goal, §2 of the design doc).

---

## 3. Technical prep in the project

### 3.1 Upload keystore (one-time, keep forever)
```bash
keytool -genkeypair -v -keystore ~/keystores/galleryslim-upload.jks \
  -alias galleryslim-upload -keyalg RSA -keysize 4096 -validity 10000
```
Back it up off-machine (encrypted). Under **Play App Signing** this is only the *upload* key — Google holds the actual app-signing key, and a lost upload key **can be reset** via Play Console ("Request upload key reset"), so a lost keystore is recoverable but a multi-day nuisance. Play App Signing enrollment is automatic for new apps (you accept its ToS at app creation). Source: https://support.google.com/googleplay/android-developer/answer/9842756

### 3.2 Release build config (`app/build.gradle.kts`)
```kotlin
android {
  defaultConfig {
    applicationId = "com.galleryslim"
    minSdk = 31            // design §8
    targetSdk = 36         // REQUIRED for new apps since 2026-08-31, see below
    versionCode = 1        // monotonically increasing integer, bump every upload
    versionName = "1.0.0"
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("upload")
    }
  }
}
```

### 3.3 Target SDK — **36 is mandatory for this submission**
As of **August 31, 2026** (already passed), **new apps and app updates must target Android 16 (API level 36)** to be submitted to Google Play; an extension only runs to November 1, 2026 and is not worth filing for a new app. The design doc's "latest target" (§8) therefore means `targetSdk = 36` today. Source: https://developer.android.com/google/play/requirements/target-sdk (authoritative Help version: https://support.google.com/googleplay/android-developer/answer/11926878)

### 3.4 16 KB page size — applies to GallerySlim because of the NDK code
Apps targeting Android 15+ **must support 16 KB memory page sizes on 64-bit devices**; enforcement for new apps began Nov 1, 2025, and from **Feb 1, 2027** even updates are blocked without it. GallerySlim ships native code (libjxl/Jpegli/SSIMULACRA2, libultrahdr), so:
- Build with **NDK r28+** (16 KB alignment is the default; r27 needs `-Wl,-z,max-page-size=16384`) and AGP 8.5.1+.
- Verify every `.so` in the AAB: `check_elf_alignment.sh` or `llvm-objdump`, and test on a 16 KB emulator image.
Source: https://developer.android.com/guide/practices/page-sizes

### 3.5 AAB + R8
- Upload format is **.aab** — the App Bundle has been required for new apps since August 2021 (APKs are only for local sideloading/Firebase App Distribution testers). Source: https://developer.android.com/guide/app-bundle
- R8 keep rules for the JNI boundary in `:core-encode` — native method names must survive shrinking:
```
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.galleryslim.encode.jni.** { *; }
```
- Upload the **native debug symbols** and R8 `mapping.txt` with each release so Play Console deobfuscates crashes in both Kotlin and C++.

### 3.6 Manifest permissions (exactly these, nothing more)
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`. Do **not** add `MANAGE_EXTERNAL_STORAGE` (instant policy problem) or `MANAGE_MEDIA` in v1 (design §6 already defers it). Keeping `READ_MEDIA_VISUAL_USER_SELECTED` is both good UX and policy evidence: the app keeps working in "selected photos only" mode if the user refuses broad access — the policy requires a reasonable fallback.

---

## 4. Play Console listing

### 4.1 Store assets (required to publish) — source: https://support.google.com/googleplay/android-developer/answer/9866151
| Asset | Spec | GallerySlim suggestion |
|---|---|---|
| App icon | **512×512**, 32-bit PNG with alpha, ≤ 1024 KB | Shrinking-photo glyph; must match the in-app launcher icon |
| Feature graphic | **1024×500**, JPEG or 24-bit PNG (no alpha) | "Free up space. Keep every memory." + before/after bar |
| Screenshots | **min 2**, JPEG/24-bit PNG, each side 320–3840 px, longest ≤ 2× shortest; for Play promotion slots provide **≥ 4 at 1080×1920 (9:16)** | 1 dashboard ("free up to 9.4 GB"), 2 before/after quality comparison, 3 batch-confirm deletion dialog, 4 savings dashboard ("You've freed 12 GB") |
| Short description | ≤ 80 chars | "Shrink photos & videos on-device. Same quality, half the space. 100% offline." |
| Full description | ≤ 4000 chars | Lead with on-device + verified quality + system-confirmed deletion; avoid keyword stuffing (metadata policy rejections) |
| Video | Optional (YouTube URL) | Skip for v1 |

### 4.2 Privacy policy URL — required for every app
The Data safety form **cannot be completed without a privacy policy link** (source: https://support.google.com/googleplay/android-developer/answer/10787469). Host it at a stable URL (GitHub Pages is fine). For GallerySlim it must state, truthfully:
- The app accesses photos/videos **solely on-device** to analyze, re-encode, and (with explicit per-batch user confirmation via the Android system dialog) replace them.
- **No data leaves the device**; no analytics SDKs, no ads, no accounts, no network transmission of media or metadata.
- EXIF location data (`ACCESS_MEDIA_LOCATION`) is read only to copy it into the re-encoded file; it is never transmitted.
- Processing records (Room ledger) stay in app-private storage; the optional vault is written to a user-chosen location.
- Contact email; effective date; that deletion of app data = uninstalling / clearing storage.

### 4.3 Data safety form — suggested answers for this fully-offline app
Policy definition: **"Data that is only processed on the device is not collection"** (same source as above). GallerySlim transmits nothing, so:
- "Does your app collect or share any of the required user data types?" → **No** (photos/videos/EXIF are processed on-device only and never leave it).
- Security practices: data not encrypted in transit (N/A — nothing in transit); you can state users can request deletion N/A.
- **Keep it true**: do not add Firebase Analytics/Crashlytics later without updating this form — a form/behavior mismatch is an enforcement classic. (The §8-post-launch crash option below is chosen to preserve the "No" answer.)
- Internal testing tracks are exempt from the form; closed testing and beyond require it.

### 4.4 Content rating
IARC questionnaire (free, in App content): utility app, no UGC sharing, no violence/sex/gambling, no unrestricted web access → expect **Everyone / 3+ / PEGI 3**. Answer the "does the app share user location" question No (location never leaves device).

---

## 5. The Photo and Video Permissions declaration (the make-or-break gate)

Policy: apps targeting API 33+ may request `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` **only when the system Photo Picker cannot serve the core functionality**; all photos/videos are treated as personal and sensitive data. Source: https://support.google.com/googleplay/android-developer/answer/14115180

**GallerySlim's strongest argument — it is the textbook exception.** The policy itself names the qualifying category: apps whose *core function* is broad access, "such as gallery apps that manage all of a user's media." Write the declaration (short free-text per permission; keep each under ~250 chars) around these points:

1. **Core feature = whole-library storage management.** The app's single purpose is scanning the *entire* photo/video library to compute savings, find duplicates/motion-photos, and batch re-encode thousands of files. This is media management, not media *selection*.
2. **The Photo Picker is technically insufficient**: it returns user-picked URIs one session at a time. It cannot (a) enumerate the library to compute the savings dashboard ("2,314 photos → free up to 9.4 GB"), (b) run scheduled overnight WorkManager batches with no UI session, or (c) hash every file for duplicate detection. Asking a user to hand-pick 2,000 files per night is not a workable substitute.
3. **Access is minimized and user-controlled anyway**: `READ_MEDIA_VISUAL_USER_SELECTED` is supported (partial-access mode degrades gracefully to "compress only the photos you selected"), every deletion goes through the OS `createTrashRequest` confirmation dialog, and nothing is uploaded (fully offline).
4. In the declaration's video/demo field, upload a short screen recording showing: library scan → savings estimate → batch compress → system deletion dialog. Reviewers act on what they can see in 2 minutes.

**The risk:** Google's standard pushback email says "use the Photo Picker instead"; the FAQ explicitly warns that having your own picker UI "does not automatically qualify." If the first reviewer misreads GallerySlim as a compressor where users pick a few files, you'll get a removal-warning/rejection. Counter it by making the *whole-library* nature unmissable: the store description, screenshots, declaration text, and demo video should all lead with the full-gallery scan and the savings dashboard, not with single-file compression. If rejected, reply via the appeal link restating points 1–2 and citing the policy's own "gallery apps that manage all of a user's media" language, and point at the dashboard in the demo video.

**The other two permissions:** `READ_MEDIA_VISUAL_USER_SELECTED` and `ACCESS_MEDIA_LOCATION` are not part of this declaration form; they need no Play declaration today. `ACCESS_MEDIA_LOCATION` is still a runtime permission — request it lazily (only when EXIF-GPS carry-over actually runs) and explain in-app why.

---

## 6. Closed testing — the personal-account gate

Verified current rules (source: https://support.google.com/googleplay/android-developer/answer/14151465):
- Applies to **personal accounts created after Nov 13, 2023** — i.e., yours.
- **At least 12 testers must be opted in to your closed test when you apply for production access**, and each must have been opted in **continuously for the preceding 14 days**. A tester who opts out and rejoins restarts their own 14-day clock — so recruit **16–20** to survive dropouts.
- After applying ("Apply for production" on the Dashboard, three question sections: about the test, the app, production readiness), **review usually takes seven days or less**. Insufficient tester *engagement* (not just count) can send you back to testing — Google increasingly checks that testers actually used the app.

**Recruiting 16–20 real testers for GallerySlim:**
- Easiest honest pool: colleagues, family, college friends — everyone in India has a storage-starved phone; pitch it as "free up 5 GB this week." Collect their Google-account emails into a Play Console email list (or use a Google Group).
- Communities: r/AndroidClosedTesting and `testerscommunity`-style swap groups exist, but swap-testers who never open the app are exactly the "engagement" failure mode — prefer people who will genuinely run a compression batch.
- Give testers a task list (scan library → compress one album → confirm the deletion dialog → check photos still open in Google Photos) and a WhatsApp group for feedback; screenshots of their savings double as engagement evidence and marketing material.
- Tell testers the opt-in link must stay opted-in for the full 14 days.

Before closed testing, use the **internal testing** track (≤ 100 testers, instant, no Data safety form needed) to validate the Play-signed build — Play App Signing re-signs your AAB, which can surface signature-dependent bugs.

---

## 7. Review timelines, likely rejections, and recovery

**Timelines:** new-developer first releases get full human-ish review — expect hours to ~7 days per track promotion; the production-access application is "usually seven days or less"; budget both.

**Most likely rejection reasons for this app, in order:**
1. **Photo and Video Permissions declaration rejected** ("use Photo Picker") — see §5 recovery: appeal with the gallery-management exception language + demo video. Don't silently resubmit the same text.
2. **Data safety mismatch** — e.g., adding a crash SDK that phones home while the form says "no collection." Keep the app genuinely offline or update the form first.
3. **Metadata policy** — keyword-stuffed title/description ("photo compressor cleaner booster space saver…"). Keep the title "GallerySlim" + one function phrase.
4. **Misleading claims** — "no quality loss" must stay qualified ("no *visible* quality loss, verified per file"); design §6 rule 4 already mandates honest "30-day undo" copy — mirror that in the listing.
5. **Broken core flow during review** on a reviewer device without media — make the empty-library state work cleanly.

**On rejection:** the email names the policy; fix or appeal (Play Console → Policy status → appeal form). Appeals get human review in ~2–7 days. Never create a second account to route around a rejection (association = ban for both).

---

## 8. Post-launch

- **Staged rollout:** release to production at **10% → 25% → 50% → 100%**, holding each stage ~2–3 days while watching ANRs/crashes. You can halt a staged rollout instantly but can never downgrade `versionCode` — keep the halt button in mind on day one.
- **Crash monitoring, free, without breaking the "no collection" Data safety answer:** use **Play Console's built-in Android Vitals** (crashes + ANRs, aggregated by Google Play itself, no SDK in your app, no Data safety impact) plus uploaded native symbols for the libjxl JNI layer. Avoid Firebase Crashlytics in v1 — it is free but is an in-app collector that forces a Data safety form change. ACRA-to-email is a middle option if Vitals proves too thin for NDK crashes.
- **Update cadence:** first patch release ~1–2 weeks post-launch (reviewer-device and OEM-specific bugs: Samsung/Xiaomi background kills, §10 of the design). Then every 3–6 weeks through Phases 3–5 (video pipeline, cleanup, vault). Each update: bump `versionCode`, re-run the 16 KB alignment check (any libjxl rebuild), keep targetSdk current — **API 36 is already required; watch for the API 37 requirement around Aug 31, 2027**.
- **Watch Play Console inbox weekly:** policy declarations get re-reviewed retroactively; the photo-permission declaration must be re-affirmed whenever you change how media access works.

---

## Verified sources (as of 2026-10-17)
- Closed testing for personal accounts (12 testers / 14 days / 7-day review): https://support.google.com/googleplay/android-developer/answer/14151465
- Registration fee & account requirements ($25, device verification): https://support.google.com/googleplay/android-developer/answer/6112435
- Identity verification for personal accounts: https://support.google.com/googleplay/android-developer/answer/14177239
- Photo and Video Permissions policy: https://support.google.com/googleplay/android-developer/answer/14115180
- Target API level (API 36 for new apps since 2026-08-31): https://developer.android.com/google/play/requirements/target-sdk · https://support.google.com/googleplay/android-developer/answer/11926878
- 16 KB page sizes (Android 15+ targets; updates blocked from 2027-02-01): https://developer.android.com/guide/practices/page-sizes
- Play App Signing / upload key reset: https://support.google.com/googleplay/android-developer/answer/9842756
- App Bundle required for new apps: https://developer.android.com/guide/app-bundle
- Data safety form & privacy policy requirement: https://support.google.com/googleplay/android-developer/answer/10787469
- Store listing asset specs: https://support.google.com/googleplay/android-developer/answer/9866151
