# Features round 2 — plan (2026-10-21)

Rule for this round: at most ONE helper agent at a time. Everything on-device. Only new library: ML Kit
image labeling (bundled, on-device), approved by the developer's "make plan for all and implement" in reply
to the search-feature sign-off request.

| # | Feature | How | Owner |
|---|---------|-----|-------|
| 1 | Move to computer, then free space | Share server records each file whose bytes were fully sent (whole-file response or completed ZIP entry with the ZIP finished). Send screen then offers "Move N sent originals to trash" (createTrashRequest) after the user confirms they checked the computer. Only fully-sent files are eligible. | me |
| 2 | Greetings & forwards lane | DocumentClassifier gains Kind.GREETING from OCR text (English + romanised Hindi greetings, festival wishes, quote cards). New Clean up lane "Greetings & forwards" for chat-app images. ID shield still wins. Classification cache key bumped so photos get re-read once. | me |
| 3 | Home-screen widget with forecast | RemoteViews AppWidgetProvider (no new library). Daily free-space samples in prefs (app open, widget update, daily worker). Pure least-squares forecast → "Full in about N days". Button opens the app. | me |
| 4 | "Share small" in the share menu | ShareSmallActivity handles SEND / SEND_MULTIPLE for images and videos, makes smaller copies in cache/share (photos: 2048 px JPEG q82 with orientation; videos: 720p H.264 via Media3) and re-shares through FileProvider. Originals untouched. | me |
| 5 | Best shot from bursts | Burst clusters = same album, consecutive shots ≤ 1.5 s apart, ≥ 3 shots. Keeper picked by the existing sharpness + open-eyes scorer. Shown in Duplicates as "Burst" groups. | me |
| 6 | Big videos + light copy + trim | "Big videos" screen sorted by size; "Light copy (720p)" shrink and "Trim" (keep a chosen range) producing a new file; the original goes to Ready to clear like any shrink. | agent |
| 7 | Rules for old photos per album | Album rules screen over AlbumPolicy: Default / Light copy for photos older than N years / Never touch. Overnight shrink and manual shrink honour it. | agent |
| 8 | Monthly report + shareable card | Report screen from confirmed shrinks + Clean up deletions this month; card rendered to PNG and shared; monthly notification. | me |
| 9 | Quick review across everything | One swipe queue over all Clean up lanes + duplicate extras + bursts, live "space you'll free" counter, one trash request at the end. | me |
| 10 | Search photos by describing them | Background indexer: ML Kit image labels + OCR text per photo into a small Room DB; search screen with token match, ranked. | me |
| 11 | Old downloads lane | Photos/videos in the Download folder older than 30 days (MediaStore; other file types are not visible to a photo app without broad storage permission, which Play rarely allows). | me |

Safety: nothing deletes without the system dialog; ID shield applies to every new lane; delivery-tracked deletes use
the trash (30-day restore), never createDeleteRequest.

## Status (2026-10-21 14:33)
All 11 implemented; build passes; 451 unit tests pass. Not yet tested on the phone.
Notes: the "Light copy" preset now also makes 720p videos; "Never touch" albums are skipped in manual shrinks too;
trimmed videos have no Compare (frames don't line up); greeting detection reads Latin script only.
