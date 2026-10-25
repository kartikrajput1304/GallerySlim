# Architecture review — flaws, principles, fixes (2026-10-24)

A senior-architect review of `docs/GallerySlim-Design-v2.md` against the code, plus issues found while writing the
feature reference. Each entry: **what was wrong**, **the principle**, **how it was fixed**, and what is left.

---

## C1 — A file's address was treated as its identity (Critical)

**What was wrong.** GallerySlim remembered each original by its MediaStore address (`content://…/media/123`). Later it
trashed or deleted "123". But 123 can stop meaning the same photo:
- you edit the photo in Samsung Gallery after shrinking (same address, different picture) → "Clear" would trash
  your edit and keep the pre-edit copy;
- app data restored onto a new phone, or the media library rebuilt → 123 is now a *different* photo.

**Principle — a locator is not an identity; act only on the version you verified.** Like an ETag in web APIs or
compare-and-swap in databases: record a version stamp when you check something, and before a destructive step,
confirm the stamp still matches. If not, stop.

**Fix.**
- Every shrink record now stores a fingerprint of the original (size, file modified time, library version) and the
  copy's size (database v3, additive migration).
- One tested gate (`safety/SafetyGates`) re-checks the fingerprint, with one batched query, before every clear,
  undo, "Free this space now" and "delete for good". A mismatch marks the item **STALE**: it is never cleared;
  the user can only remove the app's own smaller copy or dismiss it.
- If the media library's version changes, items are re-checked; those that still match are re-stamped, the rest go
  STALE. (Tuned after review: a favourite toggle or a Google Play system update no longer raises false alarms.)
- App data is excluded from cloud backup and phone-to-phone transfer, so old records can't land on a new phone.

## H1 — Two batches could work on the same photos (High)

**What was wrong.** A manual batch and the overnight or trip job could run at once. The only guard lived inside one
screen's ViewModel, and "already queued?" was a read-then-write check, so both could queue the same original.

**Principle — enforce invariants where they can't be bypassed.** A rule that lives in one caller protects only that
caller. Races need a lock or a database constraint, not "check, then act".

**Fix.** `BatchLock` (one process-wide lock) wraps manual, overnight and trip batches; the busy one says so. The
database has a unique index "one queued record per original"; a second insert is refused and its copy is discarded.

**Left (L):** a durable job queue (batches as database rows run by WorkManager) so a batch also resumes after the app
is killed. Today a killed batch keeps everything it finished; the rest is simply not done.

## H2 — Results of Android's dialogs were kept only in memory (High)

**What was wrong.** When you confirm "Move to trash", Android's dialog covers the app; on low-memory phones Android
may close GallerySlim meanwhile. The code that recorded "these were trashed" ran from screen memory, so after a
restart it saw nothing: no Undo, items missing from "Send the trash to computer", records stuck as "Ready to clear".

**Principle — write-ahead intent (outbox) + reconciliation.** Before handing control to another process, durably
write what you're about to do. Afterwards, complete it idempotently. On start, a reconciler compares leftover
intents with the real source of truth (the media library) and finishes or abandons them.

**Fix.** An `operations` table. Every trash/untrash/delete writes an operation *before* the dialog (`prepare`), and
`complete`/`cancel` it by id afterwards; the id survives process death. `Operations.reconcile` runs on app start/resume
and in the daily trash worker. All bookkeeping (history, monthly tally, record status) happens there, in one
transaction, once — screens no longer write it.

## H3 — The most important rules were the least testable (High)

**What was wrong.** The delete gates lived inside a ViewModel and a 900-line navigation file, calling Android directly;
no test could reach them.

**Principle — functional core, imperative shell.** Keep decisions as pure functions over plain data; keep the code
that talks to Android thin. Then the rules that protect user data get exhaustive tests.

**Fix.** `MediaStoreGateway` (interface + Android implementation) and pure `SafetyGates` / `OpDecisions`, covered by
~65 tests (edited original, replaced id, restored database, missing/trashed copy, legacy rows, D1/D2 rules,
idempotency, process death). The D2 rule moved out of the screen code into the gates.

## M1 — It would slow down badly at 50,000 photos (Medium)

**What was wrong.** Two system queries per cleared photo on every dashboard refresh; per-item queries on the main
thread (freezes); per-photo caches in settings files (the whole file rewritten each time, never cleaned); a vault
that listed its whole folder for every photo (quadratic); results lists copied on every item.

**Principle — batch your I/O, bound your state, keep work off the main thread.**

**Fix.** One batched probe (500 ids per query) off the main thread; a terminal FREED status so freed records stop
being checked; caches moved into a small indexed database with pruning; a vault name index built once per batch;
linear result accumulation.

## M2 — Duplicates and Chat media skipped the ID-document shield (Medium)

**What was wrong.** Clean up lists only show photos already checked for ID documents. Duplicates pre-selected any
non-keeper (only *known* IDs excluded), and Chat media had no shield — an unchecked Aadhaar photo could be swiped away.

**Principle — one policy, fail closed.** The same safety rule must apply on every path; when unsure, protect.

**Fix.** One eligibility rule for lanes, duplicates, Quick review and Chat media: only checked, non-ID, non-protected
photos outside never-touch albums can be pre-selected; others are shown "held" with the reason and can still be ticked
by hand. Contact sheets are offered only when nothing in the burst is held.

## M3 — Two sources of truth for sharing state (Medium)

**What was wrong.** Sharing progress lived in memory and was updated from several threads without coordination, so a
"verified" mark could be lost even though the database had it.

**Principle — single source of truth; atomic updates.** **Fix.** All updates use atomic `update {}`; verified state is
saved to the history database the moment it's proven, and old verifications are labels only (deleting for good needs
a fresh check in the current run).

## M4 — Security gaps (Medium)

- **DNS rebinding:** a malicious web page could re-point its own domain at the phone and drive the sharing server
  from your browser. **Principle:** check that requests are addressed to you. **Fix:** the server refuses any `Host`
  other than its own IP or `galleryslim.local` (tested).
- **Backups vs. privacy:** Android's default backup would have uploaded the search index (including OCR text) to
  Google Drive. **Fix:** backups and transfers excluded; ID-document text is never stored in the search index.

## M5 — Android platform rules (Medium)

- Transcoding now declares Android 15's **media-processing** foreground type (data-sync below 15).
- Trip mode runs in two stages Android accepts (an evening kick-off, then an expedited run) instead of a background
  foreground request Android usually refuses.
- Vault copies are read back and compared by SHA-256, catching fake-capacity SD cards.

## Found while writing the feature reference

- Ready to clear opened from a notification or the progress screen skipped the app lock → now locked.
- Phones without a screen lock sent files with no confirmation → now an in-app "Send these files?" dialog.
- Starting sharing without Wi-Fi could crash (a foreground service stopped before going foreground) → fixed; the
  error stays visible.
- The privacy page and policy described old behaviour → corrected.

## Low

Room schemas are exported; the undo window counts from when the original was cleared, not when it was shrunk; files
moved to a computer and deleted for good no longer show an Undo that can't work; the monthly report counts freed
shrinks.

## Not done (with cost)

| Item | Why it matters | Size |
|---|---|---|
| Durable job queue for batches | resume a killed batch automatically | L (≈1 week) |
| One database + append-only event log | removes drift between three stores; every view becomes a query | L (2–4 weeks) |
| Split the navigation file per feature | smaller, testable screens | M |

## Needs a person (company policy)

Deletion logic (gates, operations, D1/D2), the database migration, protocol v2 crypto, and the Receiver page's access
checks need human review before release. Several behaviours must be checked on a real phone (trash/restore effects on
file times, MediaStore probe with trashed rows) — listed in the safety agent's notes.
