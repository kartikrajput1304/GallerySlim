# GallerySlim Receiver — plan v2 (2026-10-24, after gap review)

Goal: photos sent from the phone land in **one folder the user chose**, sorted by month, on Windows, macOS
and Linux, **without installing Python or anything else**. A file counts as safe on the computer only after
its bytes on the laptop's disk are proven identical to the phone's file.

Status (2026-10-24): **built**. Phone side in `app/.../share/` (Gs2*, FileHasher), receiver in `receiver/`, protocol in
`docs/protocol-gs2.md`. Gap review (19) and security review findings are fixed. Not yet tried on Windows/macOS or with the real phone.

---

## 1. What the user sees

**First time on a laptop**
1. Get the receiver for their system (see § 8) and open it.
2. It opens its own page in the laptop's browser (local only, `127.0.0.1`). On Linux without a desktop it runs in
   the terminal instead and prints the QR there.
3. "Where should photos go?" → **Choose folder** opens the system's own folder dialog. The suggested default is
   a folder that does **not** sync to the cloud. If the chosen folder syncs (OneDrive, iCloud, Google Drive), the
   page says so plainly.

**Every time**
1. Phone: pick photos → **Send to computer**.
2. Laptop: open the receiver; it shows a QR code and a short code (e.g. `K7-42`).
3. Phone: scan, check the short code matches, confirm with fingerprint.
4. Laptop shows progress and keeps itself awake: "142 of 300 · 1.2 GB of 2.9 GB · saving to D:\Photos".
5. Each file is read back from the laptop's disk and checked. The phone shows **"Verified on your Windows PC"**.
   Only verified files are ever offered for freeing space.
6. If the Wi-Fi drops or a laptop sleeps, opening the receiver again and scanning continues where it stopped.

The browser page (today's flow) stays as the no-download option, with the limits in § 6.

## 2. Architecture

```
 laptop                                   phone (GallerySlim)
 ┌──────────────────────────┐   Wi-Fi    ┌──────────────────────────────┐
 │ receiver (one Go program)│ ─────────▶ │ LanHttpServer (port 8765–8769)│
 │  • page on 127.0.0.1     │  requests  │  • /api/hello                 │
 │  • QR made locally       │ ◀───────── │  • /api/r/*  receiver API     │
 │  • writes + verifies     │  encrypted │  • existing browser routes    │
 └──────────────────────────┘            └──────────────────────────────┘
```

**Pull model**: the phone stays the server; the receiver only connects out. No open port on the laptop, and the
phone keeps its limits (same-subnet peers only, connection cap, stop on Wi-Fi loss, auto-stop).

## 3. Pairing and keys (needs a person's security review before release)

1. Receiver makes a random 32-byte **K** locally and draws the QR `gs2:<sid>:<K>` itself. K never crosses the network;
   the phone learns it only through its camera. (Go's standard library has no QR encoder: a small byte-mode
   encoder is written for the receiver and checked in tests by decoding its output with the phone's own ZXing.)
2. `GET /api/hello` → app name, protocol version, phone name, and a **short code** also shown on the phone, so
   two family phones sharing at once can't be confused. Text from the network is length-capped, stripped of
   control/bidi characters and HTML-escaped before display.
3. `POST /api/r/session` → `sid` and a 32-byte phone nonce **N** (session pinned to the laptop's address).
4. Phone scans, owner checks the short code and confirms with fingerprint → approved.
5. **Session keys** = HKDF-SHA256(K, salt = sid‖N), with separate labels for request MACs and for encryption
   (as built: one encryption key for both the list and files, kept apart by the resource name in every frame's AAD). A remembered laptop (later phase) reuses K safely because sid‖N changes every session.
   HKDF is written per RFC 5869 on both sides (Android has no HKDF class; Go 1.24+ has `crypto/hkdf`).
6. **Requests** carry a per-session counter (no clocks: laptop clocks are often wrong) and
   `HMAC(method ‖ raw path+query ‖ Range header ‖ sid ‖ counter ‖ SHA-256(body))`. The phone rejects counters it
   has seen and anything with a bad MAC.
7. **Responses** (list and file bodies) are AES-256-GCM frames of 1 MiB. Nonce = 8 random bytes chosen per
   response ‖ 4-byte frame counter. AAD = sid ‖ resource ‖ absolute frame offset ‖ last-frame flag, so reordering,
   truncation and splicing all fail. (As built, the file size is not in the AAD; the whole-file SHA-256 and size in
   `ack` catch any length trick.) A resumed Range response gets a fresh random prefix.
8. **Answers** to authenticated requests are signed (protocol § 4a), so nobody can flip "verified".

## 4. Verified, honestly

Gap found: hashing "what was streamed" proves nothing for resumed, skipped or wrongly-sized files.

- The phone hashes **each whole file itself** (reading to the end, not to a listed size), caches the hash by
  MediaStore id + size + date modified, and sends the true length. A length that differs from the list is an error.
- The receiver writes to a `.part` file, renames it into place, **re-reads it from disk**, hashes it, and only then
  sends `ack {file, sha256}`. The phone marks it verified only when the hashes match.
- Files the receiver already has (its manifest is just a hint) are re-hashed on disk and acked through a
  **verify-only** request, without sending the file again.
- Verified state is saved in `history.db` the moment the ack arrives, so it survives the sharing service stopping,
  the app closing or a new session.

## 5. Phone changes

- `LanHttpServer`: POST for `/api/r/*` (bodies capped at 16 KiB); keep the raw request target for MACs; framing;
  try ports 8765–8769 in order; **close sockets that make no progress for 30 s** (a dead laptop today blocks a
  connection for ~15 min, and a few of those fill all 8 slots); loop `skip()` until the full offset is skipped.
- `ShareSessions`: v2 sessions (N, counters, keys); **refresh last-seen on every chunk and while any stream is
  open** (today a session expires 10 minutes after a request starts, which breaks large videos).
- `ShareService`: partial wake lock while a stream is active; keep the screen on while the Send screen is open;
  test the Wi-Fi lock on the S24 (it may do nothing on Android 14+). **Fix auto-stop**: today any bytes ever sent
  keep sharing alive until Android's 6-hour limit; it should stop 30 minutes after the last transfer.
- `QrScanScreen`: accept `gs2:`; show the short code before the fingerprint prompt.
- Queue: pending originals carry their real date taken (looked up in MediaStore) so they don't all land in `undated`.
- History: SHA-256 per moved file (also needed to bring photos back exactly).

## 6. The "delete for good" rule (design § 6 addendum update)

Permanent delete from the trash is offered **only for receiver-verified files**. Browser-only sends ("fully sent",
not verified) can move originals to the trash but not delete them for good. The design doc addendum is updated
to this and sent for human review together with § 3.

## 7. Receiver (Go 1.24+, standard library only)

- **Finding the phone**: the receiver sends its own one-shot mDNS query (Go's built-in resolver doesn't do mDNS),
  tries ports 8765–8769, and falls back to the address shown on the phone. If several phones answer, it lists them
  with their short codes.
- **Local page**: `127.0.0.1:<random port>/<random token>/`; checks `Host` (blocks DNS rebinding), rejects other
  `Origin`s, no CORS.
- **Folder dialog**: Windows `IFileDialog`/`SHBrowseForFolderW` through Go's `syscall` (no PowerShell: garbles
  Hindi names, opens behind windows, looks suspicious to antivirus); macOS `osascript choose folder`; Linux
  `zenity`/`kdialog` when present, else a text box and `--folder`.
- **Default folder**: the real Pictures location from `SHGetKnownFolderPath` (Windows) / standard paths; if it's
  cloud-synced, suggest `<home>\GallerySlim Photos` instead. Months go **directly** inside the chosen folder
  (no `GallerySlim\GallerySlim` nesting).
- **Never overwrite**: one lock per folder; reserve the final name with create-exclusive; compare names
  case-insensitively on Windows/macOS; `IMG_1 (2).jpg` on clash; fsync file and folder; retry renames that
  antivirus/OneDrive briefly lock.
- **Names**: clean for every OS (reserved names like `CON`, `<>:"/\|?*`, trailing dots/spaces, 255-byte limit);
  warn when a path passes 260 characters (Explorer can't open those).
- **Resume**: `.part` files named by file id + size + date, so a new QR session continues them; at most 3 streams;
  back off on "busy".
- **Stay awake**: Windows `SetThreadExecutionState`, macOS `caffeinate -w`, Linux `systemd-inhibit` while receiving.
- Free-space check before starting; config in the user's config folder; log file without secrets.

## 8. Build and distribution

- Install **Go ≥ 1.24** on this machine (the installed Go 1.18 is out of support and lacks `crypto/hkdf`).
  Build for Windows (amd64/arm64), macOS (arm64/amd64), Linux (amd64/arm64). Minimum OS: Windows 10, macOS 11.
- **Windows: code signing is a blocker, not polish.** Windows 11 Smart App Control blocks unsigned programs with no
  "Run anyway", and an unsigned networking program is likely to be flagged by antivirus.
- **macOS**: ship a proper `.app`, signed and notarised (Apple developer account). macOS 15 no longer offers
  right-click → Open for unsigned apps. Expect "Local Network" and folder-access prompts; handle "denied" clearly.
- **Linux**: `.tar.gz` that keeps the run permission; terminal mode for machines without a desktop.
- Download location: a company decision (the phone serving it would add ~7 MB per system to an app that is already
  too big for full phones).

## 9. Testing

- Kotlin and Go unit tests; shared test vectors for HKDF, MACs and GCM frames; QR encoder output decoded by ZXing.
- Fault tests: Wi-Fi drop mid-file, laptop sleep, phone screen off, two phones, same file names from two phones,
  case-only name clashes, stale manifest, wrong listed size, replayed request, tampered frame.
- End-to-end on this Linux laptop with the S24 (phone awake, owner scans and confirms).
- Windows and macOS: need real machines for the final checks; builds can be made here but not run.

## 10. Phases

0. **Fix today's bugs now** (independent of the receiver): auto-stop never firing, sessions expiring during long
   files, stuck sockets filling all slots, partial `skip()`, undated pending originals.
1. Phone: protocol v2, whole-file hashing, verified state in history.db, wake/screen handling, short code.
2. Receiver core: discovery, QR encoder, pairing, download, verify, never-overwrite saving, resume, stay-awake.
3. Receiver page, folder dialogs, cloud-folder detection.
4. Signed builds for all systems, end-to-end on Linux, then Windows and macOS checks.
5. Later: remember a paired laptop (fingerprint only, no QR), start with the laptop, bring photos back.

## 11. Gap review: findings and fixes

| # | Sev. | Finding | Fixed in |
|---|---|---|---|
| 1 | High | "Verified" could cover a fragment, a skipped file, or a truncated file | § 4 |
| 2 | High | Stale manifest could lead to a permanent delete of a file no longer on the laptop | § 4 |
| 3 | High | Rename could overwrite (races, two phones, case-insensitive names) | § 7 |
| 4 | High | GCM nonce reuse across sessions and resumes; last-frame flag not authenticated | § 3.5, § 3.7 |
| 5 | High | Session expires during long files (today's app too) | § 5, phase 0 |
| 6 | Med | Phone/laptop sleep mid-transfer; Wi-Fi lock may be a no-op on Android 14+ | § 5, § 7 |
| 7 | Med | Dead connections fill all 8 slots (today's app too) | § 5, phase 0 |
| 8 | Med | Wi-Fi drop wipes verified state; can't resume | § 4, § 7 |
| 9 | Med | Clock-based replay check fails on wrong PC clocks; MAC missed body/Range/sid | § 3.6 |
| 10 | Med | Go has no QR encoder | § 3.1 |
| 11 | Med | Go doesn't resolve `.local`; fixed name breaks with two phones; random port fallback | § 3.2, § 5, § 7 |
| 12 | Med | Unsigned binaries blocked (Windows SAC, macOS 15), Linux run permission, Go 1.18 too old | § 8 |
| 13 | Med | PowerShell folder dialog garbles Hindi names and looks suspicious | § 7 |
| 14 | Med | Pictures is usually OneDrive on Windows 11; online-only files make "verified" untrue | § 1, § 7 |
| 15 | Low | Auto-stop never fires once anything was sent (today's app) | § 5, phase 0 |
| 16 | Low | `GallerySlim\GallerySlim` nesting; pending originals all `undated` | § 5, § 7 |
| 17 | Low | Unauthenticated names could inject script into the local page | § 3.2 |
| 18 | Low | `skip()` may skip less than asked (today's app) | § 5, phase 0 |
| 19 | Low | Browser-only sends lose "delete for good"; design doc needs the update | § 6 |

Not yet verified (to test, not assume): Windows `.local` resolution on "Public" networks and whether the receiver's
mDNS query triggers a firewall prompt; macOS `.local` without cgo; macOS folder-access prompts for Pictures;
the Wi-Fi lock and screen-off behaviour on the S24.

## 12. Approvals and owners

| Item | Owner |
|---|---|
| Install Go ≥ 1.24 on this machine | Kartik |
| Windows code signing, macOS signing + notarisation | RxLogix IT / legal |
| Where the receiver is downloaded from | RxLogix / Kartik |
| Security review of § 3, § 4 and § 6 | A person, before release |
