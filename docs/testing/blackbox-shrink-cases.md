# GallerySlim black-box test cases: scanning, shrinking, checking and clearing, keeping it slim

Source of truth: `docs/features/GallerySlim-Features.pdf` (Feature Reference, App 1.0.0, edition 2026-10-24; updated 92-page version — cases marked "(v2)" follow the updated text). Every expected
result below is quoted or paraphrased from that PDF; page numbers are the PDF's own "Page N of 93". The code was read only to
find out how to call things.

Scope: §1 guarantees (where they touch shrinking), §2.2 units, §3.1 first launch, §3.2 scanning, §4.1–4.13 shrinking,
§5.1–5.9 checking and clearing, §7.1–7.6 keeping it slim, plus the settings defaults from §9.6 that these features use.
Clean up, automation (standing rules, alerts, widget, Your month) and Send to computer / Receiver belong to other testers.

**Type:** `unit` = pure JVM logic; `integration` = several real classes together with in-memory fakes (ShrinkController with fake
pipelines and DAO, real settings classes over in-memory SharedPreferences, real JPEG fixtures); `device-only` = needs a real
phone, MediaStore, a codec or the UI. **Priority:** P1 = safety guarantee or data loss; P2 = core rule, threshold or limit;
P3 = wording, layout or cosmetic.

Automated tests live in `app/src/test/java/com/galleryslim/blackbox/shrink/`; the test method name starts with the case ID.

## Test files

| File | Areas |
|---|---|
| `ScanAndEstimateTest.kt` | SC (scan, estimates, special formats, re-scan cache), GN (units), CT07 |
| `ShrinkRoutingTest.kt` | RT (routing, vault, queueing, ordering, one batch, cancel), SK (skip/failure texts), MP02–MP09, TM10–TM12 |
| `PresetsProtectionSettingsTest.kt` | PR, PT, SET01, SF02, CL01, TP12 |
| `PhotoQualityTest.kt` | PQ (start quality, quality search, checks, lossless path, output folder) |
| `VideoRulesTest.kt` | VD (bitrate plan, screen recordings, free space, spot check, banding, folder) |
| `MotionPhotoTest.kt` | MP05–MP08 (clip boundary, still prefix, motion flag) |
| `BigVideosTrimTest.kt` | BV (light copy), TM01–TM09 (trim range) |
| `CompareTest.kt` | CM (unavailable states, verdict, zoom, layout, video comparer), GN03 |
| `ClearingAndUndoTest.kt` | RC, KO, TL, UR, D1 |
| `RecentChangesTest.kt` | RH (rows built the way the Recent changes screen builds them: "still in trash" from the ledger) |
| `AlbumRulesCoolingTest.kt` | AR, CL |
| `MakeRoomTripOvernightTest.kt` | MR, SF, TP, ON |
| `Fixtures.kt` | builders and fakes (no tests) |

## 1. Units (§2.2, p7)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| GN01 | §2.2 p7 | – | App size text for 4.2 GiB, 1 GiB, 850 MiB, 1 MiB, 12 KiB, 1 KiB, 1023 B | Binary units; ≥1 GB one decimal ("4.2 GB"), ≥1 MB whole MB ("850 MB"), ≥1 KB whole KB, else "n B" | unit | P3 |
| GN02 | §2.2 p7 | – | Goal gap for 5 GB with 0 free | 5 GB = 5 × 1,073,741,824 bytes | unit | P2 |
| GN03 | §5.2 p31–32 (v2) | – | Compare info card size line for 4.2 MiB → 1.1 MiB; 245 MiB; 1.3 GiB; 12 KiB | One decimal while below 100: "4.2 MB → 1.1 MB · 74% smaller", "245 MB", "1.3 GB", "12 KB" | unit | P3 |
| GN04 | §2.2 p7, §7.4 p52 (v2) | – | Make room / goal size text for 2.5 GiB, 850 MiB, 500 KiB | "As app screens": "2.5 GB", "850 MB", "500 KB" | unit | P3 |

## 2. First launch and scanning (§3.1 p8–9, §3.2 p9–10)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SC01 | §3.1 p8 | No media access | Open the app | Onboarding (no app bar), title "Your photos, just lighter", body, three promises, button "Allow access to photos", caption; calm colours | device-only | P3 |
| SC02 | §3.1 p8 | Android 13+ / Android 12 | Tap "Allow access to photos" | 13+: asks "Photos and videos" (READ_MEDIA_IMAGES + READ_MEDIA_VIDEO); 12: READ_EXTERNAL_STORAGE | device-only | P2 |
| SC03 | §3.1 p8 | Android 14 | Choose "Select photos" | Treated as granted; scan covers selection only; All tools shows "Showing selected items only — allow all for a full estimate" (opens app settings); not on Home | device-only | P2 |
| SC04 | §3.1 p8 | – | Deny; then deny permanently | "GallerySlim can't see your gallery yet." + "Continue" (asks again); permanently: "Open settings" opens app page | device-only | P2 |
| SC05 | §3.1 p8 | Android 13+ | First launch; then start shrink, light copy, trim, strip, Make room, trip, Send, overnight-on | Notification permission not asked at first launch; asked before each listed action; denying never blocks the work | device-only | P2 |
| SC06 | §3.1 p9, App. B #4 | Denied permanently | Grant via "Open settings", return | Onboarding probably stays until the app is reopened (known issue) | device-only | P3 |
| SC07 | §3.1 p8 | – | Start the app | Schedules trash-expiry check (daily), free-space sampling (12 h), monthly check (daily), weekly nudge (7 d, first after 3 d); records one free-space sample | device-only | P3 |
| SC08 | §3.1 p9 | – | Onboarding | Camera permission not requested here | device-only | P3 |
| SC10 | §3.2 p9 | – | JPEG (not motion, not Ultra HDR) of 10,000,000 B | Category "Camera photos", estimate 35 % = 3,500,000 B | unit | P2 |
| SC11 | §3.2 p10 | – | PNG of 10,000,000 B | "Screenshots & images", 60 % = 6,000,000 B | unit | P2 |
| SC12 | §3.2 p10 | – | H.264 video that could not be measured, 100,000,000 B | "Videos", flat 45 % = 45,000,000 B | unit | P2 |
| SC13 | §3.2 p10 | – | H.264 1080p, 20 Mbps, 60 s, 150 MiB | "Videos", estimate from the bitrate plan: a real saving below the file size | unit | P2 |
| SC14 | §3.2 p10 | – | H.264 1080p at 5.5 Mbps (already lean) | Counts as 0; already efficient | unit | P2 |
| SC15 | §3.2 p10 | – | Motion photo declaring a 1.2 MB clip | "Hidden motion-photo video", estimate = declared clip size | unit | P2 |
| SC15b | §3.2 p10 | – | Motion photo that is also Ultra HDR | Motion photo is checked first | unit | P2 |
| SC16 | §3.2 p10 | – | Motion photo with no declared clip, 5,000,000 B | 50 % of the file = 2,500,000 B | unit | P2 |
| SC17 | §3.2 p10 | – | Ultra HDR JPEG of 10,000,000 B | "HDR photos", 3 % (lossless only) = 300,000 B | unit | P2 |
| SC18 | §3.2 p10 | – | Ultra HDR HEIC | 0, "Already efficient or protected" | unit | P2 |
| SC19 | §3.2 p10 | – | HEIC, WebP, GIF, other image; HEVC, AV1, other, unknown video | 0, "Already efficient or protected" | unit | P2 |
| SC20 | §3.2 p10 | – | JPEG the scanner could not read (classed other format) | 0 and never shrunk (fails closed) | unit | P1 |
| SC21 | §3.2 p9 | – | Mixed list | Photo/video counts, total size, total = sum of category estimates | unit | P3 |
| SC22 | §3.2 p10 | – | XMP `GCamera:MotionPhoto="1"`, `MicroVideo="1"`, `MotionPhoto="0"`, none | First two are motion photos, last two are not | unit | P2 |
| SC23 | §3.2 p10 | – | Samsung trailer with / without a `MotionPhoto_Data` entry | Motion photo only with the entry | unit | P2 |
| SC24 | §3.2 p10 | – | Header with `hdrgm:Version`, with ISO 21496 urn, with neither | Ultra HDR, Ultra HDR, not | unit | P2 |
| SC26 | §3.2 p9 | – | Samsung trailer directory of 64 KiB and of 64 KiB + 1 | Up to 64 KiB is read; larger is not | unit | P3 |
| SC27 | §3.2 p10 | Cached entry | Same size+mtime; size changed; mtime changed; mtime unknown | Reused only when size and mtime both unchanged and mtime known | unit | P2 |
| SC28 | §3.2 p10 | – | Save the cache, load it | Entries come back unchanged | integration | P3 |
| SC29 | §3.2 p10 | Cache of another version | Load | Nothing reused: everything is read again | integration | P2 |
| SC30 | §3.2 p9 | – | Cold start; after access granted; Rescan gallery; after Undo of a clean-up/move row; resume; rotate; second start during scan | Scans on the first four; not on resume/rotation; second start ignored | device-only | P2 |
| SC31 | §3.2 p9 | Items shrunk / trashed via the app | Look at totals | Items with a waiting or confirmed copy left out; app-trashed items disappear at once without re-scan | device-only | P2 |
| SC32 | §3.2 p10 | – | First scan and later scans | First scan screen ≥1.5 s, later not held; ≤ every 100 ms; 160 dp ring; three statuses; "Found X to free so far"; no cancel; screen on | device-only | P3 |
| SC33 | §3.2 p10 | – | Scan a library; compare files before/after | Read-only: nothing changed | device-only | P1 |
| SC34 | §3.2 p9 | – | JPEG whose HDR marker is beyond the first 128 KiB | Not detected (only the first 128 KiB is read) | device-only | P3 |
| SC35 | §3.2 p10 | Cache present | Add, edit, delete files; re-scan | New/changed read again, deleted drop out | device-only | P2 |
| SC36 | §3.2 p10 | – | Corrupt JPEG in the gallery | Classed "other format", shown under "Other formats — left alone" | device-only | P2 |

## 3. Shrinking at a glance, routing, vault, batches (§4.1 p16–17, §1 p4–5)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| RT01 | §4.1 p16 | – | JPEG and PNG, preset Balanced | Photo shrink with that preset | integration | P2 |
| RT02 | §4.1 p16 | – | Ultra HDR JPEG | Goes to the photo path (lossless re-optimisation only) | integration | P1 |
| RT03 | §4.1 p16, §4.8 p23 | – | Ultra HDR HEIC | Skipped: "Ultra HDR photo — protected" | integration | P2 |
| RT04 | §4.1 p16, §1 p4 | – | Motion photo in a normal shrink | Skipped: "Motion photo — use the motion-photo flow"; nothing written | integration | P1 |
| RT05 | §4.1 p16 | – | HEIC, WebP | Skipped: "Already in an efficient format" | integration | P2 |
| RT06 | §4.1 p16 | – | GIF; unreadable JPEG (other) | Skipped: "Format not supported" | integration | P2 |
| RT07 | §4.1 p16 | – | H.264 video | Video shrink to HEVC | integration | P2 |
| RT08 | §4.1 p16 | – | HEVC video, Gentle/Balanced/Aggressive | Skipped as efficient | integration | P2 |
| RT09 | §4.1 p16 | – | HEVC 1080p, Light copy | Accepted (short edge above 720 px) | integration | P2 |
| RT10 | §4.1 p16 | – | AV1, every preset | "Already in an efficient format" | integration | P2 |
| RT11 | §4.1 p16 | – | Other / unknown codec, Gentle and Light copy | "Format not supported" | integration | P2 |
| RT12 | §4.1 p16 | – | Two photos, one with an album/cooling Light copy preset | Each item gets its own preset | integration | P2 |
| RT13 | §4.1 p16, §9.3 p68 | Vault on | Vault copy fails | Item not shrunk; "Couldn't protect it in the vault — left untouched"; nothing queued | integration | P1 |
| RT14 | §4.1 p16 | Vault on | Shrink one photo | Original copied to the vault first, then shrunk | integration | P1 |
| RT15 | §9.3 p68 | Vault on | Pipeline then skips the item | Vault copy was still made | integration | P3 |
| RT16 | §9.3 p68 | Vault off | Shrink a photo and a video | No vault copies | integration | P3 |
| RT17 | §4.1 p16–17, §1 p4 | – | Successful shrink | Original queued in Ready to clear with sizes and both uris; no record status changed (nothing trashed/deleted) | integration | P1 |
| RT18 | §4.1 p17 | – | Skipped and failed items | Nothing queued, nothing changed | integration | P1 |
| RT19 | §4.1 p16 | – | Usable 499 MB; 599 MB with a 300 MB item; exactly 2× largest; plenty; exactly 500 MB | Smallest-first only when below 500 MB or below 2× the largest; otherwise user order | unit | P2 |
| RT19b | §4.1 p16 | 100 MB usable | Batch of 30/3/10 MB photos | Processed smallest-first | integration | P2 |
| RT20 | §4.1 p16 | A batch running | Start another (overnight/trip/manual) | Second start silently ignored; first completes; a later batch can run | integration | P1 |
| RT21 | §4.1 p17 | Earlier result remembered | Retry after "Wouldn't get meaningfully smaller" / "Couldn't match the original's quality" | Skipped instantly with the same reason; an accepted earlier result is not a skip | unit | P2 |
| RT23 | §4.7 p22 | Batch of 3 | Cancel during the 2nd | Cancelled; the finished copy stays queued; nothing deleted | integration | P1 |
| RT24 | §4.7 p22 | – | 2 shrunk, 2 skipped, 1 failed | "done of total" 5/5; counts 2/2/1; "About X to free" = sum of (original − copy) | integration | P2 |
| RT29 | §1 p4 | – | Copy made but its record cannot be written | The new copy is removed; item reported failed "… left untouched"; not counted as shrunk | integration | P1 |
| RT30 | §4.1 p16 | Item waiting in Ready to clear | Shrink it again (any path) | Silently dropped; never shrunk twice | device-only | P1 |
| RT31 | §4.1 p17, §7.1 p49 | Album set to Never touch | Shrink items from it | Dropped with "Left N untouched — their album is set to Never touch" | device-only | P1 |
| RT32 | §4.1 p17 | Vault on, no folder | Start a batch | "Pick a vault folder first"; refused; progress shows "Nothing is running." or the previous result | device-only | P1 |
| RT33 | §4.1 p17 | – | Batch crashes | "Something went wrong — see the diagnostic log" | device-only | P3 |
| RT34 | §4.1 p17 | Photo skipped once | Retry same size+preset; then other preset | Instant same-reason skip; other preset searches again | device-only | P2 |
| RT36 | §4.1 p16, §1 p4 | Ultra HDR JPEG on the phone | Shrink it | Only a lossless re-optimisation keeping pixels and gain map; skipped "Ultra HDR photo — protected" if it saves < 1.5 % | device-only | P1 |
| RT38 | §4.1 p16–17 (v2) | Overnight or trip job holds the batch | Start a manual shrink | "Another shrink is already running (the overnight shrink). Try again when it finishes." (or "the trip shrink"/"another shrink"); overnight skips a busy night; trip retries after 15 min | device-only | P2 |
| RT37 | §1 p4, §4.9 p24 | – | Kill the app mid-shrink; force an encode failure | Nothing half-made visible; hidden copy removed; original unchanged | device-only | P1 |
| SG01 | §1 p4 | – | Run manual shrink, overnight, trip, Make room | No item ever trashed or deleted without the user's confirmation in Android's dialog | device-only | P1 |

## 4. Presets, protection, settings defaults (§4.2 p17–18, §4.4 p19–20, §9.6 p71)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| PR01 | §4.2 p17–18 | – | Read each preset | SSIM / SSIMULACRA2 / start: Gentle 0.985/80/87, Balanced 0.97/72/80, Aggressive 0.93/60/65, Light copy 0.90/55/75 | unit | P2 |
| PR01b | §4.2 p18 | – | Long-edge limit | Only Light copy reduces photos to 2048 px | unit | P2 |
| PR02 | §4.2 p17–18 | – | Video target per preset | 0.75 / 0.65 / 0.5 / 0.5 | unit | P2 |
| PR03 | §4.2 p18 | Fresh install | Read global preset | Gentle | integration | P2 |
| PR03b | §4.2 p17 | – | Choose Aggressive, read back | Remembered | integration | P3 |
| PR04 | §4.2 p17–18 | – | Settings › Quality | Radio list, captions, GPS caption, Gentle selected, "(recommended)" on Balanced | device-only | P3 |
| PR06 | §4.2 p17 | – | Preset names | "Gentle", "Balanced", "Aggressive", "Light copy" | unit | P3 |
| PT01 | §4.4 p20 | – | Old favourite | Protected; chip "Protected · favorite" | unit | P1 |
| PT02 | §4.4 p20 | – | Photo taken 3 days ago | Protected; chip "Protected · taken in the last N days" | unit | P1 |
| PT03 | §4.4 p20 | Window 30 | Age exactly 30 days | Not protected ("below the window") | unit | P2 |
| PT04 | §4.4 p20 | Window 30 | Age 30 days − 1 s | Protected | unit | P2 |
| PT05 | §4.4 p20 | – | No date taken, no date added | Not protected | unit | P2 |
| PT06 | §2.1 p7, §4.4 p20 | – | No date taken; date added 5 / 500 days ago | Age from date added: protected / not | unit | P2 |
| PT07 | §2.1 p7 | – | Taken 800 days ago, added today | Age from date taken: not protected | unit | P2 |
| PT08 | §4.4 p20 | Fresh install | Read choices and default | 7, 30, 90; default 30 | integration | P2 |
| PT09 | §4.4 p20 | – | Windows 7 and 90 around their edges | 6 d protected / 8 d not (7); 89 d protected / 91 d not (90) | unit | P2 |
| PT10 | §4.4 p20 | – | Make room plan with a favourite and a recent photo | Protected items excluded entirely | unit | P1 |
| PT11 | §4.4 p20 | – | Category screen with protected items | Chips and header note; "favorite" vs "favourite" spelling | device-only | P3 |
| PT12 | §4.4 p20 | – | Get space back with protected items | Excluded entirely from "Shrink N items" | device-only | P1 |
| SET01 | §9.6 p71 | Fresh install | Read settings | Vault off, overnight off, app lock off, Google Photos warning not accepted, protect 30 days | integration | P2 |

## 5. Category screens, Try one, Google Photos warning, progress (§4.3 p18–19, §4.5 p20–21, §4.6 p21, §4.7 p21–22)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| CT01 | §4.3 p19 | – | All tools › Shrink | Rows only with items and saving > 0; labels/subtitles; "− size" | device-only | P3 |
| CT02 | §4.3 p19 | – | Category grid | 3 columns; badges; video duration m:ss / h:mm:ss | device-only | P3 |
| CT03 | §4.3 p19, §4.4 p20 | Protected items present | Open category | Non-protected start selected; protected unticked; Select all / Clear all never include protected; can tick by hand | device-only | P1 |
| CT04 | §4.3 p19 | – | Select / deselect | "Save about X" hidden when none; "Shrink N items" (Motion: "Remove hidden video from N photos"), disabled when none | device-only | P3 |
| CT05 | §4.3 p19 | Selection | Tap "Send N to computer" | Queues selection, opens Send to computer | device-only | P3 |
| CT06 | §4.3 p19 | Never-touch album | Open category | Items look selectable; dropped at batch start | device-only | P2 |
| CT07 | §4.3 p19 | – | Screen titles | "Camera photos", "Screenshots & images", "Videos", "Motion photos", "HDR photos" | unit | P3 |
| TR01 | §4.5 p20 | – | Category screens | "Try on one photo" only on Camera photos, Screenshots & images, HDR photos | device-only | P3 |
| TR02 | §4.5 p20 | – | Try on one photo | Selected item with the largest saving; none selected → largest non-protected; hidden when nothing selected and all protected | device-only | P2 |
| TR03 | §4.5 p20, App. B #13 | – | Get space back "Try one first" | Shrinkable non-protected item with the largest saving (may be a video); no Google Photos warning | device-only | P2 |
| TR04 | §4.5 p21 | – | After a trial | Progress: "See the difference" + caption | device-only | P3 |
| GP01 | §4.6 p21 | Google Photos installed | Shrink from a category | Warning until accepted once; Cancel/dismiss → again next time; Cancel aborts | device-only | P2 |
| GP02 | §4.6 p21 | – | Category vs Get space back | Two different body texts | device-only | P3 |
| GP03 | §4.6 p21 | – | Try one first, Big videos, Trim, Make room, overnight, trips | No warning | device-only | P3 |
| PG01 | §4.7 p22 | Running batch | Watch progress | Texts, ring, file name, "About X to free…", red Cancel with note; no pause | device-only | P3 |
| PG02 | §4.7 p22 | – | Finish / cancel | "Done. N shrunk · N skipped · N failed" / "Cancelled. Files already shrunk are waiting in Ready to clear." | device-only | P2 |
| PG03 | §4.7 p22 | Skips/failures | Why card | ≤5 groups largest first, ≤2 example names, failures red, "Share diagnostic log" | device-only | P3 |
| PG04 | §4.7 p22 | Successes | Compare links | 1 → "See the difference"; more → "Compare name" for first 3; ask app lock | device-only | P3 |
| PG05 | §4.7 p22, App. B #1 | – | Main button | "Go to Ready to clear" (no app lock) if any success, else "Back to dashboard" | device-only | P2 |
| PG06 | §4.7 p22 | Running | Back arrow | Home; batch keeps running | device-only | P2 |
| PG07 | §4.7 p22 | Running | Notification shade | "Shrinking your gallery" / "done of total · name" ("Scanning…" first); channel; no tap action; Android 15 time limit | device-only | P3 |
| PG08 | App. B #5 | Running | Swipe app from Recents | May cancel the batch (record) | device-only | P2 |

## 6. Skip and failure reasons (§4.8 p22–23)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SK01 | §4.8 p23 | – | Each skip kind | Exact seven skip texts from the table | unit | P2 |
| SK02a | §4.8 p23 | – | Vault copy failed; space ran out | "Couldn't protect it in the vault — left untouched"; "Not enough free space" | unit | P2 |
| SK02b | §4.8 p23 | – | No progress 120 s; empty output; audio lost | "Video conversion stalled — left untouched"; "Video conversion failed — left untouched" | unit | P2 |
| SK02c | §4.8 p23 | – | Photo encode error; motion strip error | "Couldn't convert this photo — left untouched"; "Couldn't remove the hidden video — left untouched" | unit | P2 |
| SK02d | §4.8 p23 | – | Lossless-path error; anything else | "Couldn't process this file — left untouched" | unit | P2 |
| SK02e | §4.8 p23 (v2) | – | Trim errors (transcode error, empty trim, audio lost) | "Couldn't trim this video — left untouched" | unit | P3 |
| SK03 | §4.8 p23 (v2) | – | Item queued meanwhile; original changed during work | "Already waiting in Ready to clear"; "The original changed while it was being shrunk — left untouched" | unit | P1 |
| SK03b | §4.8 p23 (v2), §1 p4 | Original changes before the record is written | Shrink | New copy removed, nothing queued, "The original changed…" | integration | P1 |

Note (still true in v2): §4.8's safety note says "Every failure text ends in 'left untouched'", but its own table lists "Not enough free space"
as a failure text. This is an inconsistency inside the PDF; SK02a follows the table.

## 7. Photo quality checks and output (§4.9 p23–25)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| PQ01 | §4.9 p24 step 1 | – | q65 @ 0.79 bpp; q40 @ 0.5; q66; q65 @ 0.8; unknown q | Too compact (lossless path) only when quality ≤ 65 and below 0.8 bits per pixel | unit | P2 |
| PQ02 | §4.9 p24 step 3 | – | Preset start 87 with source q 69/70/84/85/unknown; clamps | −15 below 70, −8 below 85; kept within 40–95 | unit | P2 |
| PQ26 | §4.9 p24 | – | JPEG tables for IJG quality 50…95 | Source quality estimated (±1) | unit | P3 |
| PQ03 | §4.9 p24 step 4 | – | Searches that never succeed | At most 6 encodes | unit | P2 |
| PQ04 | §4.9 p24 | – | Fail at q87; pass-too-big at q87 | Up to 95; down to 79 (step 8) | unit | P2 |
| PQ05 | §4.9 p24 | – | Same size at two steps | Step becomes 16 | unit | P3 |
| PQ06 | §4.9 p24 | – | Encode exactly 1.5×, 1.6× (fail), 1.49× | Stops at ≥1.5×; continues at 1.49× | unit | P2 |
| PQ07 | §4.9 p24 step 6 | – | Passing encode at 90 % and at 90 % + 1 B | Accept at ≤ 90 % only | unit | P2 |
| PQ08 | §4.9 p24 step 6 | – | Two passing encodes 95 % and 88 % | Accept the smallest passing encode | unit | P2 |
| PQ09 | §4.9 p24 | – | Fail at 79 then pass at 87 with 60 % | Steps up then accepts 87 | unit | P2 |
| PQ09b | §4.9 p24 (v2) | – | Failed check at q87 with encodes at 60 %, 48 %, 49 % of the original | Stops when 1.84 × last encode would exceed 90 % (60 %, 49 %); steps up at 48 % | unit | P3 |
| PQ10 | §4.9 p24, §4.8 p23 | – | Never passes; passes but never ≤ 90 % | "Couldn't match the original's quality" / "Wouldn't get meaningfully smaller" | unit | P2 |
| PQ11 | §4.9 p24 | – | Step below 40 / above 95 | Clamped to 40–95 | unit | P2 |
| PQ12 | §4.9 p24 step 5 | – | 6 of 10 vs 5 of 10 blocks with ≤4 levels | Screen-like from 60 % of blocks | unit | P2 |
| PQ13 | §4.9 p24 step 5 | – | Identical / noisy / inverted images | SSIM and MS-SSIM 1.0 when identical, lower when noisy; SSIMULACRA2 identical ≥ Gentle floor 80, inverted below Light copy floor 55 | unit | P3 |
| PQ15 | §4.9 p24 step 7 | Baseline JPEG fixture | Lossless re-optimisation | Pixels bit-identical | integration | P1 |
| PQ16 | §4.9 p24 step 7 | JPEG + Samsung-style trailer | Lossless re-optimisation | Trailer copied byte for byte | integration | P1 |
| PQ16b | §4.9 p24 step 7 | Two-image JPEG (gain-map layout) | Lossless re-optimisation | Everything after the image copied byte for byte | integration | P1 |
| PQ17 | §4.9 p24 step 7 | Progressive JPEG fixture | Lossless re-optimisation | Not supported (no copy) | integration | P2 |
| PQ21 | §4.9 p24 | – | Originals in DCIM/Camera, Pictures/Screenshots, WhatsApp media, Download | Same folder under DCIM/Pictures; otherwise Pictures/GallerySlim/album/ | unit | P2 |
| PQ22 | §4.9 p24 | – | Lossless copy score 1.0 | "Visually identical · Match 100.00%" | unit | P3 |
| PQ30 | §4.9 p24 | – | Shrink IMG_1.jpg | HEIC "IMG_1.heic"; lossless JPEG keeps the name (de-duplicated) | device-only | P3 |
| PQ31 | §4.9 p24 | – | Inspect copies | Date taken copied; HEIC orientation baked, no EXIF/GPS; lossless keeps EXIF | device-only | P2 |
| PQ32 | §4.9 p24 step 2 | Little free space | Shrink a photo | Needs 2 × original + 300 MB free, else skipped with the space reason | device-only | P2 |
| PQ33 | §4.2 p18 | Light copy | Shrink a 4000 px photo and a 1500 px photo | Long edge 2048 only when larger | device-only | P2 |
| PQ34 | §4.9 p24 step 7 | – | Lossless saving < 1.5 % | Not kept | device-only | P2 |
| PQ36 | §4.9 p24 | – | Force a failing check | Copy discarded; original opened read-only | device-only | P1 |

## 8. Video rules (§4.10 p25–26)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| VD01 | §4.10 p25 step 1 | – | Short side 360/500/501/800/801/1200/1201/1600/1601/2400/2401/4320 | 1.2 / 2.5 / 5 / 9 / 16 / 32 Mbps by the ≤ boundaries | unit | P2 |
| VD02 | §4.10 p25 | – | 1080×1920 portrait | Uses short side: 5 Mbps | unit | P2 |
| VD03 | §4.10 p25 | – | 48, 60, 47.9 fps | ×1.5 at 48 fps or more | unit | P2 |
| VD04 | §4.10 p25 step 2 | – | Plan 1.2 Mbps vs source 1.6 / 1.599999 Mbps; 1080p @ 6 Mbps | Skip only above 75 % of the source | unit | P2 |
| VD05 | §4.10 p25 step 2 | – | 1080p @ 20 Mbps and @ 8 Mbps | Lower of plan and 55 % of source: 5 / 4.4 Mbps | unit | P2 |
| VD06 | §4.10 p25 | – | Resolution and bitrate unknown | Skip | unit | P2 |
| VD07 | §4.10 p25 step 3 | – | Detail from flat to busy | Factor 0.7–1.0, never outside, rising with detail | unit | P3 |
| VD08 | §4.10 p25 step 4 | – | Names / albums | Screen recording by name prefix or album text | unit | P3 |
| VD09 | §4.10 p25 step 4 | – | 60, 33, 32 fps; non-screen 60 | Above 32 fps → 30 fps | unit | P3 |
| VD10 | §4.10 p25 step 5 | – | 5 Mbps, 60 s | Output (5 Mbps + 256 kbps) × 60 s × 1.15; needs 2 × that + 300 MB | unit | P2 |
| VD11 | §4.10 p25 step 8 | – | 10 s video | Frames at 10/30/50/70/90 % | unit | P2 |
| VD12 | §4.10 p25 step 8 | – | Frame scores | Every frame ≥ 0.90 | unit | P1 |
| VD13 | §4.10 p25 | – | Unknown duration | Spot check fails | unit | P1 |
| VD14 | §4.10 p25 step 8 | – | (source, output) banding pairs | Fails when output ≥ 1.5 % and > 1.0 point above source | unit | P2 |
| VD15 | §4.10 p25 | – | Flat vs banded ramp | Index 0 vs ≥ 1.5 % | unit | P3 |
| VD17 | §4.10 p25 | – | Videos in DCIM/Movies/Pictures and WhatsApp media | Same folder, else Movies/GallerySlim/album/ | unit | P2 |
| VD30 | §4.10 p25 | – | Shrink an H.264 video | Hardware HEVC, audio copied unchanged, base name .mp4 | device-only | P2 |
| VD31 | §4.10 p25 | – | Stall the encoder 120 s | "Video conversion stalled — left untouched" | device-only | P2 |
| VD32 | §4.10 p25 | – | Empty output / audio lost / duration off > 1 s / not smaller | Failed or skipped; nothing published | device-only | P1 |
| VD34 | §4.10 p25 | Old work files | Start a batch | Work files older than 6 h removed | device-only | P3 |
| VD35 | §4.10 p25 | – | Compare a video | Stored score = lowest spot-check frame | device-only | P3 |
| VD37 | §4.10 p25 | – | Failed/stalled conversion | Original untouched; no copy visible | device-only | P1 |

## 9. Motion photos (§4.11 p26–27)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| MP02 | §4.11 p26 | Consent given | Strip one motion photo | New still published; original queued under "Motion photos" | integration | P2 |
| MP03 | §4.11 p26 | – | Strip on a non-motion photo | Nothing done | integration | P1 |
| MP04 | §9.3 p68 | Vault on, vault fails | Strip | Not stripped; vault failure text | integration | P1 |
| MP05 | §4.11 p26 | JPEG + MP4 with XMP length | Find boundary | Clip found; still is an exact byte prefix | unit | P1 |
| MP05b | §4.11 p26 | JPEG + MP4, no XMP | Find boundary | Clip found | unit | P2 |
| MP06 | §4.11 p26 | No clip | Find boundary | No boundary → item fails, file untouched | unit | P1 |
| MP07 | §4.11 p26 | Samsung layout | Find boundary | Still is a byte prefix ending before the clip | unit | P2 |
| MP08 | §4.11 p26 | – | Clear motion flag | MotionPhoto/MicroVideo set to 0, same length, input untouched; no longer detected as motion | unit | P2 |
| MP09 | §4.11 p26 | – | Compare a stripped photo | Score "Not measured" | integration | P3 |
| MP10 | §4.11 p26 | – | Remove hidden video | Consent dialog texts and buttons; "Choose export folder" only opens the picker | device-only | P1 |
| MP11 | §4.11 p26 | Export folder set | Export then remove; export failure | Clip "base.mp4" with a video track; on failure partial clip deleted and photo not stripped | device-only | P1 |
| MP12 | §4.11 p26 | – | Inspect the new still | Decodes fully before visible; EXIF/XMP/GPS/gain map kept; flag 0; name/folder/date rules | device-only | P1 |
| MP14 | §4.11 p26 | Never-touch album | Strip | Strip still allowed; no free-space pre-check | device-only | P3 |
| MP15 | §4.11 p26 | – | Pick an unusable export folder | "Couldn't use that folder for clip exports" | device-only | P3 |
| MP16 | §4.11 p26 | – | Motion photos screen | All non-protected selected; no "Try on one photo" | device-only | P3 |

## 10. Big videos, light copy and trim (§4.12 p27–28, §4.13 p28–29)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| BV01 | §4.12 p27 | – | 1920×1080, 1080×1920, 3840×2160 | Short edge 720, aspect kept | unit | P2 |
| BV01b | §4.12 p27 | – | Odd aspect ratios | Even dimensions, short edge 720, aspect within 2 px | unit | P2 |
| BV01c | §4.12 p27 | – | 1280×720, 640×480 | Not upscaled | unit | P2 |
| BV02 | §4.12 p27 | – | Rotation 0/90/180/270 | Rotation respected | unit | P2 |
| BV03 | §4.12 p27 | – | AV1/other/unknown; HEVC ≤720p; no plan; size or duration unknown | No saving (row not selectable) | unit | P2 |
| BV04 | §4.12 p27 | – | H.264 640×360 | Not upscaled, re-encoded to HEVC; saving > 0 | unit | P2 |
| BV05 | §4.12 p27 | – | HEVC 1080p | Has a light copy | unit | P2 |
| BV10 | §4.12 p27 | – | Open Big videos | All videos largest first; row text; "720p copy saves about X" / "Already light" | device-only | P3 |
| BV11 | §4.12 p27 | – | Selection | Nothing pre-selected; only saving rows selectable; Select all only when any selectable | device-only | P2 |
| BV12 | §4.12 p27 | – | Bottom bar | "N videos · X in total" / "N selected"; "Make 720p light copies (save about X)"; disabled when none | device-only | P3 |
| BV13 | §4.12 p27 | Global preset Gentle | Make 720p light copies | Light copy preset; no warning, no confirmation; progress opens | device-only | P2 |
| BV14 | §4.12 p27 | Never-touch album; album LC rule; cooling | Make light copies | Never touch honoured with message; LC rules/cooling not applied | device-only | P1 |
| BV15 | §4.12 p27 | – | Rows | "Trim" only for H.264/HEVC longer than 2 s | device-only | P3 |
| BV16 | §4.12 p27 | No videos | Open | "No videos found" | device-only | P3 |
| TM01 | §4.13 p28 | – | Duration unknown | Cannot be trimmed | unit | P2 |
| TM02 | §4.13 p28 | – | Start −1 ms | "Start is before the beginning" | unit | P2 |
| TM03 | §4.13 p28 | 10 s video | End 10.501 s; 10.500 s | "End is after the video ends"; within 500 ms snaps to the end | unit | P2 |
| TM04 | §4.13 p28 | – | Start = end; start > end | "Start must be before end" | unit | P2 |
| TM05 | §4.13 p28 | – | Keep 999 ms; 1000 ms | "Keep at least 1 second"; OK | unit | P2 |
| TM06 | §4.13 p28 | 10 s video | Full range; cut 999 ms; cut 1000 ms; 500 + 500 ms | "Move a handle to cut something" below 1 s cut in total | unit | P2 |
| TM07 | §4.13 p28 | – | H.264 / HEVC source | Planner bitrate for H.264; source bitrate otherwise | unit | P2 |
| TM08 | §4.13 p28 | – | Range shares | "Save about X" = cut share; 0 for full range | unit | P3 |
| TM09 | §4.13 p28 | – | Times | m:ss / h:mm:ss labels | unit | P3 |
| TM10 | §4.13 p28 | – | Trim through the batch | Original under "Trimmed videos"; Recent changes "Trimmed" | integration | P2 |
| TM11 | §4.13 p28 | – | Trim a photo | Refused | integration | P2 |
| TM12 | §9.3 p68 | Vault on, vault fails | Trim | Not trimmed | integration | P1 |
| TM20 | §4.13 p28 | – | Trim screen | Still frame, 100 ms snap, labels, body text | device-only | P3 |
| TM21 | §4.13 p28 | – | Invalid ranges on screen | "Save about" only valid; "Make trimmed copy" enabled only valid; unknown length text | device-only | P2 |
| TM22 | §4.13 p28 | – | Never touch; already waiting; vault without folder; batch running | Refusal texts; ignored while a batch runs | device-only | P1 |
| TM23 | §4.13 p28 | – | Make trimmed copy | base_trim.mp4; integrity checks; no quality check | device-only | P2 |
| TM24 | §4.13 p29 | – | Compare on a trimmed row | "Can't compare this item" | device-only | P3 |

## 11. Compare (§5.1 p30, §5.2 p31–32, §5.3 p32–33)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| CM01 | §5.1 p30 | – | Record not found | "Item not found" / "This item is no longer in the queue. Nothing was changed." | unit | P2 |
| CM02 | §5.1 p30 | Confirmed or undone record | Open | "Already handled" + message | unit | P2 |
| CM03 | §5.1 p30 | Trim record | Open | "Can't compare this item" + message | unit | P3 |
| CM04 | §5.1 p30 | – | Both / original / copy missing | Three titles and messages | unit | P2 |
| CM05 | §5.1 p30 | – | Photo, stripped motion photo, video | Photo comparer / photo comparer / video comparer | unit | P2 |
| CM06 | §5.1 p30 | – | Font scale 1.29, 1.3, 2.0 | Second line from 1.3 | unit | P3 |
| CM07 | §5.2 p31 | – | Scores 1.0, 0.985, 0.9849, 0.97, 0.9699, none | Visually identical / Nearly identical / Some loss / Not measured | unit | P2 |
| CM08 | §5.2 p31 | – | 0.98999, 0.971299 | "Match 98.99%", "Match 97.12%" (truncated) | unit | P3 |
| CM09 | §5.2 p31 | – | Size pairs | "N% smaller" (≥ 1 %, never 100 %), "same size", "N% larger" | unit | P3 |
| CM10 | §5.2 p31 | – | Wipe | Starts at 50 %; stays 26 dp from edges; original on start side (right in RTL) | unit | P3 |
| CM11 | §5.2 p31 | – | Large, medium, tiny photo | Zoom from fit (or 1:1 when smaller) to larger of 3× fit and 4× actual pixels | unit | P3 |
| CM12 | §5.2 p31 | – | Double-tap at fit and when zoomed | To actual pixels kept within 2×–4× of fit; back to centred fit | unit | P3 |
| CM13 | §5.2 p31 | – | "100%" pill twice | 1:1, then "Fit" back to fit | unit | P3 |
| CM14 | §5.2 p31 | – | Zoom 1.0, 1.005, 1.02 | Chip only when more than 1 % above fit | unit | P3 |
| CM15 | §5.2 p31 | – | Side by side | Row or column, whichever larger; row on a tie | unit | P3 |
| CM16 | §5.2 p31 | – | Window sizes | Side panel when wider than tall and ≥ 560 dp; 40 % width in 280–360; portrait panel ≤ half; canvas ≥ 240 dp | unit | P3 |
| CM17 | §5.2 p32 | – | Divider at 45 % | "Original 45%, GallerySlim 55%" | unit | P3 |
| CM18 | §5.2 p32 | – | Info columns | "12 MP", "JPEG", "W × H", unknown "—" | unit | P3 |
| CM19 | §5.3 p32 | – | Video comparer defaults | Side by side; GallerySlim copy audible; only one side audible; tap pane switches | unit | P3 |
| CM20 | §5.3 p32 | – | Transport | "Play both" / "Pause" / "Play again" | unit | P3 |
| CM21 | §5.3 p32 | – | Durations | Total = shorter of the two; unknown → "—" | unit | P3 |
| CM22 | §5.3 p33 | – | Drift 100/101 ms; re-sync 499/500 ms after last | Re-sync when drift > 100 ms, at most every 500 ms | unit | P3 |
| CM23 | §5.3 p33 | – | End and scrub timing | End within 50 ms; previews at most every 80 ms | unit | P3 |
| CM24 | §5.3 p33 | – | Frame grab | ≥ 50 ms before the end; ≤ 1/8 of memory within 4–48 MB | unit | P3 |
| CM25 | §5.3 p33 | – | Verdict notes | Four texts | unit | P3 |
| CM26 | §5.3 p33 | – | Format / bitrate | H.264 / HEVC / AV1 / Other / —; "5.0 Mbps" | unit | P3 |
| CM27 | §5.3 p33 | – | Pane errors | Four headline/detail pairs | unit | P3 |
| CM30 | §5.1 p30 | – | Entry points | Ready to clear rows; progress links ask for the app lock | device-only | P2 |
| CM31 | §5.2 p31 | – | Wipe/side-by-side gestures | Handle drag; tap does not move it; labels fade; 2 dp gap; near-black canvas | device-only | P3 |
| CM33 | §5.2 p31 | – | Touch and hold 250 ms | Original everywhere; haptic; "Original" chip; hint 3.5 s | device-only | P3 |
| CM34 | §5.2 p31 | – | Rotate | Mode, divider and zoom survive | device-only | P3 |
| CM35 | §5.1 p30 | – | "Looks good", back, "Go back" | Return to origin; nothing approved or trashed | device-only | P1 |
| CM36 | §5.5 p35 | – | Keep original in Compare during transition / double tap | Ignored unless fully on top | device-only | P2 |
| CM37 | §5.2 p32 | One file unreadable | Open | Only Keep original; "Couldn't open one of the two photos…" | device-only | P3 |
| CM38 | §5.3 p32 | – | Video modes, "Compare this frame" | One transport; flip keeps position; frame compare without Looks good/Keep original | device-only | P3 |
| CM39 | §5.3 p33 | – | Phone call during playback | Pauses; rebuilt at same position; screen stays on | device-only | P3 |

## 12. Ready to clear (§5.4 p33–34)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| RC01 | §5.4 p34 step 3 | Records of all kinds | Group | Fixed order Photos, Videos, Trimmed videos, Motion photos | unit | P3 |
| RC02 | §5.4 p34 | – | Group header | Count and saving (original − copy) | unit | P2 |
| RC03 | §5.4 p34 step 5 | – | Clear button | Count and total size of the originals | unit | P2 |
| RC04 | §5.4 p34 | Copy larger than original | Saving | Never below 0 | unit | P2 |
| RC05 | §5.4 p34 step 6 | – | Copy pending/not visible, trashed, size 0 | Dropped from the batch; original not trashed | unit | P1 |
| RC06 | §5.4 p34 | – | Copy visible, original present | Goes to the trash dialog | unit | P1 |
| RC06b | §5.4 p34 | One good, one bad | Clear | Only the failing record leaves the batch | unit | P1 |
| RC08 | §5.4 p34, §1 p5 | – | State unreadable | Original never trashed without a verified copy | unit | P1 |
| RC10 | §5.4 p34 step 7 | – | OK in Android's dialog | Records confirmed | unit | P1 |
| RC11 | §5.4 p34 step 7 | – | Cancel | Nothing changes | unit | P1 |
| RC16 | §5.7 p36 | – | Dialog result after the screen was rebuilt | Only the batch the dialog was opened for is confirmed | unit | P1 |
| RC30 | §5.4 p34 (v2) | Queued record | Original changed size / modified time / library version | Not cleared; moves to "Can't be cleared safely" | unit | P1 |
| RC30b | §5.4 p34 (v2) | – | Copy not the size written | Not cleared | unit | P1 |
| RC31 | §5.4 p34 (v2) | Stale record | Clear | Never part of the Clear button | unit | P1 |
| RC32 | §5.4 p34 (v2) | Stale record | Remove the smaller copy with original present / in trash / gone / copy changed / check failed | Deletes only while original in the gallery, not trashed, and copy is ours; otherwise kept | unit | P1 |
| RC33 | §5.4 p34 (v2) | – | Original already gone or in trash at Clear | Leaves the list, copy kept, not stale | unit | P2 |
| RC34 | §5.4 p34–35 (v2) | Stale items | Open Ready to clear; Dismiss; Remove | Section caption, rows "Smaller copy: X", Dismiss hides only; messages "N item(s) changed since…", "N original(s) were already gone…", "Couldn't check N item(s)…", "Removed/Couldn't remove/Kept the smaller copy of name…" | device-only | P2 |
| RC20 | §5.4 p33, App. B #1 | App lock on | Open from Home card, banner, Get space back; from progress / notifications | Lock asked for the first three; not for the others (known) | device-only | P2 |
| RC21 | §5.4 p34 | – | Header | Back always Home; "Send to laptop" when not empty | device-only | P3 |
| RC22 | §5.4 p34 | – | Texts | Intro; "Saves X once the trash empties…"; "Clear N originals (X) to the trash" | device-only | P3 |
| RC23 | §5.4 p34 | – | Clear → OK / Cancel | One dialog for all; OK: list empties, no snackbar, becomes last run; Cancel: nothing | device-only | P1 |
| RC24 | §5.4 p34 | A copy deleted outside the app | Clear | "Skipped N item(s) whose shrunk copy is missing — originals left untouched" | device-only | P1 |
| RC25 | §5.4 p34 | Dialog open | Tap Compare / Keep / Send | Ignored; Clear disabled | device-only | P2 |
| RC26 | §5.4 p34 | – | "Send to laptop" | Queues originals with real dates; records stay | device-only | P3 |
| RC27 | §5.4 p34 | Empty | Open | "Nothing to clear yet…" | device-only | P3 |
| RC28 | App. B #7 | Originals trashed via Send to laptop | Keep original | Records stay listed; Keep says original already in trash | device-only | P3 |

## 13. Keep original (§5.5 p35)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| KO01 | §5.5 p35 | Original present, not in trash | Keep original | Only the smaller copy is deleted; item leaves the list | unit | P1 |
| KO02 | §5.5 p35 | Original in the trash | Keep original | Copy kept; leaves the queue as confirmed | unit | P1 |
| KO03 | §5.5 p35 | Original gone | Keep original | Copy kept; confirmed | unit | P1 |
| KO04 | §5.5 p35 | Check fails | Keep original | Nothing changes | unit | P1 |
| KO05 | §5.5 p35 | Any non-present original | Keep original | Never deletes the only remaining copy | unit | P1 |
| KO08 | §5.5 p35 | Record no longer waiting | Keep original | Nothing happens | unit | P2 |
| KO09 | §5.5 p35 (v2) | Original or copy changed | Keep original | Nothing deleted; moves to "Can't be cleared safely" | unit | P1 |
| KO14 | §5.5 p35 (v2) | Original changed | Keep original | "name changed since it was shrunk — nothing was deleted" | device-only | P3 |
| KO10 | §5.5 p35 | Copy cannot be deleted | Keep original | Nothing changes; "Couldn't remove the shrunk copy of name" | device-only | P1 |
| KO11 | §5.5 p35 | Original in trash | Keep original | "name: the original is already in the trash — kept the shrunk copy" | device-only | P2 |
| KO12 | §5.5 p35 | Media library unreadable | Keep original | "Couldn't check the original of name — try again" | device-only | P2 |
| KO13 | §5.5 p35 | – | Keep original | No confirmation dialog; row disappears with no message | device-only | P3 |

## 14. Trash ledger (§5.6 p35–36)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| TL01 | §5.6 p36 | Two originals in trash | Ledger | In trash: full original sizes, count 2, earliest expiry; nothing freed | unit | P1 |
| TL02 | §5.6 p36 | Original no longer exists | Ledger | Freed = original − copy | unit | P2 |
| TL03 | §5.6 p36 | Restored by the user | Ledger | Counts nothing | unit | P2 |
| TL04 | §5.6 p36 | – | In trash / gone / present / lookup error | In trash / freed / nothing / in trash (never claims freed) | unit | P1 |
| TL05 | §5.6 p35 | – | Expiry | Date comes from the trashed original | unit | P3 |
| TL07 | §5.6 p36, §5.8 | Deleted for good | Ledger | Freed | unit | P2 |
| TL10 | §5.6 p35, §3.5 p14 | – | All tools | "X is waiting in the trash" + "and comes back from d MMM"; "Freed so far" / "You've freed X with GallerySlim"; "Still in the trash: N originals, X · frees automatically from d MMM" | device-only | P2 |
| TL11 | §5.6 p36 | Clean-up trash items | Ledger | Not counted | device-only | P2 |
| TL13 | §3.3 p11, §5.6 p35 | – | Home | "can get back" includes originals still in the trash | device-only | P2 |
| TL14 | §5.6 p36 | Restored original | "You've freed" | Still includes it | device-only | P3 |

## 15. Undo this run (§5.7 p36–37)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| UR01 | §5.7 p36 | Restore OK, original back | Cleanup | Copy deleted, item undone | unit | P1 |
| UR02 | §5.7 p36 | Original still in trash | Cleanup | Copy kept | unit | P1 |
| UR03 | §5.7 p36 | Original gone | Cleanup | Copy kept | unit | P1 |
| UR04 | §5.7 p36 | Lookup failed | Cleanup | Nothing deleted | unit | P1 |
| UR05 | §5.7 p36 | – | Status change | Undone only from confirmed | unit | P2 |
| UR08 | §5.7 p36 | – | Cancel restore dialog | Nothing changes | unit | P1 |
| UR09 | §5.7 p36 | Run originals in the trash | Undo | Those originals go to the restore dialog | unit | P2 |
| UR14 | §5.7 p37 (v2) | – | Undo with original changed in trash / restored / gone | Not offered to the restore dialog | unit | P1 |
| UR15 | §5.9 p39 (v2) | Original back but changed | After restore | Copy kept | unit | P1 |
| UR16 | §5.7 p37 (v2) | – | Undo when none restorable / check fails | "Nothing to restore — those originals have left the trash" / "Couldn't check some originals — try again"; partial "Restored N; M keep their smaller copy…" | device-only | P2 |
| UR10 | §5.7 p36 | Batch cleared | All tools / Home | Card on All tools only: "Cleared N originals (X) to the trash", Undo, X | device-only | P2 |
| UR11 | §5.7 p36 | – | Undo → Cancel | Nothing; card stays | device-only | P1 |
| UR12 | §5.7 p36 | – | Undo → OK (all / some back) | "Restored N originals" / "Restored X of N; the rest keep their shrunk copy because the original isn't back in your gallery" | device-only | P2 |
| UR13 | §5.7 p36 | – | Kill app; clear another batch; X; Undo; shrink Undo in Recent changes | Card lost / replaced / cleared | device-only | P3 |

## 16. Free this space now, D1 (§5.8 p37–38, §3.4 p12–13)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| D101 | §5.8 p37 | Confirmed, original in trash, copy published | Re-check | Eligible | unit | P1 |
| D102 | §5.8 p37 | Not confirmed | Re-check | Not eligible | unit | P1 |
| D103 | §5.8 p37 | Original restored | Re-check | Not eligible | unit | P1 |
| D104 | §5.8 p37 | Original gone | Re-check | Not eligible | unit | P1 |
| D105 | §5.8 p37 | Lookup failed (original or copy) | Re-check | Not eligible | unit | P1 |
| D106 | §5.8 p37 | Copy missing / trashed / empty | Re-check | Not eligible | unit | P1 |
| D107 | §5.8 p37 | Mixed batch | Re-check | Only the eligible subset; others stay in the trash | unit | P1 |
| D110 | §5.8 p37 | Delete OK | Status | Original freed for good | unit | P2 |
| D111 | §5.8 p37 (v2) | Record without a fingerprint (older app version) | Re-check | Never eligible | unit | P2 |
| D112 | §5.8 p37 (v2) | Original in trash but size or modified time differs | Re-check | Not eligible | unit | P1 |
| D113 | §5.8 p37 (v2) | Media library version changed | Re-check | Not eligible | unit | P1 |
| D120 | §5.8 p37 | – | Link visibility | Only with trashed originals, ≥ 1 eligible, no batch running | device-only | P2 |
| D121 | §5.8 p37, §3.4 p13 | – | Both dialogs | Exact wordings; red confirm; Android delete dialog; cancel either → nothing | device-only | P1 |
| D122 | §5.8 p37 | – | OK | "Deleted N originals from the trash"; ledger refreshes | device-only | P3 |
| D123 | §5.8 p37–38 (v2) | Nothing eligible at re-check | Confirm | No Android dialog; snackbar "Nothing can be deleted for good right now — those originals stay in the trash until it empties" | device-only | P2 |
| D124 | §5.8 p38 (v2) | Some become ineligible | Confirm | Success count is the number Android actually deleted; op resolved on next start if app killed | device-only | P3 |
| D125 | §5.8 p37 | After D1 | Recent changes Undo | No undo; copy kept ("Restored 0 of 1; …") | device-only | P1 |

## 17. Recent changes and Undo (§5.9 p38–39)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| RH01 | §5.9 p39 | Shrink and trim, originals in trash | Rows | "Shrunk, original cleared" / "Trimmed"; size = saving; Undo | unit | P2 |
| RH02 | §5.9 p38 | Logged items | Rows | "Cleared · reason", "Moved to computer", "Folded into a contact sheet"; size = file size | unit | P2 |
| RH03 | §5.9 p38 | – | Order | Newest first | unit | P3 |
| RH04 | §5.9 p38 | – | Logged row exactly 30 × 24 h old and 1 ms older | Inside / outside | unit | P2 |
| RH04b | §5.9 p39 (v2) | – | Copies made 30 × 24 h ago, and 1 ms earlier with original in trash / gone | First two listed with Undo; old one whose original left the trash not listed | unit | P2 |
| RH05 | §5.9 p39 (v2) | Copy made 31 days ago, original still in trash | Rows | Listed with Undo, "Shrunk, original cleared" | unit | P2 |
| RH05b | §5.9 p39 (v2) | Copy made 2 days ago, original no longer in the trash | Rows | Listed, "Shrunk, original gone from the trash", no Undo | unit | P2 |
| RH06 | §5.9 p38 | Pending/undone/failed/confirmed | Rows | Only confirmed shrinks | unit | P2 |
| RH07 | §5.9 p38 | Undone logged row | Rows | Not listed | unit | P2 |
| RH08a | §5.9 p39 (v2) | Shrink original deleted for good (D1) | Rows | Listed, "Shrunk, original deleted for good", no Undo | unit | P2 |
| RH08b | §5.9 p39 (v2) | Moved item deleted for good (D2) | Rows | Listed, "Moved to computer, deleted from the phone", no Undo | unit | P2 |
| RH10 | §5.9 p39 (v2) | Trashed, moved, contact-sheet rows | Rows | Undo offered unless deleted for good | unit | P2 |
| RH09 | §5.9 p38 | – | Grouping | Date headers, newest day first | unit | P3 |
| RH20 | §5.9 p38 | – | Header | "Freed so far: X" always; "Still in the trash: X. …" when any | device-only | P3 |
| RH21 | §5.9 p38 | – | Undo a shrink row | Restore dialog; copy removed only if original back; same messages; clears the run card | device-only | P1 |
| RH22 | §5.9 p39 (v2) | – | Undo a logged row (still in trash / already left trash) | Restore dialog; row disappears; "Restored 1 item"/"Restored N items"; re-scan; contact sheet stays / "Nothing to restore — those files have left the trash" | device-only | P2 |
| RH23 | §5.9 p38 | – | Cancel Android's dialog | Nothing | device-only | P1 |
| RH24 | §5.9 p38 | Nothing changed | Open | "Nothing changed in the last 30 days." | device-only | P3 |

## 18. Album rules and cooling (§7.1 p49, §7.2 p50)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| AR01 | §7.1 p49 | – | Rule choices | Default, Light copy older than 1/2/3/5 years, Never touch (exact labels, in order) | unit | P3 |
| AR02 | §7.1 p49 | – | Album list | Most items first, then name A–Z; blank → "Unnamed album" | unit | P3 |
| AR03 | §7.1 p49 | – | Exactly 2 × 365.2425 days; +1 ms; 730.25 days | Old only strictly above N × 365.2425 days | unit | P2 |
| AR04 | §7.1 p49 | – | Unknown age | Never old | unit | P2 |
| AR05 | §7.1 p49 | LC 2 years | Old photo, newer photo, old video | Light copy / global / global | unit | P2 |
| AR06 | §7.1 p49 | LC rule | Old favourite ticked by hand | Gets a light copy | unit | P3 |
| AR07 | §7.1 p49 | Never touch | Photo, video | Excluded | unit | P1 |
| AR08 | §7.1 p49 | LC 5 y + cooling 1 y; LC 1 y | 2-year-old photo | Album rule wins | unit | P2 |
| AR09 | §7.1 p49 | Never touch + cooling | Old photo | Excluded (Never touch beats everything) | unit | P1 |
| AR10 | §7.1 p49 | – | Stored rule values | Round trip; unknown → Default | unit | P3 |
| AR20 | §7.1 p49 | – | Album rules screen | Header, intro, rows, colours, dialog, saves at once, Close, empty text | device-only | P3 |
| AR21 | §7.1 p49 | Never touch | Manual, Big videos, Trim, Make room, overnight, trip | Items removed; messages | device-only | P1 |
| CL01 | §7.2 p50 | Fresh install | Cooling setting | Off; choices 0/1/2/3 | integration | P2 |
| CL02 | §7.2 p50 | Cooling 2 | 2.5 y / 1.5 y photo | Light copy / global | unit | P2 |
| CL02b | §7.2 p50 | Cooling 1 | Exactly 1 year / +1 ms | Not / cooled | unit | P3 |
| CL03 | §7.2 p50 | – | Old favourite | Never cooled | unit | P1 |
| CL04 | §7.2 p50 | – | Old motion, Ultra HDR | Never cooled | unit | P1 |
| CL05 | §7.2 p50 | – | Old video | Never cooled | unit | P2 |
| CL06 | §7.2 p50 | – | Never touch / LC 3 y album | Album rules win | unit | P2 |
| CL07 | §7.2 p50 | Cooling off | 10-year-old photo | Global preset | unit | P2 |
| CL10 | §7.2 p50 | – | Settings › Automatic | Chips "Off", "Older than 1/2/3 yr", caption; Q5 sets 2 years / Off | device-only | P3 |
| CL11 | §7.2 p50 | Cooling on | Big videos light copy | Cooling not applied | device-only | P3 |

## 19. Storage floor, Make room, trip mode, overnight (§7.3–7.6 p50–55)

| ID | PDF | Preconditions | Steps / inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SF01 | §7.3 p50 | – | Goal 10 GB with 4 / 12 / 10 GB free; goal off | Gap = goal − free, never below 0; choices 0/5/10/15/20 | unit | P2 |
| SF02 | §7.3 p50 | Fresh install | Read, set 5–20, set 7 and 25 | Off by default; other values stored as Off | integration | P2 |
| SF03 | §7.3 p51 | – | Four situations, 1 and many originals | Exact morning texts; "original(s)" singular for 1 | unit | P2 |
| SF04 | §7.3 p50 | Free space unreadable | Gap | No gap assumed | unit | P2 |
| SF10 | §7.3 p50 | – | Settings, Q4 | Chips and caption; Q4 sets 10 GB / Off; does not switch overnight on | device-only | P3 |
| SF11 | §7.3 p51 | Goal set | Overnight finishes | Title "Below your free-space goal" / "Overnight shrink finished"; tap opens Ready to clear | device-only | P3 |
| SF12 | §7.3 p50 | Below goal | Overnight | Gap-closing items first; 300/10 caps filled | device-only | P2 |
| MR01 | §7.4 p51 | – | Target 0 / negative | Empty plan | unit | P3 |
| MR02 | §7.4 p52 | – | JPEG, PNG, H.264, Ultra HDR, motion, HEIC, HEVC | Rung 1: JPEG/PNG/H.264 with saving, largest first; Ultra HDR not included | unit | P2 |
| MR03 | §7.4 p51 | – | Target 4 MB | Stops as soon as the estimate reaches the target; later rungs absent | unit | P2 |
| MR04 | §7.4 p52 | – | Enough / short | Rung 2 only if rung 1 falls short | unit | P2 |
| MR05 | §7.4 p52 | – | Old JPEG/PNG/young/motion/HDR/video/HEIC; cooling 1; album LC 5 y | Still JPEG/PNG, not favourite/motion/Ultra HDR; album years, else cooling, else 2 years | unit | P2 |
| MR06 | §7.4 p52 | – | Two old 10 MB photos, target 7.5 MB | Light copy estimate 70 %; moved photo counts only extra saving | unit | P2 |
| MR07 | §7.4 p52 | – | Expired and suggestions with favourite, empty, repeated items | Skip favourites and zero-size; never repeat; largest first | unit | P2 |
| MR08 | §7.4 p52 | – | Waiting, never-touch, recent, favourite items | Rungs 1–2 leave them out | unit | P1 |
| MR08b | §7.4 p52 (v2) | – | Same kinds in expired/suggestions | "All four rungs" leave them out | unit | P2 |
| MR09 | §7.4 p52 | – | Rung titles | "Shrink N photos" / "… videos" / "… photos and M videos"; "Lighter copies of N photos from YYYY" or range; "Clear N expired items"; "Review N suggestions" | unit | P3 |
| MR10 | §7.4 p51 | – | Target chips | 2, 5, 10 GB | unit | P3 |
| MR11 | §7.4 p52 | – | Short plan | Below target | unit | P3 |
| MR13 | §7.4 p52 | – | Rung behaviour | Rungs 1–2 make copies; 3–4 only open review | unit | P1 |
| MR15 | §7.5 p53, §7.6 p54 | Caps 300/10 | Large library | Rungs 1–2 hold ≤ 300 photos and ≤ 10 videos | unit | P2 |
| MR20 | §7.4 p51 | – | Make room screen | Intro; "Back to N GB free · X" only with a goal gap; default target gap else 2 GB; pick sticks for session | device-only | P2 |
| MR22 | §7.4 p52 | – | Switch combinations | Button labels; no thousands separator; "Nothing selected" disabled | device-only | P3 |
| MR23 | §7.4 p52 | – | Run | One shrink batch + progress; Quick review on top with full queue | device-only | P2 |
| MR24 | §7.4 p52 | – | Empty / short | "Nothing to suggest right now." / "That's everything we found — about X of Y." | device-only | P3 |
| MR25 | §7.4 p52 | Vault on, no folder | Run | "Pick a vault folder first"; nothing runs | device-only | P1 |
| TP01 | §7.5 p53 | Sat 24 Oct | "In 2 days" | Mon 26 Oct | unit | P2 |
| TP02 | §7.5 p53 | Mon/Wed/Fri/Sat/Sun | "This weekend" | Coming Saturday; today on Sat/Sun | unit | P2 |
| TP03 | §7.5 p53 | Mon/Sat/Sun | "Next week" | Coming Monday | unit | P2 |
| TP04 | §7.5 p53 | – | Run time | 20:00 the day before; immediately if passed | unit | P2 |
| TP05 | §7.5 p53 | – | Trip day ends | Trip disappears | unit | P3 |
| TP06 | §7.5 p53 | – | Label | "Sat 24 Oct" | unit | P3 |
| TP07 | §7.5 p53 | – | Choices | "In 2 days", "This weekend", "Next week"; 2/5/10 GB | unit | P3 |
| TP08 | §7.5 p53 | – | 5 GB needed with 6 / 3 GB free | Gap 0 (only notify) / 2 GB | unit | P2 |
| TP09 | §7.5 p53 | Unattended | Old photo; with cooling; with album LC rule | No 2-year fallback; light copy only when opted in | unit | P2 |
| TP12 | §7.5 p53 | – | Plan two trips; old job finishes | New plan replaces old; finishing old does not remove new | integration | P2 |
| TP20 | §7.5 p53 | – | Trip card | Defaults This weekend / 5 GB; snackbar; card texts; "Cancel trip" silent | device-only | P3 |
| TP21 | §7.5 p53 | Enough space | Trip runs | "Ready for your trip" / "You already have N GB free. Nothing to do." | device-only | P2 |
| TP22 | §7.5 p53 | Not enough | Trip runs | Rungs 1–2 with caps; "Trip prep: N items shrunk"/"Trip prep finished" texts | device-only | P2 |
| TP23 | §7.5 p53 | – | Trip runs | No charging/idle condition | device-only | P2 |
| TP24 | §7.5 p53 | Vault on, no folder | Trip runs | "Overnight shrink skipped"; trip dropped | device-only | P2 |
| ON01 | §7.6 p54 | – | 400 photos + 20 videos | 300 photos and 10 videos, order kept | unit | P2 |
| ON02 | §7.6 p54 | – | JPEG, PNG, H.264, Ultra HDR, motion, HEIC, HEVC | Only JPEG/PNG/H.264 eligible | unit | P1 |
| ON06 | §7.3 p50, §7.6 p54 | Goal set | Plan for the gap | Largest estimated savings first | unit | P2 |
| ON10 | §7.6 p54 | – | Toggle "Shrink overnight" | Asks notification permission; schedules / cancels | device-only | P2 |
| ON11 | §7.6 p54 | – | Job constraints | Every 24 h; charging, idle, battery not low; no clock time | device-only | P2 |
| ON12 | §7.6 p54 | Protected, never-touch, waiting items | Night run | Excluded; vault used when on | device-only | P1 |
| ON14 | §7.6 p54 | – | Morning | "Overnight shrink finished" / "Ready to clear: N originals · X. Tap to clear them." or "nothing to do"; tap opens Ready to clear | device-only | P3 |
| ON15 | §7.6 p54 | Vault on, no folder | Night run | Skipped; notification opens Home | device-only | P2 |
| ON16 | §7.6 p54 | – | Morning check | Nothing trashed or deleted | device-only | P1 |
