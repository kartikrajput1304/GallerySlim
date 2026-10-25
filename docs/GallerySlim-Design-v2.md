# GallerySlim — System Design (v2, as built)

**Status:** current-state design as of 2026-10-24 (app 1.0.0, versionCode 2; Receiver 0.1.0), updated after the
architecture review (`docs/architecture-review-2026-10-24.md`).
**Supersedes for "how it works":** `docs/2026-10-17-galleryslim-design.md` (the original plan; its §6 safety rules and
addenda remain normative and are restated in § 7). Protocol details: `docs/protocol-gs2.md`.
**Audience:** engineers and reviewers who need to understand, change or audit the system.

---

## 1. Purpose and scope

GallerySlim frees storage on Android phones without losing memories. It does four things:

1. **Shrink** — re-encode photos and videos into smaller files that are verified to look the same; originals wait for
   the user's confirmation before going to Android's 30-day trash.
2. **Clean up** — find things worth deleting (old screenshots, chat forwards, blurry shots, duplicates, expired
   tickets/OTPs, old downloads) and let the user decide, never deleting on its own.
3. **Move** — send photos to a computer on the same Wi-Fi (browser page or the GallerySlim Receiver program), verify
   they arrived, then free the space.
4. **Keep it slim** — a storage goal, overnight work, standing rules, forecasts and nudges.

Out of scope: cloud storage, accounts, analytics, editing photos, deleting without the user.

## 2. Design principles

| # | Principle | Where it shows |
|---|---|---|
| P1 | Reversible first ("ladder of regret") | shrink → Ready to clear → 30-day trash → delete for good |
| P2 | Fail closed | unreadable photo = possible ID; unverified copy = not deletable |
| P3 | Prove, don't assume | verifier gates every copy; receiver re-hashes from disk before "verified" |
| P4 | Honest numbers | freed vs. still-in-trash shown separately |
| P5 | One decision at a time | minimal home, one button, guided flow, "All tools" behind it |
| P6 | Everything on the phone | no account, no uploads; network only for local Wi-Fi sharing |
| P7 | Defence in depth | Wi-Fi subnet + pairing + fingerprint + encryption + replay window |
| P8 | Design for bad conditions | nearly full phone, flaky Wi-Fi, sleep, wrong clocks, Hindi names |
| P9 | Small pure units | decisions are pure Kotlin/Go functions with tests; screens only render |

## 3. System context

```
            ┌──────────────────────────── Android phone ─────────────────────────────┐
            │  GallerySlim app                                                      │
 user ────▶ │   UI (Compose) ─ ViewModels ─ engines ─ Room DBs / prefs / files      │
            │        │                 │                                            │
            │        ▼                 ▼                                            │
            │   MediaStore (photos, videos, trash)   ML Kit (on-device OCR, faces,   │
            │   SAF folders (vault, clip export)      labels, barcodes)              │
            │   WorkManager (overnight, trip, reports, samples)                      │
            │   Share server (LAN HTTP :8765–8769, Wi-Fi interface only) + mDNS      │
            └───────────────▲──────────────────────────────▲─────────────────────────┘
                            │ gs1 (browser page, ZIP)      │ gs2 (encrypted, signed)
                    ┌───────┴───────┐              ┌───────┴────────────────────┐
                    │ laptop browser │              │ GallerySlim Receiver (Go) │
                    └───────────────┘              │ page on 127.0.0.1 → folder │
                                                   └────────────────────────────┘
```

External dependencies (all approved): Jetpack Compose, Room, WorkManager, Media3 Transformer 1.5.0, HeifWriter,
ExifInterface, Coil, CameraX, ML Kit (text, face, barcode, image labeling — all bundled/on-device), ZXing core.
The Receiver uses Go 1.27's standard library only.

## 4. Architecture

### 4.1 Layers

1. **UI** (`ui/`, Jetpack Compose): screens, navigation (`AppNavHost`), dialogs. No business rules.
2. **ViewModels** (`scan/ScanViewModel`, `shrinkflow/ShrinkViewModel`, `shrinkflow/CleanupViewModel`,
   `search/SearchViewModel`): hold state flows, start work, translate results for the UI.
3. **Engines and pure rules**: `shrink/` (photo/video encoders and verifiers), `cleanup/` (lanes, classifiers,
   duplicates), `shrinkflow/` (controller, planner, rules), `share/` (sessions, protocol), `scan/` (scanner, estimator).
4. **Platform adapters**: MediaStore, SAF, WorkManager workers (`work/`), foreground services, ML Kit wrappers.
5. **Storage**: three Room databases, SharedPreferences files, app-private files.

### 4.2 Packages (phone)

| Package | Responsibility | Size |
|---|---|---|
| `scan` | MediaStore scan, probe cache, savings estimate, codec probing, motion/Ultra HDR detection | 7 files |
| `shrink/image` | JPEG→HEIC, quality search, verifiers (SSIM/MS-SSIM/edge, SSIMULACRA2), lossless JPEG optimizer, motion-photo split | ~14 files |
| `shrink/video` | H.264→HEVC via Media3, bitrate/CQ planning, spot check, banding guard, space budget, light copy, trim | ~8 files |
| `shrinkflow` | batch controller, pending queue, trash ledger, album rules, room planner, quick-review queue, batch lock, view models | 14 files |
| `safety` | MediaStore gateway, identity fingerprints, safety gates, operations outbox + reconciler, quarantine | 8 files |
| `cleanup` | lanes, document classifier (ID shield), on-device vision, duplicates/bursts, contact sheets, expiry reader, chat diet, standing rules | 17 files |
| `share` | gs1 sessions, gs2 protocol, LAN HTTP server, mDNS responder, foreground service, trash export, hasher | 10 files |
| `history` | change log (Recent changes, archive map, verified copies) | 2 files |
| `search` | photo index (labels + OCR), ranking | 2 files |
| `storage` | free-space samples, forecast, widget, low-space alert | 2 files |
| `report` | monthly report, share card, cleanup tally | 1 file |
| `sharesmall` | "Share small" share-sheet target | 2 files |
| `vault` | SAF vault copy/restore | 2 files |
| `work` | overnight, trip, trash-watch workers, notifications | 4 files |
| `data`, `core`, `diag` | main Room DB, presets/contracts, diagnostic log | 4 files |
| `ui` | all screens | 46 files |

### 4.3 Receiver (laptop, Go)

`main.go` wires: `discover` (mDNS + subnet probe) → `client` (signed requests, signed-answer checks) → `receive`
(pairing, ≤3 streams, resume, save, re-hash, ack) → `store` (names, never-overwrite finalize, manifest) ;
`ui` (127.0.0.1 page with token, Host/Origin checks, hash-pinned CSP) ; `desktop` (native folder dialogs,
cloud-folder detection, keep-awake) ; `qr` (own encoder) ; `gs2` (keys, MACs, frames) ; `applog`.

## 5. Key flows

### 5.1 Scan (incremental)
`MediaScanner` queries Images and Video collections (id, name, mime, size, dates, favourite, bucket,
DATE_MODIFIED). For each item it reuses `ScanCache` (app file, versioned, atomic write) when size and modified time
match; otherwise it probes (JPEG header/trailer for motion photo SEF/XMP and Ultra HDR; `VideoCodecProber` for
codec/size/fps/bitrate/duration). Deleted ids drop out. `SavingsEstimator` categorises items (camera photos,
screenshots/images, videos, motion clips, HDR lossless, skipped) and estimates reclaimable bytes.

### 5.2 Shrink a photo
1. Routing (`ShrinkController.route`): JPEG/PNG → image tier; Ultra HDR → lossless-only; motion photo → skip
   (separate strip flow); efficient formats → skip.
2. Pre-gates: free-space check (`SpaceBudget`); cached decision (`ShrinkDecision` table: NO_SAVINGS /
   QUALITY_UNREACHABLE skip instantly, ACCEPT seeds the start quality); `JpegQuality.tooCompactForHeic`.
3. `QualitySearch` loop (≤6 encodes, early exits for hopeless/identical/predicted-too-big): HEIC via HeifWriter at
   quality q → decode → verifier (min of luma SSIM, MS-SSIM, edge similarity for screen content) against the preset
   floor → accept the smallest passing encode that saves ≥10 %.
4. Fallback: lossless JPEG Huffman re-optimisation (MPF-aware for Ultra HDR).
5. Publish: write to MediaStore with IS_PENDING=1 → record `ShrinkRecord` (PENDING_CONFIRM) → clear IS_PENDING.
   Light copy preset also downsizes to 2048 px; album rules / cooling can choose it per item.

### 5.3 Shrink a video
`HevcVideoShrinker`: probe → `BitratePlanner` (resolution/fps ladder × content complexity; screen recordings capped to
lower fps) → space budget on `files/transcode` → Media3 Transformer to HEVC (constant-quality encoder when the
device has one, else bitrate with fallback) → stall watchdog → spot check of sampled frames + banding guard →
publish as above. Light copy = 720p; Trim = clipped re-encode (no quality check; original queued like any shrink).

### 5.4 Ready to clear and the trash
Pending records are grouped by kind; the user may Compare each (photo wipe/zoom, synced video), Keep original
(deletes the shrunk copy, marks UNDONE), or clear all → `createTrashRequest` (system dialog) → CONFIRMED.
`TrashLedger` splits confirmed records into freed vs. still-in-trash (`IS_TRASHED`, `DATE_EXPIRES`). "Undo this run"
untrashes originals and deletes copies only when the original is back. "Free this space now" →
`createDeleteRequest` for CONFIRMED originals verifiably in the trash whose copy is verifiably published (§ 7, D1).

### 5.5 Clean up
- **Lanes** (`CleanupLanes`): old screenshots, old chat media, greetings & forwards, low quality, expired, old
  receipts & tickets, old downloads. Favourites and "keep until" items excluded.
- **ID shield**: ML Kit OCR → `DocumentClassifier` (ID, receipt, ticket, greeting, none, unknown). ID and unreadable
  (UNKNOWN) never appear in any lane. Lanes show only already-checked photos; checking runs in priority order
  (lane candidates first) and fills lanes progressively.
- **Expiry reader**: tickets expire the day after the printed travel date; OTP screenshots a day after capture.
- **Duplicates**: same-size → edge hash → SHA-256 (cached digests) exact groups; burst clusters (≤1.5 s apart, ≥3
  shots); dHash near-duplicates. Keeper = favourite > eyes-open score > sharpness > resolution > newest. Bursts can
  become a **contact sheet** (one grid image saved, other shots trashed).
- **Quick review**: one swipe queue over all lanes and duplicate extras; standing rules pre-select; one trash dialog.
- **Chat diet**: space per chat folder, review older than N months.

### 5.6 Make room, storage floor, trips, cooling
`RoomPlanner.plan(target)` builds rungs in order: SHRINK → LIGHT_COPY_OLD → EXPIRED → SUGGESTIONS until the estimate
covers the target. The guided "Get space back" flow presents one rung per screen. Overnight work
(`OvernightWorker`, charging + idle, ≤300 photos / 10 videos) sizes its batch to the floor gap when a floor is set and
posts an honest morning note. `TripWorker` runs rungs 1–2 the evening before a chosen date (light copies only when
cooling is on). Cooling: photos older than N years become light copies unless favourite; album rules win.

### 5.7 Send to computer — browser (gs1)
`ShareService` (foreground, dataSync) binds `LanHttpServer` to the phone's Wi-Fi IPv4 (no VPN), ports 8765–8769,
answers `galleryslim.local` via its own mDNS responder. Opening the page creates a session (random id + secret,
pinned to the laptop's IP) and shows a QR `gs1:<id>:<secret>`; the phone scans it, the owner confirms with
biometrics, the session is approved with the file list; the page downloads a month-sorted ZIP or single files.
"Fully sent" = the whole file/ZIP was written to the socket. Stops on Wi-Fi loss, task removal, 30 min idle.

### 5.8 Send to computer — Receiver (gs2)
The receiver creates K locally and shows `gs2:<sid>:<K>`; keys = HKDF(K, sid‖N). Requests are HMAC-signed with a
counter (replay window 1024); answers to authenticated requests are signed (`X-GS-RMac`); list and files stream as
AES-GCM frames (random 8-byte prefix per response, AAD = sid, resource, absolute offset, last flag). The receiver
writes `.part`, finalises without overwriting, **re-reads from disk**, hashes, and acks; the phone compares with its
own whole-file SHA-256 → **verified** (stored in `history.db` as VERIFIED). Resume via Range; ≤3 streams.

### 5.9 Trash export and delete for good
"Send to computer first" queues items GallerySlim itself trashed (shrink originals, clean-ups, burst extras) that are
still `IS_TRASHED=1`. After transfer, **only files verified by the Receiver in this run** and still in the trash can
be deleted permanently (`createDeleteRequest`, in-app confirm + system dialog). Earlier verifications are labels only.

### 5.10 Other features
Search (ML Kit labels + OCR into `search.db`, token ranking with synonyms) · Share small (share-sheet target; 2048 px
JPEG / 720p H.264 copies in cache) · Home-screen widget with least-squares "full in N days" · Low-space alert ·
Monthly report + shareable card · Recent changes with Undo · Moved-to-computer archive map with thumbnails ·
Safety vault (SAF copies) · Motion-clip export and strip · App lock (BiometricPrompt) · Privacy page · Hindi strings.

## 6. Data model and storage

| Store | Contents | Notes |
|---|---|---|
| `galleryslim.db` (Room v3) | `shrink_records` (+ identity fingerprint columns), `vault_entries`, `album_policies`, `shrink_decisions`, `operations`, `operation_items` | MIGRATION_1_2, MIGRATION_2_3 (additive); partial unique index "one queued record per original"; schemas exported |
| `cleanup_cache.db` (Room v1) | `doc_kinds`, `digests` | rebuildable cache, pruned after each scan |
| `history.db` (Room v2) | `history` (TRASHED, MOVED_TO_COMPUTER, CONTACT_SHEET, VERIFIED; sha256) | MIGRATION_1_2 adds sha256 |
| `search.db` (Room v1) | `photo_index` (labels, text) | rebuildable cache, destructive migration allowed |
| `files/scan_cache.txt` | probe results per id (size + modified) | versioned, atomic rename |
| `files/archive_thumbs/` | 256 px thumbnails of moved files | |
| `files/transcode/` | video work files | space-budgeted |
| `cache/share/` | Share small copies, report cards | FileProvider `cache-path share/` |
| SharedPreferences | prefs, expiry tags (pruned), storage samples, cleanup tally, rules, room/trip settings, last MediaStore version | small key-value state |

Backups: `allowBackup=false` and data-extraction rules exclude all app data from cloud backup and device transfer
(old records must never land on another phone where ids mean different photos).

## 7. Safety invariants (normative)

- **S1** Write → record → clear IS_PENDING; a shrunk copy is never visible without its record.
- **S2** Originals leave the gallery only through Android's trash dialog after the user confirms.
- **S3** Every copy passes the verifier before it is queued; failures leave originals untouched.
- **S4** Motion photos and Ultra HDR are never silently degraded (skip, lossless, or explicit strip consent).
- **S5** Favourites and recent photos (protect window) are unselected by default; ID documents and unreadable
  photos never appear in deletion lanes.
- **S6** Nothing deletes without a system dialog. Every trash/untrash/delete goes through `safety/SafeActions`:
  gate (identity fingerprint re-checked with one batched probe) → write-ahead operation → system dialog → complete/
  cancel by operation id; `Operations.reconcile` finishes leftovers on start/resume. A fingerprint mismatch marks the
  item STALE (never cleared; only the app's own copy may be removed). **Permanent delete paths (exhaustive)**, both via
  `AndroidMediaStoreGateway.createDeleteRequest`:
  - **D1** "Free this space now": CONFIRMED originals verifiably in trash with a verifiably published copy.
  - **D2** "Send the trash to computer": items GallerySlim trashed, still trashed, verified by the Receiver in this run.
  - (Keep original deletes only GallerySlim's own shrunk copy, after checking the original is still present.)
- **S7** Rules, overnight and trips never delete; they only queue or pre-select.
- **S8** Undo exists for 30 days for everything except D1/D2.

## 8. Security model

- **Permissions:** media read, camera (QR only), biometrics, notifications, Wi-Fi multicast, internet (local sharing
  only), wake lock (during transfers), foreground service (dataSync).
- **Share server:** Wi-Fi interface only, same-subnet peers, `Host` must be its own IP or `galleryslim.local`
  (DNS-rebinding guard), ≤8 connections (≤5 per address), header and body caps, 10 s request deadline, 30 s stall
  watchdog, auto-stop 30 min idle, stops on Wi-Fi loss. Phones without a screen lock confirm sends in-app.
- **gs1 (browser):** session secret in QR + page (visible on the LAN), peer-pinned, biometric approval; no encryption.
- **gs2 (Receiver):** K only via camera; HKDF session keys; signed requests with replay window; signed answers;
  AES-GCM frames; ≤2 waiting sessions per address; verified = on-disk re-hash equals phone's whole-file hash.
- **Receiver page:** 127.0.0.1, random port + 32-hex token, Host/Origin/Sec-Fetch checks, hash-pinned CSP, no CORS.
- **Data at rest:** app-private storage; no secrets persisted (K zeroed after approval, never logged).

## 9. Concurrency and lifecycle

- ViewModels run work on `Dispatchers.IO`; Media3 Transformer must start on the main looper (wrapped in
  `suspendCancellableCoroutine`).
- `BatchKeepAliveService` keeps a manual batch in the foreground; `ShareService` hosts the server; both implement
  `onTimeout` (Android 15 dataSync limit).
- WorkManager: overnight (charging+idle, battery not low), trip (two stages: an evening kick-off with battery-not-low,
  then an expedited run), storage samples (12 h), monthly report (daily check), weekly tidy (7 d), trash watch (also
  reconciles operations).
- `BatchLock` (process-wide) admits one batch at a time: manual, overnight or trip.
- Transcoding foreground type: `mediaProcessing` on Android 15+, `dataSync` below.
- Share server: cached thread pool, semaphore slots, per-address counters, watchdog executor; gs2 sessions
  synchronized; wake/Wi-Fi locks guarded by one lock object with a destroyed flag.

## 10. Performance

Incremental scan cache; decision memory (skip known-hopeless photos, seed known quality); quality-search early exits;
progressive OCR by priority; two-stage duplicate hashing with cached digests; frame size 1 MiB; ≤3 parallel streams.
APK: debug/phone builds arm64-only; `phone` build type is R8-shrunk (~51 MB) for installs on nearly full phones.

## 11. Errors and observability

`GsLog` (rotating 1 MiB file + logcat, shareable via FileProvider); friendly failure mapping
(`friendlyFailure`, `reasonText`); throttled logging for network floods; Receiver `applog` without secrets.

## 12. Build and distribution

Phone: Gradle 8.11 / AGP 8.7.3 / Kotlin 2.0.21, compile/target SDK 36, minSdk 31; build types debug, phone, release.
Receiver: `receiver/build.sh` → Windows (amd64/arm64 .exe), macOS (.app zips, macOS 13+), Linux (tar.gz),
reproducible, SHA256SUMS. Signing/notarisation and the download location are pending RxLogix IT/legal.

## 13. Testing

~501 phone unit tests (JVM; Android stubs return defaults) incl. real-server gs2 tests and a cross-implementation
test that runs the Go receiver against the Kotlin server; ~120 receiver tests (vectors shared with Kotlin,
fault-injecting fake phone, UI security tests). Device testing on a Galaxy S24 (Android 16) for shrink, scan,
sharing (gs1). Not yet tested: gs2 with the real phone, Windows/macOS receiver runs.

## 14. Known limitations and open risks

- HEIC outputs lose GPS EXIF (ExifInterface limitation).
- gs1 is unencrypted on the LAN; delete-for-good requires gs2.
- Expiry/greeting detection reads Latin script only.
- Single-process app; long batches rely on a foreground service and may be stopped by aggressive OEM policies.
- Receiver binaries unsigned; Windows Smart App Control / macOS Gatekeeper may block them.
- Several crypto and access-control parts need human security review (company policy).

## 15. Decision log (short)

| Decision | Why |
|---|---|
| Trash, not delete, by default | 30-day undo; matches user trust |
| HEIC (not JXL) for photos | native gallery support on Android; JXL deferred |
| Verify every copy (min of 3 metrics) | one metric misses screen content or noise |
| Pull model for Receiver | no open port / firewall prompt on laptop |
| K via camera only | the LAN never carries the pairing secret |
| Go stdlib for Receiver | single binary, no runtime, no third-party code |
| Verified = on-disk re-hash | "sent" ≠ "safe"; stale manifests and truncation can't fake it |
