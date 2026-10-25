# GallerySlim black-box test cases: Clean up, keeping slim, and other features

Source of truth: `docs/features/GallerySlim-Features.pdf` (Feature Reference, 2026-10-24). Revision 2 of this catalogue follows the updated 92-page PDF; rows marked **(rev 2)** were changed or added from the new text. Page numbers are the PDF's own "Page N of 93". Every expected result below comes from the PDF. Source code was read only to find out how to call things.

Scope: Clean up lists, ID shield, expiry / Keep for a while, swipe review, Quick review, Duplicates, Chat media, standing rules and the weekly nudge, low-space alert, widget and forecast, Your month, Search, Share small, safety vault, app lock, notifications, Settings, Privacy and How it works, and Hindi.

Tests: `app/src/test/java/com/galleryslim/blackbox/cleanup/` (JUnit 4, JVM). Each test method's name starts with its case ID.

**Types**
- **unit**: pure logic called directly.
- **integration**: two or more app units chained the way the app chains them.
- **resource**: `res/` or manifest XML parsed.
- **copy**: a quoted PDF string searched for in the shipped Kotlin source, with `"a" + "b"` joins and escapes undone.
- **device-only**: needs a phone or the UI. Not automated.

**Priority**
- **P1**: safety guarantee or core rule.
- **P2**: important behaviour.
- **P3**: wording, cosmetic or edge case.

Fixed "now" in the unit tests: 2026-10-24 12:00 UTC. An "age of N days" means date taken = now − N×86,400 s. The PDF (§2.1, p7) says age comes from date taken, or date added when date taken is missing.

## 1. Clean up lists: PDF §6.1, pp40-41

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| CL-01 | 6.1 p40 | photo checked by ID shield, reading None | album "Screenshots", age exactly 30 d | in "Old screenshots" (age ≥ 30 d) | unit | P1 |
| CL-02 | 6.1 p40 | as CL-01 | age 30 d − 1 s; age 29 d | not listed | unit | P1 |
| CL-03 | 6.1 p40 | as CL-01 | age 31 d | listed | unit | P2 |
| CL-04 | 6.1 p40 | as CL-01 | album Camera, name "Screenshot_…png", 40 d | listed (name starts with "screenshot") | unit | P2 |
| CL-05 | 6.1 p40 | as CL-01 | album "my SCREENSHOTS"; name "SCREENSHOT_2.PNG" | both listed (any case) | unit | P2 |
| CL-06 | 6.1 p40 | as CL-01 | name "my_screenshot.png", album Camera | not listed (name must *start* with screenshot) | unit | P3 |
| CL-07 | 6.1 p40 | – | videos in Screenshots album / named Screenshot_*.mp4, 40 d | not in Old screenshots ("Photos (not screen recordings)") | unit | P2 |
| CL-08 | 2.1 p7, 6.1 | – | taken 10 d ago, added 100 d ago | not listed (date taken wins) | unit | P1 |
| CL-09 | 2.1 p7 | – | no date taken; added 31 d / 29 d ago | 31 d listed, 29 d not | unit | P1 |
| CL-10 | 6.1 p40 | – | albums WhatsApp Images, Telegram, Snapchat, Messenger, Signal, Instagram, WeChat, LINE; 30 d | all in "Old chat media" | unit | P1 |
| CL-11 | 6.1 p40 | – | IMG-/VID-/AUD-/PTT-20240101-WAnn names in album Camera | all in Old chat media | unit | P2 |
| CL-12 | 6.1 p40 | – | 7-digit date, underscores, missing WA, other prefix | none listed | unit | P3 |
| CL-13 | 6.1 p40 | – | WhatsApp photo 30 d − 1 s and 30 d | only 30 d listed | unit | P1 |
| CL-14 | 6.1 p40 | – | Screenshot_1.png inside WhatsApp Images, 40 d | not in chat media; in Old screenshots | unit | P2 |
| CL-15 | 6.1 p40 | – | WhatsApp Video video, 40 d | in Old chat media | unit | P2 |
| CL-16 | 6.1 p40 | – | albums "Skyline", "Timeline" | not chat media ("contains one of the *words*… line"). This reads the PDF wording as whole words. | unit | P3 |
| CL-17 | 6.1 p40 | reading Greeting | album Camera, ages 7 d and 7 d − 1 s | 7 d listed; younger not (age ≥ 7 d, any album) | unit | P1 |
| CL-18 | 6.1 p40 | readings None/Receipt/Ticket | 20 d | not in Greetings | unit | P2 |
| CL-19 | 6.1 p40 | readings Receipt, Ticket | ages 30 d, 30 d − 1 s, 29 d | only 30 d ones in "Old receipts & tickets" | unit | P1 |
| CL-20 | 6.1 p40 | readings Greeting, None | 60 d | not in receipts | unit | P2 |
| CL-21 | 6.1 p41 | – | albums Download, Downloads, DOWNLOADS (photo + video) 30 d; Download 30 d − 1 s | first three listed | unit | P1 |
| CL-22 | 6.1 p41 | – | "Download Manager", "MyDownloads", "WhatsApp Downloads" | not listed (only exactly Download/Downloads) | unit | P2 |
| CL-23 | 6.1 p40 | keep-until passed | 1-day-old photo, 2-day-old video | both in "Expired" | unit | P1 |
| CL-24 | 6.1 p40 | keep-until in future | 100-day photo | not Expired | unit | P1 |
| CL-25 | 6.1 p41 | keep-until +7 d | one item per age list | hidden from every age list and Expired; control without tag is listed | unit | P1 |
| CL-26 | 6.1 p41 | favourites | one favourite per list, incl. expired | no list shows any of them | unit | P1 |
| CL-27 | 6.1 p41 | – | 3 screenshots 300/900/600 B; downloads 10/20 B | largest first | unit | P2 |
| CL-28 | 6.1 p41 | protect window default 30 d | greeting 7 d old | listed (no list uses the protect window) | unit | P2 |
| CL-29 | 6.1 p41 | nothing ID-checked | old Download video, old chat video | listed (videos appear immediately) | unit | P1 |
| CL-30 | 6.1 p41, 6.2 | nothing ID-checked | old photos in every list | no list shows them | unit | P1 |
| CL-31 | 6.1 p40-41 | – | list enum | 7 titles + descriptions exactly as the table, fixed order | unit | P2 |
| CL-32 | 6.1 p41 | – | probable-list photo, low-quality find, other | ID-check order: probable first, then low-quality, then rest | unit | P3 |
| CL-33 | 6.1 p41 | – | – | ID checks in batches of 24 | unit | P3 |
| CL-34 | 6.1 p40-41 | device | open Clean up | nothing selected by default; Select all / Clear act on current list; selection shared across lists; button trashes only current list's ticks | device-only | P2 |
| CL-35 | 6.1 p40-41 | device | select, "Move N to trash ( size )" | Android trash dialog; OK: items leave every list, logged in Recent changes and Your month, no message; Cancel: nothing changes, selection kept | device-only | P1 |
| CL-36 | 6.1 p41 | – | source text | "Select all", "Review one by one", bottom-bar text, "Nothing here — nice.", checking captions, "Keep selected for", 1 week/1 month/3 months shipped | copy | P3 |
| CL-37 | 6.1 p41 | device, force list preparation failure | – | all lists stay empty (fails closed) | device-only | P2 |
| CL-38 | 6.1 p40 | device | open Suggestions | title "Clean up", opens on Old screenshots, "Duplicates" is last button | device-only | P3 |

## 2. Low-quality photos: PDF §6.1 issue table, p41

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| LQ-01 | p41 | sharp, well-exposed pixels | long edge 639, 1, 640, 0 (unknown) | "Very small" for 1–639 only | unit | P1 |
| LQ-02 | p41 | – | flat 34 vs flat 35 | Too dark at mean 34; not at 35 | unit | P1 |
| LQ-03 | p41 | – | mean 18 with top 10 % at 90 vs 89 | p95 = 90 → not dark; 89 → dark | unit | P1 |
| LQ-04 | p41 | – | flat 226 vs 225 | Overexposed at 226 only | unit | P1 |
| LQ-05 | p41 | – | 41 % at 250; exactly 40 % at 255; 41 % at 249 | only first is Overexposed (> 40 % at ≥ 250) | unit | P1 |
| LQ-06 | p41 | – | smooth horizontal ramp (content, no edges) | Blurry | unit | P1 |
| LQ-07 | p41 | – | flat 128; bands with variance 168.75 | not Blurry (plain sky/walls) | unit | P1 |
| LQ-08 | p41 | – | pixel variance exactly 300 vs ~285 (soft) | 300 → Blurry; 285 → not ("at least 300") | unit | P2 |
| LQ-09 | p41 | – | Laplacian variance exactly 100 vs ~95 | 100 → not Blurry; 95 → Blurry ("below 100") | unit | P2 |
| LQ-10 | p41 | – | dark soft image meeting the blur tests | Too dark, never Blurry | unit | P2 |
| LQ-11 | p41 | – | sharp checkerboard, 4000 px | no issues | unit | P2 |
| LQ-12 | p41 | – | – | labels "Very small", "Too dark", "Overexposed", "Blurry" | unit | P3 |
| LQ-17 | p41 | – | – | decoded at about 256 px | unit | P3 |
| LQ-13 | p40 | device | blurry photo taken today | appears (photos of any age) | device-only | P2 |
| LQ-14 | p41 | device | blurry favourite | never listed | device-only | P1 |
| LQ-15 | p43 note | device | Keep selected for 1 week on a low-quality photo, restart app | hidden until restart, then back (known) | device-only | P3 |
| LQ-16 | p40 | device | – | caption " size · issues " (e.g. "2 MB · Blurry, Too dark") | device-only | P3 |

## 3. ID shield: PDF §6.2, p42; guarantee §1, p4

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| ID-01 | p42 | – | each of the 20 ID phrases, upper-cased, in other text | ID document | unit | P1 |
| ID-02 | p42 | – | "1234 5678 9012" | ID (Aadhaar-style) | unit | P1 |
| ID-03 | p42 | – | "ABCDE1234F" | ID (PAN) | unit | P1 |
| ID-04 | p42 | – | "A1234567" | ID (passport no.) | unit | P1 |
| ID-05 | p42 | – | receipt words + "Date of Birth"; ticket words + passport | ID wins | unit | P1 |
| ID-06 | p42 | – | "BOARDING PASS Gate 22"; every ticket word paired with "pnr" | Ticket | unit | P1 |
| ID-07 | p42 | – | 2 ticket + 2 receipt; 2 ticket + 3 receipt | Ticket; Receipt | unit | P2 |
| ID-08 | p42 | – | one ticket word | not a ticket (None) | unit | P2 |
| ID-09 | p42 | – | 2 receipt words; 1 word; every receipt word paired with "upi" | Receipt; None; Receipt | unit | P1 |
| ID-10 | p42 | – | good morning/night, shubh prabhat, happy diwali/holi, eid mubarak, jai shri, quote of the day, happy weekday | Greeting | unit | P1 |
| ID-11 | p42 | – | Devanagari-only card text | not detected (None) | unit | P3 |
| ID-12 | p42 | – | "", whitespace, unrelated text | None | unit | P2 |
| ID-13 | p42 | – | each kind | ID and Unknown shielded; Receipt/Ticket/Greeting/None not | unit | P1 |
| ID-14 | p42, p4 | – | – | ID and unreadable never in any Clean up list (see SF-01) | unit | P1 |
| ID-15 | p4 guarantee, p42 | – | "GOVERNMENT OF\nINDIA", "Date of\nBirth", "Income  Tax  Department" (OCR line breaks / double spaces) | still an ID document. Section 1 says ID documents never appear in the Clean up lists; the PDF does not discuss whitespace, so this applies that guarantee to text read off a real card. | unit | P2 |
| ID-18 | p42 | – | "Good\nMorning…" | Greeting | unit | P3 |
| ID-16 | p42 | device | photo checked, then edited (size changes) | read again; unchanged photo not re-read | device-only | P3 |
| ID-17 | p42 | device | unreadable photo | not remembered; retried next session; never listed | device-only | P2 |

## 4. Expiry dates and "Keep for a while": PDF §6.3, p43

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| EX-01 | p43 | taken 2026-03-01 10:30 UTC | "Your OTP is 482913" | keep-until = taken + exactly 24 h | unit | P1 |
| EX-02 | p43 | – | otp / one time password / one-time password / verification code / security code / login code + 4 digits | each → taken + 24 h | unit | P1 |
| EX-03 | p43 | – | 4, 8, 3, 9 digit codes | 4 & 8 → expiry; 3 & 9 → none | unit | P2 |
| EX-04 | p43 | – | OTP keyword, no number | none | unit | P2 |
| EX-05 | p43 | ticket | 12/03/2026, 2026-03-12, "12 Mar 2026", "Mar 12, 2026" | each → start of 2026-03-13 | unit | P1 |
| EX-06 | p43 | ticket | two dates 05/03 and 20/04 | latest (start of 21 Apr) | unit | P1 |
| EX-07 | p43 | ticket | dates −7 d, −8 d, +400 d, +401 d from capture | −7 and +400 used; −8 and +401 ignored | unit | P1 |
| EX-08 | p43 | ticket | no date | none | unit | P2 |
| EX-09 | p43 | receipt / none kind | printed date | none (only tickets use dates) | unit | P2 |
| EX-10 | p43 | – | date taken unknown | none | unit | P1 |
| EX-11 | p43 | ID / unreadable | OTP + ticket text | none | unit | P1 |
| EX-12 | p43 | existing tag | reader wants new date | existing never overwritten | unit | P1 |
| EX-13 | p43 | – | – | choices 7 / 30 / 90 days (1 week, 1 month, 3 months) | unit | P2 |
| EX-14 | p43 | tag exists | Keep selected for again | replaces earlier date | unit | P2 |
| EX-15 | p43 | ticket for 2026-04-20 shot 2026-03-01 | lists on 04-10, 04-20, 04-21 | hidden, hidden, then Expired and Old receipts & tickets | integration | P1 |
| EX-15b | p43 | ticket for 2026-03-12 | lists on 03-14 | Expired only (< 30 d old) | integration | P2 |
| EX-16 | p43 | device | expired item | only suggested; never deleted automatically | device-only | P1 |
| EX-17 | p43 | device | select in a list → Keep selected for | row shown only with a selection in current list; items leave lists, unselected; no message | device-only | P2 |

## 5. Swipe review: PDF §6.4, p44

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SW-01 | p44 | – | source | "Space you'll free: ", Keep, Trash, caption "Swipe right to trash, left to keep. You'll review the selection before anything moves." | copy | P3 |
| SW-02 | p44 | device | drag 100 / 110 / 219 / 221 px | overlay at 110 px; decision only past 220 px; less springs back | device-only | P3 |
| SW-03 | p44 | device | pre-marked item swiped left | kept | device-only | P2 |
| SW-04 | p44 | device | finish swiping | nothing trashed until confirmation (Chat media: Android dialog directly) | device-only | P1 |
| SW-05 | p40 | device | Review one by one, finish / Back | decisions replace list selection; Back keeps old selection | device-only | P2 |

## 6. Quick review: PDF §6.5, pp44-45; rules §7.7, p55

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| QR-01 | p45 | one item per list | lists supplied in reverse order | queue order Old screenshots, Old chat media, Low-quality, Expired, Receipts, Greetings, Old downloads | unit | P1 |
| QR-02 | p45 | lists built by the list rules | sizes 100/300, 50/500 | largest first within each list | integration | P2 |
| QR-03 | p45 | groups in finder order | exact, burst, similar groups | after lists: exact extras, then burst, then similar | unit | P1 |
| QR-04 | p45 | groups supplied mixed | similar, burst, exact | still exact → burst → similar | unit | P3 |
| QR-05 | p45 | item in Expired, Old screenshots and an exact group | – | appears once, reason "Old screenshots" (first wins) | unit | P1 |
| QR-06 | p45 | – | – | reasons = list titles, "Exact copy", "Burst, not the best shot", "Very similar shot" | unit | P1 |
| QR-07 | p45 | – | – | keepers never queued | unit | P1 |
| QR-08 | p45 | no rules | – | Expired pre-marked (only) | unit | P1 |
| QR-09 | p45, p55 | rule 1 on/off | – | Old screenshots pre-marked only when on | unit | P1 |
| QR-10 | p45, p55 | rule 2 / rule 3 | low-quality items of all 4 issues | Greetings pre-marked; whole Low-quality list pre-marked | unit | P1 |
| QR-11 | p45 | all rules | – | chat media, receipts, downloads never pre-marked; rules 4–5 add nothing | unit | P1 |
| QR-12 | p45 | all rules on | duplicate groups | duplicate extras not pre-marked | integration | P1 |
| QR-13 | p6, p42 | near group, one member identified as ID | – | ID photo not in queue; others are | integration | P1 |
| QR-18 | p45 | item in screenshots + Expired | – | pre-marked once; reason Old screenshots | unit | P2 |
| QR-19 | p43, p45 | expired photo 3 d old | – | pre-marked with no rules | integration | P2 |
| QR-20 | 6.5 p45, 6.2 p42 | duplicate pair, nothing ID-checked yet | – | extra never enters Quick review **(rev 2)** | integration | P1 |
| QR-21 | 6.5 p45 | similar group | favourite, recent, Never-touch and removable extras | only the removable extra is queued **(rev 2)** | integration | P1 |
| QR-15 | p44-45 | – | source | summary/finish/empty texts shipped | copy | P3 |
| QR-14 | p44-45 | device | Start swiping, swipe, Back / Move N to trash, OK / Cancel | queue frozen at start; Back discards; one Android trash dialog; OK logs each reason; Cancel nothing | device-only | P1 |
| QR-16 | p45 note, B#9 | device | trash then Review again | known: "You kept everything.", trashed items still in queue | device-only | P3 |
| QR-17 | p45 | device | open Quick review cold | list preparation and duplicate scan start | device-only | P3 |

## 7. Duplicates: PDF §6.6, pp45-47

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| DU-01 | p46 | same size | same quick + same SHA-256; one different SHA | the two identical form a group | unit | P1 |
| DU-02 | p46 | – | quick hashes differ | no group; full hash only for quick collisions | unit | P2 |
| DU-03 | p46 | – | unreadable (null quick / null full) | never joins | unit | P1 |
| DU-04 | p46 | 300 KB files | change in middle / head / tail / length | quick hash = first & last 64 KB + length | unit | P2 |
| DU-05 | p46 | – | – | remembered per id, size, date modified | unit | P3 |
| DU-06 | p46 | same album | 3 shots, gaps 1,500 ms | burst | unit | P1 |
| DU-07 | p46 | – | gap 1,501 ms | no burst | unit | P1 |
| DU-08 | p46 | – | 2 shots | no burst (≥ 3 shots) | unit | P1 |
| DU-09 | p46 | – | different albums | no burst | unit | P1 |
| DU-10 | p46 | – | middle shot without date taken | not in burst | unit | P2 |
| DU-11 | p46 | – | 5 shots each 1.4 s apart | one burst of 5 (consecutive gaps) | unit | P2 |
| DU-12 | p46 | – | 3 videos 0.5 s apart | no burst (photos only) | integration | P2 |
| DU-13 | p46 | – | 10 / 11 differing bits | ≤ 10 similar; 11 not; 9×8 → 64 bits | unit | P1 |
| DU-13b | p46 | – | 9×8 luma patterns | difference hash has 64 bits (one per adjacent pair, 8 rows × 8) | unit | P3 |
| DU-14 | p46 | – | A–B 10, B–C 10, A–C 20 bits | one transitive group | unit | P2 |
| DU-15 | p46 | burst of 3, one favourite | – | favourite is keeper | integration | P1 |
| DU-16 | p46 | – | face sets | no faces lowest; worst face counts; open eyes / smile rank higher | unit | P2 |
| DU-17 | p46 | checked, old photos | group with a favourite | all non-keepers pre-selected except favourites | unit | P1 |
| DU-18 | p47 | – | – | keeper never pre-selected or selectable | unit | P1 |
| DU-19 | p46 | – | ID members in a pair and a trio (near) | ID removed; pair disappears; trio → 2 | unit | P1 |
| DU-20 | 6.6 p46 | exact trio | one copy ID; separately one copy Unknown | whole exact group removed (byte-identical copies) **(rev 2)** | unit | P1 |
| DU-20b | 6.6 p46 | exact pair, only one copy ID-checked | – | one checked copy counts for all: nothing held, non-keeper pre-selected **(rev 2)** | unit | P2 |
| DU-21 | 6.6 p46, 6.2 p42 | – | pair / trio with one Unknown member | unreadable removed; pair disappears, trio → 2 **(rev 2)** | unit | P1 |
| DU-22 | 6.6 p46, 6.2 p42 | nothing ID-checked | pair | group stays; non-keeper held "Not checked yet", never pre-selected, still tickable by hand **(rev 2)** | unit | P1 |
| DU-22b | 6.6 p46 | – | same pair once checked | label clears **(rev 2)** | unit | P3 |
| DU-23 | 6.6 p46 | photos 5 d old (inside protect window) | exact pair | non-keeper held "Protected · recent", not pre-selected **(rev 2)** | unit | P2 |
| DU-24 | p46 | – | trash members | groups with < 2 left disappear | unit | P1 |
| DU-25 | p46 | – | long-press 3rd photo | it becomes keeper; old keeper selectable | unit | P2 |
| DU-31 | 6.6 p46 | – | favourite; Never-touch album; album rules unreadable | labels "Protected · favourite", "Never-touch album", "Album rules unavailable"; none pre-selected **(rev 2)** | unit | P2 |
| DU-32 | 6.6 p46-47 | burst groups | all checked; one unchecked; one favourite; non-burst | "Keep best + contact sheet" offered only for bursts with nothing held besides the keeper **(rev 2)** | unit | P1 |
| DU-27 | p46 | – | 3…25 shots | 2 cols ≤ 4, 3 cols ≤ 9, else 4; 480 px cells | unit | P2 |
| DU-29 | p45-46 | – | source | header, labels, scanning, empty, contact-sheet texts and folder shipped | copy | P3 |
| DU-26 | 6.6 p46 | device | long-press another photo when the old keeper is removable / a favourite | old keeper becomes selected only if removable (not held) **(rev 2)** | device-only | P2 |
| DU-28 | p46 | device | Keep best + contact sheet; OK / Cancel / failure | sheet saved first (Pictures/GallerySlim/Contact sheets/Burst_<best>_sheet.jpg, q88, dated like best); all non-keepers incl. favourites in one dialog; Cancel deletes sheet; failure snackbar | device-only | P1 |
| DU-30 | p45 | device | open Duplicates twice in a session | scan runs once; "Move N duplicates to trash" disabled with none selected; tapping keeper does nothing | device-only | P3 |

## 8. Chat media: PDF §6.7, p47

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| CM-01 | p47 | – | 3 chat albums | one row each, largest first, photo/video counts, sizes | unit | P1 |
| CM-02 | p47 | – | WhatsApp-named photo with blank album | row "Chat media" | unit | P2 |
| CM-03 | p47 | – | Camera, WA-named, Signal, Download | only WA-named + Signal counted | unit | P1 |
| CM-04 | p47 | – | ages 29/30 (1 mo), 89/90 (3 mo), 180 (6 mo), 359/360 (1 yr) | month = 30 d; 1 year = 360 d | unit | P1 |
| CM-05 | p47 | – | – | choices 1, 3, 6, 12 months | unit | P3 |
| CM-06 | p47 | favourite + normal, old | – | favourite not in review but in totals | unit | P1 |
| CM-07 | p47 | – | mixed sizes/ages | review largest first, only older than cut-off | unit | P2 |
| CM-08 | p47 | – | future keep-until | not checked (still offered) | structural: `ChatDiet.review` takes no keep-until input | P3 |
| CM-09 | 6.7 p47-48, 6.2 p42 | old chat photos | readings ID, Unknown, None | only None offered (ID shield applies) **(rev 2)** | unit | P1 |
| CM-10 | 6.7 p47-48 | photo never checked, video | – | photo held and counted (N, size) for the not-checked line; video offered **(rev 2)** | unit | P1 |
| CM-11 | 6.7 p48 | cut-off 1 month | 45-day photo, protect 90 d vs 30 d | not offered inside protect window; offered outside **(rev 2)** | unit | P2 |
| CM-14 | 6.7 p48 | – | Never-touch album; album rules unreadable | Never-touch not offered; nothing offered when rules unreadable **(rev 2)** | unit | P2 |
| CM-15 | 6.7 p48 | held items | – | still counted in folder totals **(rev 2)** | unit | P2 |
| CM-13 | p47 | – | source | top line, "older than the cut-off", "1 year", review caption, " not checked for ID documents yet — they appear here once checked." **(rev 2)** | copy | P3 |
| CM-12 | p47 | device | Review N old items, swipe all | starts nothing marked, largest first, reason = album; Android dialog right after last swipe; Back discards; OK logs album as reason; default cut-off 3 months | device-only | P1 |

## 9. Standing rules and weekly nudge: PDF §7.7, p55

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SR-01 | p55 | – | – | 5 questions + detail lines exact, in order | unit | P2 |
| SR-02 | p55 | – | – | 1→Old screenshots, 2→Greetings, 3→Low-quality, 4–5 → no list | unit | P1 |
| SR-03 | p55 | fresh install | – | all rules off; setup not done | unit | P1 |
| SR-04 | p55 | – | answer, change | saved immediately; answering ≠ setup done | unit | P2 |
| SR-06b | p55, p70 | – | source | nudge texts, channel, setup texts shipped | copy | P3 |
| SR-05 | p55 | device | only rule 4/5 on; then rule 1 on | no nudge; nudge (does not check matches) | device-only | P2 |
| SR-06 | p55 | device | – | every 7 d, first after 3 d; silent channel; tap → Quick review | device-only | P2 |
| SR-07 | p55 | device | answer 4/5 Yes/No | storage goal 10 GB / Off; cooling 2 y / Off, overwriting Settings | device-only | P2 |
| SR-08 | p55 | device | back part-way; finish | answers kept, setup not done; "Rules saved…" then closes | device-only | P3 |

## 10. Low-space alert: PDF §7.8, p56

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| LS-01 | p56 | last alert 25 h ago | 2,147,483,647 B free | alert | unit | P1 |
| LS-02 | p56 | – | exactly 2,147,483,648 B | no alert (below 2 GB only) | unit | P1 |
| LS-03 | p56 | – | last alert 24 h / 24 h − 1 ms ago | alert / no alert | unit | P1 |
| LS-04 | p56 | never alerted | 500 MB | alert | unit | P2 |
| LS-05 | p56 | – | – | threshold 2 GiB | unit | P2 |
| LS-06 | p56 | – | source | "Running low: X GB free", "Make room now, before the camera says it's full?", channel "Low space" | copy | P3 |
| LS-07 | p56 | device | app opened with < 2 GB | no alert from opening; 12 h job alerts; tap → Make room | device-only | P2 |
| LS-08 | p56 | device | 2.1e9 B free | "Running low: 2.1 GB free" (decimal, one decimal) | device-only | P3 |

## 11. Widget and forecast: PDF §7.9, pp56-57; Home §3.3, p11

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| WF-01 | p57 | – | no forecast | "Tracking how fast it fills up" | unit | P1 |
| WF-02 | p57 | – | 0 | "Almost full" | unit | P1 |
| WF-03 | p57 | – | 366, 365, 12 | "Plenty of room at this rate"; "Full in about 365 days at this rate" | unit | P1 |
| WF-04 | p57 | – | 1 | "Full in about 1 days at this rate" (no singular) | unit | P3 |
| WF-05 | p57 | – | 2 samples | no forecast | unit | P1 |
| WF-06 | p57 | – | span 1.9 d vs 2.0 d | none vs 9 days | unit | P1 |
| WF-07 | p57 | – | 3 samples > 30 d old + 2 recent | none (old ignored) | unit | P1 |
| WF-08 | p57 | – | rising; flat | none | unit | P1 |
| WF-09 | p57 | – | 10.6 GB / 11.4 GB now, −1 GB/day | 10 and 10 (rounded down) | unit | P1 |
| WF-10 | p57 | – | fitted free 200 MB | 0 (minimum 0) | unit | P2 |
| WF-11 | p57 | – | 5 noisy samples | least-squares result | unit | P2 |
| WF-12 | p56-57 | – | widget XML | min 180×110 dp, 3×2 cells, resizable, refresh 6 h, description text | resource | P3 |
| WF-13 | p57 | – | widget layout | "GallerySlim" title, "Shrink now" button | resource | P3 |
| WF-14 | p57 | device | – | at most one sample per 6 h; last 120 kept | device-only | P3 |
| WF-15 | p57, p7 | device | – | "12.9 GB free" decimal; tap opens Home, no shrink | device-only | P3 |
| WF-16 | p57 | device | delete via Clean up / Duplicates / Quick review / Chat media; shrink | widget refreshes after deletions, not after shrinks | device-only | P3 |

## 12. Your month: PDF §7.10, pp57-58

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| MR-01 | p58 | confirmed copies made 1 Oct 00:00 and 31 Oct 23:59 | – | both counted; saving = original − copy | unit | P1 |
| MR-02 | p58 | copies 30 Sep, 1 Nov, pending in Oct | – | not counted | unit | P1 |
| MR-03 | p58 | – | saving 6 MB + cleared 7 MB | total 13 MB; room = total ÷ 3,000,000 = 4 | unit | P1 |
| MR-04 | p7, p58 | – | 1,000,000,000 / 1,234,567,890 / 999,400,000 / 850,000,000 B | "1.0 GB", "1.2 GB", "999 MB", "850 MB" | unit | P2 |
| MR-05 | p58 | – | tallies 3+2 (+0) in Oct, 1 in Nov | Oct 5 items, Nov 1, Sep 0; zero clears ignored | unit | P1 |
| MR-06 | p58, p73 | phone in Hindi | – | title "October 2026" (notifications and screens are English) | unit | P3 |
| MR-07 | p57-58 | – | source | gallery-slim-yyyy-MM.png, share text, card lines, navigation texts shipped | copy | P3 |
| MR-08 | p58 | device | month changes | one notification for last month on first daily run; none if nothing freed; tap opens Your month (current month, known) | device-only | P2 |
| MR-09 | p57 | device | – | "‹ Earlier" always, "Later ›" only on earlier months; Share disabled at 0 | device-only | P3 |
| MR-10 | p58 | device | clear, then undo; trash export delete | cleared tally not reduced; export deletions not counted twice | device-only | P2 |
| MR-11 | p58 | device | copy confirmed, then deleted for good (D1) | open question: the PDF says "confirmed now"; unclear if freed-for-good copies still count | device-only | P3 |

## 13. Search: PDF §9.1, p66

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SE-01 | p66 | – | "Beach,SUNSET!!2024" | beach, sunset, 2024 | unit | P1 |
| SE-02 | p66 | – | filler words, one-letter words | dropped | unit | P1 |
| SE-03 | p66 | label beach | "beach dog" | no match (every word must match) | unit | P1 |
| SE-04 | p66 | – | each listed synonym | finds its label | unit | P2 |
| SE-05 | p66 | label mountain | "moun", "mou" | 4+ letters prefix-matches; 3 doesn't | unit | P2 |
| SE-06 | p66 | text "invoice…" | "invoice" | match | unit | P1 |
| SE-07 | p66 | – | label / text / both | 3 / 1 / 4 points; label outranks text | unit | P1 |
| SE-08 | p66 | equal scores | – | newest first (newer = larger id; the index stores no date) | unit | P2 |
| SE-09 | p66 | – | "", "the photo" | nothing | unit | P2 |
| SE-11 | p66 | – | source | "Search photos", "No matches yet.", progress text | copy | P3 |
| SE-10 | p66 | device | – | only photos indexed; labels ≥ 0.6; 960 px copy; first 600 chars; re-index on size change; stops on leaving screen; refresh every 25 | device-only | P3 |

## 14. Share small: PDF §9.2, pp66-67

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SS-01 | p67 | – | 4000×3000, 3000×4000, 1000×800, 2048×1000 | long edge ≤ 2048, never upscaled | unit | P1 |
| SS-02 | p67 | – | 1920×1080, 1080×1920, 640×360 | short edge ≤ 720, never upscaled | unit | P1 |
| SS-03 | p67 | – | – | JPEG q82, ~2.5 Mbit/s | unit | P3 |
| SS-04 | p67 | – | – | up to 50 items | unit | P2 |
| SS-05 | p67 | – | "my photo (1).jpg", "fér!.png", 104-char name | other chars → "_", cut to 80 | unit | P2 |
| SS-07 | p67 | – | source | spinner, originals line, failure line, chooser title, _small.jpg/.mp4 | copy | P3 |
| SS-06 | p67 | device | share from Gallery | previous copies deleted first; not-smaller → original; metadata/location dropped; content:// only; failure "Couldn't read these files." | device-only | P2 |

## 15. Safety vault: PDF §9.3, pp67-68

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SV-01 | p68 | – | `/ \ : * ? " < > \|` | each → "_" | unit | P2 |
| SV-02 | p68 | – | control chars | "_" | unit | P3 |
| SV-03 | p68 | – | surrounding spaces, trailing dots | removed | unit | P2 |
| SV-04 | p68 | – | 305-char name | 127 chars, extension kept | unit | P2 |
| SV-05 | p68 | – | "", "   ", "..." | "unnamed" | unit | P3 |
| SV-06 | p68 | – | clashes ignoring case | "IMG_001 (2).jpg", "(3)" | unit | P1 |
| SV-07 | 9.3 p67 | – | byte counts equal / differ / unreadable | accepted only when length (and SHA-256) match | unit | P1 |
| SV-07b | 9.3 p67 | – | same length, different SHA-256 | rejected **(rev 2)** | unit | P1 |
| SV-09 | p67-68 | – | source | vault screen and snackbar texts shipped | copy | P3 |
| SV-08 | p67-68 | device | switch on without folder; pick internal folder; shrink with vault on/no folder; restore | picker; cancel turns off; internal-storage warning; batch refused "Pick a vault folder first"; restore puts original back (no confirmation), entry leaves list | device-only | P1 |

## 16. App lock: PDF §9.4, pp68-69

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| AL-01 | p68 | lock off | open protected screen | opens without prompt | unit | P2 |
| AL-02 | p69 | – | source | "Unlock GallerySlim", subtitle, no-screen-lock message | copy | P3 |
| AL-06 | 8.1 p59-60, 9.4 p68 | – | source | "Send these files to your computer?", "Send these files?", no-screen-lock warning, "Not sent" shipped **(rev 2)** | copy | P3 |
| AL-07 | 8.1 p59-60 | device without screen lock or biometrics | Send to computer | in-app "Send these files?" dialog with Send/Cancel; Cancel → "Not sent"; never sends without it **(rev 2)** | device-only | P1 |
| AL-03 | p69 | device | lock on: Home card, All tools banner, Review and clear, Open vault, Compare links; cancel | prompt each time until unlocked once per process; cancel → nothing; enabling without screen lock → message, switch off | device-only | P1 |
| AL-04 | 9.4 p68, 9.5 p70 | device, lock on | tap overnight / goal / trip notification; progress "Go to Ready to clear" | lock prompt shown (no longer a bypass) **(rev 2)** | device-only | P1 |

## 17. Notifications: PDF §9.5, pp69-70

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| NT-01 | p70 | – | source | weekly / monthly / low-space channels and texts, daily-time-limit text shipped **(rev 2 adds the time-limit text)** | copy | P3 |
| NT-02 | p70 | device | – | importances (weekly low, monthly low, low space default); tap targets; no action deletes anything | device-only | P2 |

## 18. Settings: PDF §9.6, p71

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| ST-01 | p71 | – | – | floor Off, 5, 10, 15, 20 GB | unit | P2 |
| ST-02 | p71 | – | – | cooling Off, 1, 2, 3 years | unit | P2 |
| ST-03 | p71 | fresh prefs | – | protect 7 / 30 / 90, default 30 | unit | P1 |
| ST-04 | p71 | fresh prefs | – | Gentle; overnight, lock, vault off; motion folder not set | unit | P1 |
| ST-05 | p71 | – | strings.xml | section titles, options, captions as PDF | resource | P3 |
| ST-06 | p71 | – | SettingsSheet | no language / theme / version / Share log entry | copy | P3 |
| ST-08 | §1 p5, 9.6 p70, 9.7 p71 | – | manifest + data extraction rules | allowBackup=false; cloud-backup and device-transfer exclude every app domain **(rev 2)** | resource | P2 |
| ST-07 | p71 | device | toggle every option | nothing deleted; overnight asks notification permission | device-only | P2 |

## 19. Privacy and How it works: PDF §9.7, pp72-73

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| PR-01 | 9.7 p71 | – | source | six section titles + bodies exactly as quoted in the new PDF (network, delete-for-good, diagnostic log + backup sentences) **(rev 2)** | copy | P2 |
| PR-02 | p72 | – | source | three steps, five questions, "Which photos are left alone?" answer | copy | P3 |

## 20. Hindi: PDF §9.8, pp73-74

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| HI-01 | p73 | – | values-hi | 44 resources, same names as English | resource | P2 |
| HI-02 | p73-74 | – | values-hi | every Hindi string in the PDF table matches | resource | P2 |
| HI-03 | p73 | – | – | app_name "GallerySlim" untranslated | resource | P3 |
| HI-04 | p74 | – | – | placeholders %1$d kept | resource | P2 |
| HI-05 | p73 | – | res + manifest | only values/values-hi; "Share small" label English | resource | P3 |
| HI-06 | p74 | Locale hi-IN | 4.2 GiB, 850 MiB | "4.2 GB", "850 MB" (Western digits, dot) | unit | P2 |
| HI-06b | p74 | Locale hi-IN | month-card sizes | "1.2 GB", "850 MB" | unit | P2 |
| HI-07 | p73 | device in Hindi | open every screen | only Settings sheet + widget picker description in Hindi | device-only | P3 |

## 21. Safety guarantees: PDF §1, pp4-6

| ID | PDF | Preconditions | Inputs | Expected (PDF) | Type | Pri |
|---|---|---|---|---|---|---|
| SF-01 | p4, p42 | items qualifying for every list | readings ID, then Unknown | no list shows any; control with None does | unit | P1 |
| SF-01b | p42 | ID card text that also reads like a receipt | 90-day-old photo | not in Old receipts & tickets | integration | P1 |
| SF-02 | p41 | – | – | favourites never listed (see CL-26) | unit | P1 |
| SF-04 | p47 | – | – | keeper never selected for trash (see DU-18) | unit | P1 |
| SF-03 | p4-5 | device | every removal path in scope (lists, Quick review, Duplicates, contact sheet, Chat media) | nothing moves without Android's own dialog; Cancel changes nothing | device-only | P1 |
