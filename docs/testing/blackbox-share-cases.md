# Black-box test cases: Send to computer and GallerySlim Receiver

Source of truth: `docs/features/GallerySlim-Features.pdf` (Feature Reference, App 1.0.0 · Receiver 0.1.0, 2026-10-24; updated 92-page edition — cases revised against it, page numbers unchanged).
Expected results below come only from that PDF; page numbers are the PDF's own ("Page N of 93").
Scope: 8.1–8.5 (phone side of sending), 10.1–10.9 (Receiver program), plus the related rules in 1 (pages 4–6),
2.2 (units, page 7), Appendix A.5/A.6 (pages 87–88) and Appendix B (pages 89–90).

Type: **unit** (one function or object), **integration** (real phone server on 127.0.0.1, real Receiver binary, or the
Receiver engine against a fake phone), **static** (inspects the page the server really serves; behaviour needs a
browser), **device-only** (needs an Android phone, a camera, Android dialogs, a second OS, or a real Wi-Fi LAN).
Priority: P1 = safety/security or data loss, P2 = main behaviour, P3 = wording, cosmetics, rare paths.

Automated in: `K:` Kotlin, `app/src/test/java/com/galleryslim/blackbox/share/<Class>`; `G:` Go,
`receiver/blackbox/<file>`. "—" = not automated (see results file).

Conventions used in steps: *phone* = `LanHttpServer` with fake storage; *Receiver* = the real binary;
*engine* = the Receiver's pairing/receiving code driven in-process against a fake phone; *page* = the Receiver's
local web page (`http://127.0.0.1:<port>/<token>/`).

---

## 8.1 Send to computer: the phone screen and the sharing service (pages 59–60)

| ID | PDF | Preconditions | Inputs / steps | Expected (from the PDF) | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| SND-01 | 8.1 Where, p59; 4.3 | Category screen with items | Select 0, then 1+ items (also on Motion photos) | “Send N to computer” appears only with ≥ 1 selected, on every category; queues the selection and opens Send to computer | device-only | P2 | — |
| SND-02 | 8.1 Where p59; 5.4 p33 | Items in Ready to clear | Tap header link “Send to laptop” | Originals of all listed records queued with real dates; sharing starts; Send screen opens; records stay in Ready to clear | device-only | P2 | — |
| SND-03 | 8.1 How p59 | Sharing started | Open Send screen, press back | Subtitle “N items · size”; screen stays on while open; leaving does not stop sharing | device-only | P2 | — |
| SND-04 | 8.1 How p59 | Sharing started | Look at the Receiver hint | “Using the GallerySlim Receiver? … This phone's code is K7-42.” with this run's short code | device-only | P3 | — |
| SND-05 | 8.1 Step 1 p59 | Sharing on Wi-Fi | Read/tap step 1 link; “Send the link to my computer…” | `galleryslim.local:port` when the name responder runs, else `IP:port`; tap copies full `http://` address; fallback line “If that name doesn't open: ip:port”; caption; share sheet “Send the link”; before the link: error text or “Starting…” | device-only | P2 | — |
| SND-06 | 8.1 Step 2 p59 | — | Empty queue / no link / both present | “Scan the computer's code” enabled only when the link exists and the queue is not empty | device-only | P2 | — |
| SND-07 | 8.1 Step 3 p59 | Receiver session waiting, progress > 0 | Approve a new code | Progress resets on each new approval | unit (ShareHub) + device for the bar | P2 | K: PhoneSessionsTest |
| SND-08 | 8.1 Bottom p59 | Sharing on | Tap red “Stop sharing” | Service stops and the screen closes | device-only | P1 | — |
| SND-09 | 8.1 Scan screen p59 | Codes of several kinds | Scan `https://…`, `GS2:…`, `gs3:…`, `gs1:`/`gs2:` codes | Only codes starting with `gs1:` or `gs2:` are accepted; others ignored | unit + device (camera) | P1 | K: PhoneSessionsTest |
| SND-10 | 8.1 Confirmation p59 | Phone with screen lock | Scan a valid code; also when app is unlocked; cancel | Android biometric/PIN prompt “Send these files to your computer?” always asks; cancel/error → snackbar “Not sent” | device-only | P1 | — |
| SND-11 | 8.1 Rules p60 | Phone on mobile data / VPN / Wi-Fi | Start sharing | Wi-Fi only (not VPN), IPv4; otherwise “Connect the phone to Wi-Fi first” | device-only | P1 | — |
| SND-12 | 8.1 Rules p60 | 8765–8766 occupied | Start server with defaults | First free of 8765–8769 (here 8767) | integration | P1 | K: PhoneServerLimitsTest |
| SND-13 | 8.1 Rules p60 | 8765–8769 all occupied | Start server | Random port (outside 8765–8769) | integration | P2 | K: PhoneServerLimitsTest |
| SND-14 | 8.1 Rules p60 | Server bound to one address | Connect to another local address | Bound to the phone's Wi-Fi address only (not reachable on others) | integration | P1 | K: PhoneServerLimitsTest |
| SND-15 | 8.1 Rules p60 | Subnet rule active | Request from inside and outside the subnet | Only computers in the phone's subnet; others disconnected without a reply | integration | P1 | K: PhoneServerLimitsTest |
| SND-16 | 8.1 Rules p60 | Subnet unknown | Classify 10/8, 172.16/12, 192.168/16 vs public, CGNAT, link-local | Private addresses allowed, others not | unit | P2 | K: PhoneServerLimitsTest |
| SND-17 | 8.1 Rules p60 | 8 open connections | Open a 9th | At most 8 at once; beyond: “503 Busy” | integration | P1 | K: PhoneServerLimitsTest |
| SND-18 | 8.1 Rules p60 | 5 open connections from one address | 6th from same address; one from another address | 5 per address; 6th gets “503 Busy”; other address served | integration | P1 | K: PhoneServerLimitsTest |
| SND-19 | 8.1 Rules p60 | — | Send request line + partial headers, then nothing | Headers within 10 s, else connection closed (no answer) | integration | P2 | K: PhoneServerLimitsTest |
| SND-20 | 8.1 Rules p60; A.5 p87 | — | 64 vs 65 header lines | 64 accepted; 65 refused (connection closed without answer) | integration | P2 | K: PhoneServerLimitsTest |
| SND-21 | 8.1 Rules p60; A.5 | — | ~30 KB vs ~34 KB of headers | ≤ 32 KB accepted; more refused | integration | P2 | K: PhoneServerLimitsTest |
| SND-22 | 8.1 Rules p60; A.5 | — | Header line of 8191 vs 8192 characters | Lines of at most 8191 characters (plus the line ending) accepted; 8192 refused | integration | P2 | K: PhoneServerLimitsTest |
| SND-23 | 8.1 Rules p60; A.5 p87 | — | POST body 16 384 vs 16 385 bytes | ≤ 16 KB accepted; larger: connection closed without an answer | integration | P2 | K: PhoneServerLimitsTest |
| SND-24 | 8.1 Rules p60 | Approved Receiver session, 48 MB file | Request the file, read nothing for 45 s | 30 s without progress closes the connection | integration | P2 | K: PhoneServerLimitsTest |
| SND-25 | 8.1 Rules p60 | Approved browser session | Idle 9 min after a chunk; idle 10 min | Expire after 10 min idle; each chunk keeps them alive | integration (fake clock) | P1 | K: PhoneSessionsTest |
| SND-26 | 8.1 Rules p60 | — | Load the page 33 times | At most 32 unapproved sessions (oldest dropped, its code no longer approves) | integration | P2 | K: PhoneSessionsTest |
| SND-27 | 8.1 Rules p60 | Approved browser session | Status/ZIP/file from another IP | Each session pinned to the computer's IP; other computers get nothing | integration | P1 | K: PhoneSessionsTest |
| SND-28 | 8.1 Rules p60; Glossary p93 | — | Generate 4 000 codes | 2 letters A–Z without I and O, “-”, number 10–99; random (new each start) | unit (+ device: new per start) | P2 | K: PhoneSessionsTest |
| SND-29 | 8.1 Rules p60; A.5 p87 | Sharing on | Wi-Fi off; swipe app away; Android 15 FGS limit; 30 min with nothing approved | Sharing stops; ~30 min idle checked every 60 s; ~40 min after last transfer | device-only | P1 | — |
| SND-30 | 8.1 Rules p60 | Transfer in progress | Stop sharing | Revokes all sessions, cuts transfers, empties the queue | integration (cut + refuse) + device (queue, revoke via service) | P1 | K: PhoneServerLimitsTest (partial) |
| SND-31 | 8.1 Rules p60 | Approved session | Stream a file; list only | Wake lock + Wi-Fi lock only while a file streams | integration (streaming signal) + device (locks) | P3 | K: PhoneServerLimitsTest |
| SND-32 | 8.1 What the user sees p60 | Sharing on | Pull down notifications; tap action | “Ready to send to your computer” / “Open the link … stops by itself after 30 min”, action “Stop sharing”; tap opens app | device-only | P3 | — |
| SND-33 | 8.1 p60 | Notifications blocked | Start sharing | “Couldn't start sharing (notification blocked)” | device-only | P3 | — |
| SND-34 | 8.1 Confirmation p59 | No screen lock, no biometrics | Scan a valid code | In-app dialog “Send these files?” with the no-screen-lock warning, “Send”/“Cancel”; never sent without a confirmation | device-only | P2 | — |
| SND-36 | 8.1 Rules p60 (Host check) | Server running | Hosts: own IP / galleryslim.local with and without port; foreign names, other IPs, localhost | Allowed hosts served; every other Host answered “421 Misdirected Request” (no page session made) | integration | P1 | K: PhoneServerLimitsTest |
| SND-35 | 8.1 Tester note p60 | No Wi-Fi or VPN | Open Send screen; start & immediately stop | Step 1 shows “Connect the phone to Wi-Fi first”; brief notification “Not sharing: no Wi-Fi” | device-only | P2 | — |

## 8.2 Send to computer via a browser (gs1) (pages 61–62)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| GS1-01 | 8.2 step 1 p61 | Sharing on | Load the page twice | Each load creates its own session and QR (`gs1:` code of that session) | integration | P1 | K: PhoneSessionsTest |
| GS1-02 | 8.2 step 1 p61 | — | Load the page | Tab “GallerySlim · Send to this computer”, heading, “From *model* · only this screen can receive”, QR, 3 steps, “Waiting for the phone…”; QR decodes to this page's code | integration | P2 | K: BrowserSendTest |
| GS1-03 | 8.2 steps 2–3 p61 | Two pages open | Approve with wrong secret, unknown page, expired page, right code | Only the scanned page's valid code approves; wrong/stale → “That code isn't from this phone…” (snackbar: device) | integration + device | P1 | K: PhoneSessionsTest |
| GS1-04 | 8.2 step 4 p61 | Page waiting | Poll status before/after approval | waiting → approved with files (name, size, month folder / undated) | integration | P1 | K: PhoneSessionsTest |
| GS1-05 | 8.2 step 4 p61 | — | Inspect served page | Checks every 1.5 s | static | P3 | K: BrowserSendTest |
| GS1-06 | 8.2 step 4 p61 | — | Inspect served page | “Ready to save”, “n files · size”, three choices with hints | static | P3 | K: BrowserSendTest |
| GS1-07 | 8.2 step 4 p61; A.5 | — | Inspect served page | “Save into a folder I choose” only with folder access in a secure context (normally hidden) | static | P3 | K: BrowserSendTest |
| GS1-08 | 8.2 step 5 p61 | Page waiting 10+ min | Poll | “This link has expired” / “Reload the page on the laptop and scan the new code.” | integration + static | P2 | K: PhoneSessionsTest, BrowserSendTest |
| GS1-09 | 8.2 Rules p61 | Approved | Download ZIP | Named `GallerySlim yyyy-MM-dd HH.mm.zip` (phone's time) | integration | P2 | K: BrowserSendTest |
| GS1-10 | 8.2 Rules p61 | Files with/without date taken | Download ZIP | Entries `GallerySlim/yyyy-MM/name` by date taken, or `GallerySlim/undated/name` | integration | P1 | K: BrowserSendTest |
| GS1-11 | 8.2 Rules p61 | Same names in one month | Download ZIP | Duplicates become “name (2).ext” (then (3)) | integration | P1 | K: BrowserSendTest |
| GS1-12 | 8.2 Rules p61 | — | Download ZIP | Entry time = date taken | integration | P2 | K: BrowserSendTest |
| GS1-13 | 8.2 Rules p61 | Names with `/`, `\`, control chars, `../../` | Download ZIP | Slashes and control characters become “_”; nothing escapes `GallerySlim/<month>/` | integration | P1 | K: BrowserSendTest |
| GS1-14 | 8.2 Rules p61 | — | Download ZIP | No compression: DEFLATE at level 0 | integration | P3 | K: BrowserSendTest |
| GS1-15 | 8.2; 8.1 safety p60 | — | ZIP/file before approval, wrong secret, unknown file, other computer | Refused; only the approved computer with the right secret gets files | integration | P1 | K: PhoneSessionsTest |
| GS1-16 | 8.2 Rules p61; B#20 | — | Inspect page (and device) | “Preparing your file…”, then after a 1.5 s timer “Saved as one file ✓” and “Check your Downloads folder” | static | P3 | K: BrowserSendTest |
| GS1-17 | 8.2 Rules p61 | — | Inspect page; one-by-one on a real browser | Loose original names, no month folders, 2 at a time, “✓ saved”/“failed”, “All saved ✓” or “n of m saved”, “Saved to your Downloads folder.” | static + device | P3 | K: BrowserSendTest |
| GS1-18 | 8.2 Rules p61 | Secure-context browser | Folder picker | `chosen/GallerySlim/yyyy-MM` or `undated/`, 4 at a time, “ (2)” on clashes, end text | static + device | P3 | K: BrowserSendTest |
| GS1-19 | 2.2 p7 | — | Inspect page | Browser page sizes in binary units (GB one decimal, MB/KB whole) | static | P3 | K: BrowserSendTest |
| GS1-20 | 8.2 Rules p61; Glossary | Real gallery files | Single file from byte 0; range download; full ZIP; ZIP cut off | Only a whole single file from byte 0, or a ZIP entry read to the end with the whole ZIP finished while sharing ran, counts as “fully sent”; a cut-off ZIP marks nothing | device-only | P1 | — |
| GS1-21 | 8.2 Rules p61 | Approved | One-by-one download of a Hindi-named file | Saved under its original name | integration | P2 | K: BrowserSendTest |
| GS1-22 | 8.2 What the user sees p61–62 | Some files fully sent | Step 4 → “Move N sent files to trash” → dialog → Android trash dialog OK / Cancel; files gone | Texts as in PDF; OK → “Moved N sent files to the trash” (recorded in Moved to computer, Your month); Cancel → nothing; none left → “These files are no longer in the gallery” | device-only | P1 | — |
| GS1-23 | 8.2 Safety p62; 1 p5 | Browser sends only | Look for delete-for-good | Browser sends are never “verified”; may be trashed (30-day restore), never deleted for good | device-only | P1 | — |

## 8.3 Send to computer via the GallerySlim Receiver (gs2, phone side) (pages 62–63)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| GS2-01 | 8.3 step 1 p62; 10.4 p79 | Sharing on | GET hello | Answers as GallerySlim protocol 2 with the phone's name and short code | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-02 | 8.3 step 1 p62 | — | Receiver asks for a session | The session carries computer name, system, folder name | integration (+ e2e) | P2 | K: ReceiverProtocolPhoneTest, ReceiverPageEndToEndTest; G: engine_test RT02 |
| GS2-03 | 8.3 step 2 p62 | — | Approve a code with unknown session / bad secret / gs1 prefix | No approval (phone: “That code has expired. Restart the receiver…”, no prompt — device) | integration + device | P1 | K: ReceiverProtocolPhoneTest |
| GS2-04 | 8.3 Rules p62 | — | Approve a code twice; approve after 10 min | Code valid only while its session waits | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-05 | 8.3 Rules p62 | — | 3 sessions from one address | At most 2 waiting per computer address (oldest dropped); other addresses unaffected | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-06 | 8.3 Rules p62 | — | 34 sessions from 17 addresses | At most 32 waiting in total, oldest dropped | integration | P2 | K: ReceiverProtocolPhoneTest |
| GS2-07 | 8.3 Rules p62 | Approved | Requests 9 min apart; then 10 min idle | Expire after 10 min idle → “expired” | integration (fake clock) | P1 | K: ReceiverProtocolPhoneTest |
| GS2-08 | 8.3 Rules p62 | Approved | No MAC, bad MAC, other session's key, garbage counter | Refused; nothing served | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-09 | 8.3 Rules p62 | Approved from 127.0.0.1 | Same signed request from 127.0.0.2 | Wrong IP → refused | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-10 | 8.3 Rules p62 | Approved | Re-use a counter; counter 0 / negative | Replayed (already used) → refused | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-11 | 8.3 Rules p62 | Highest counter 2000 | Counter 975 (1025 below); 1000 (unused) | More than 1024 below → refused; within window and unused → accepted | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-12 | 8.3 Rules p62 | Highest counter 2000 | Counter 976 (exactly 1024 below) and 977 (1023 below), unused | “1024 or more below the highest” refused (976); 977 accepted | integration | P3 | K: ReceiverProtocolPhoneTest |
| GS2-13 | 8.3 Rules p62 | Approved | Sign one target/range/body/session, send another | Bad signature → refused; no verification recorded | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-14 | 8.3 step 3 p62 | Session not approved | Signed list/file | Refused; nothing opened | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-15 | 8.3 Rules p62 | Approved | ack, unknown file, bad range, bad ack, changed file | Every answer that carries a decision is signed (and the signature binds counter/status/body) | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-16 | 8.3 Rules p62 | List fetched | File grows on phone; fetch | Answered “changed”; after re-list, served | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-17 | 8.3 Rules p63 | Approved | Range from byte 2 MiB + 13 | Resume from a byte offset (206, bytes from that offset) | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-18 | 8.3 Rules p63 | Approved | Fetch 3 MB file | Encrypted in 1 MB frames; no plaintext on the wire; frames bound to resource/offset | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-19 | 8.3 step 4 p62; Glossary p93 | Approved | Ack wrong hash, wrong size, partial hash, phone file edited after sending, right one | Verified only if the phone's whole current file SHA-256 and size equal the Receiver's | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-20 | 8.3 step 4 p62 | — | Mismatching acks | Silently left out of counts (not recorded) | integration | P1 | K: ReceiverProtocolPhoneTest |
| GS2-21 | 8.3 step 4 p62 | Device | Verify, kill app, reopen | Verified saved immediately in history, survives restarts | device-only | P1 | — |
| GS2-22 | 8.3 What the user sees p63 | Device | Receiver-only deliveries; mixed with browser | “N of M files (size) were checked on your computer and match exactly…”; mixed → browser wording | device-only | P2 | — |
| GS2-23 | 8.3 Rules p63; 10.5 step 4 p80 | 8 files, slow phone | Receive | At most 3 streams at once (enforced by the Receiver) | integration | P2 | K: ReceiverEndToEndTest E2E-11; G: engine_test RT05 |
| GS2-24 | 8.3 Safety p63; 10.5 safety p80 | — | Full pairing + transfer; inspect every request | The pairing secret never travels over the network | integration | P1 | G: engine_test RT02 |
| GS2-25 | 8.3 Rules p63; 8.4 step 2 | Device, earlier verifications | Re-queue same items | Earlier verifications matched by same media id and size; labels only, never allow delete for good | device-only | P2 | — |
| GS2-26 | 8.3 step 3 p62 | Device | Scan a Receiver code | Prompt subtitle “N files (size) to *computer name*”; snackbar “Sending to … Each file is checked on the computer.” | device-only | P3 | — |
| GS2-27 | 8.3 Tester note p63; B#21 | Files already on the computer | Send again | Verified without sending bytes (phone bar may never reach “All … sent”) | integration (+ device for the bar) | P2 | K: ReceiverEndToEndTest E2E-02; G: engine_test RT20 |

## 8.4 Send the trash to computer, and delete for good (D2) (pages 63–64)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| D2-01 | 8.4 Where p63 | Confirmed shrink originals in trash | All tools card / Get space back | “Send to computer first” shown only then | device-only | P2 | — |
| D2-02 | 8.4 step 1 p63 | Mixed trash (GallerySlim + other apps, undone items) | Start | Queues GallerySlim-trashed originals, clean-ups, duplicates/burst extras, contact-sheet extras, chat media not undone and still in trash, sorted by date taken | device-only | P1 | — |
| D2-03 | 8.4 step 2 p63 | Earlier verifications in history | Start | Loaded as labels only, never as verified in this run | unit (ShareHub) | P1 | K: PhoneSessionsTest |
| D2-04 | 8.4 Rules p64 | Verified ids in a run | Set a new queue | “This run” resets (verified, delivered, earlier labels, progress) | unit (ShareHub) | P1 | K: PhoneSessionsTest |
| D2-05 | 8.4 Rules p64 | Verified ids in a run | Stop sharing | Run resets | device-only | P1 | — |
| D2-06 | 8.4 step 3 p63–64 | Combinations | Send via Receiver / browser / earlier-only | Three wordings, first match wins; browser-only has no button | device-only | P1 | — |
| D2-07 | 8.4 step 4 p64 | Verified this run | Button and dialog | “Delete N checked files for good”; dialog texts; red “Yes, delete for good”, “Not yet” | device-only | P1 | — |
| D2-08 | 8.4 step 5 p64 | Some items restored/deleted meanwhile | Confirm | Trash re-queried; only still-in-trash AND verified-this-run kept; then Android delete dialog | device-only | P1 | — |
| D2-09 | 8.4 Rules p64; 1 p4 | Browser sends, earlier verifications | Try to delete for good | Never qualify | device-only | P1 | — |
| D2-10 | 8.4 Rules p64 | Other apps' trash, items already moved to computer | Start | Not included | device-only | P1 | — |
| D2-11 | 8.4 What the user sees p64 | Nothing qualifies | Start | “Nothing from GallerySlim is in the trash right now”; Send screen does not open | device-only | P2 | — |
| D2-12 | 8.4 p64 | — | Android dialog OK | “Deleted N files for good. They're on your computer.”; recorded in Moved to computer; ledger refreshes | device-only | P2 | — |
| D2-13 | 8.4 p64 | — | Nothing left at re-check; Cancel | “Nothing can be deleted for good — only files verified on your computer in this session” (no Android dialog); Cancel → nothing changes | device-only | P1 | — |
| D2-14 | 8.4 Tester note p64; B#14 | Trash holds only clean-up items | Look for entry | No entry point (known gap — confirm) | device-only | P3 | — |

## 8.5 Moved to computer (pages 64–65)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| MOV-01 | 8.5 How p65 | — | Free space from Send screen; OK vs cancel | Entry only after Android's dialog returns OK | device-only | P2 | — |
| MOV-02 | 8.5 How p65 | — | Same | 256×256 thumbnail made before; deleted again on cancel | device-only | P3 | — |
| MOV-03 | 8.5 How p65 | Entries in several months + undated | Open screen | Header, “N items · size”, month sections sorted descending as text (“undated” first), “computer · folder” line, 4-column grid | device-only | P3 | — |
| MOV-04 | 8.5 Rules p65 | — | Browser User-Agents (Windows, Mac, ChromeOS, Android, Linux, other) | “Windows PC”, “Mac”, “Chromebook”, “Android device”, “Linux computer”, “Computer” + “ (IP)” | unit | P2 | K: BrowserSendTest |
| MOV-05 | 8.5 Rules p65 | — | Receiver session with a name / empty name / no name | Receiver's computer name, or “Your computer” | integration | P2 | K: ReceiverProtocolPhoneTest |
| MOV-06 | 8.5 Rules p65 | — | Any move | Folder label always “GallerySlim/yyyy-MM” or “GallerySlim/undated” | device-only | P3 | — |
| MOV-07 | 8.5 Rules p65 | — | Empty list; old and undone moves | Empty text as in PDF; all moves shown, including undone | device-only | P3 | — |

## 10.1 Which file, and first run (pages 75–76)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RF-01 | 10.1 Where p75 | `dist/receiver` built | Inspect files | The six files of the table exist; SHA256SUMS lists every file and matches; Linux archive has `GallerySlim-Receiver`, macOS zip has “GallerySlim Receiver” app | integration | P2 | G: system_test |
| RF-02 | 10.1 What the user sees p75 | Page mode, `--no-browser` | Start; Ctrl+C | “GallerySlim Receiver 0.1.0 is running.”, “If your browser didn't open, go to:”, address, “Keep this window open… Ctrl+C to stop.”; exit 0 | integration | P2 | G: cli_test |
| RF-03 | 10.1 p76 | No browser opener | Start page mode | “Couldn't open the browser (error); open the address above yourself.” | integration | P3 | G: cli_test |
| RF-04 | 10.1 How/Rules p75 | Windows, macOS 13–15, Linux desktop | Double-click/open; Gatekeeper, SmartScreen, Local Network, Firewall | As described in PDF | device-only (manual per OS) | P2 | — |

## 10.2 Choosing the folder, and the cloud warning (pages 76–77)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RD-01 | 10.2 How p76 | First run | Inspect page | Heading, text, “Suggested folder”, “Choose folder”, “Use this folder”, “Type a folder path”, “Use this path” | static | P3 | G: page_test RP01 |
| RD-02 | 10.2 Rules p76 | Pictures not synced | First run | Suggests `<Pictures>/GallerySlim Photos`, no note | integration | P2 | G: page_test |
| RD-03 | 10.2 Rules p76–77 | Pictures in Dropbox | First run | Suggests `<home>/GallerySlim Photos` with note “Your Pictures folder syncs with Dropbox, …” | integration | P2 | G: page_test |
| RD-04 | 10.2 Rules p76 | Home and Pictures synced | First run | Suggests Pictures itself with a warning | integration | P3 | G: page_test |
| RD-05 | 10.2 Rules p76 | — | Paths with OneDrive, “OneDrive - …”, Google Drive, My Drive, Dropbox, “Dropbox (…”, iCloudDrive, iCloud Drive, iCloud Photos, ~/Insync; pCloud, Nextcloud, MEGA | Synced ones detected (provider named); others not | unit | P2 | G: system_test |
| RD-06 | 10.2 What the user sees p77 | — | Choose a Dropbox folder | Not blocking; warning text exactly as PDF | integration | P2 | G: page_test (netns) |
| RD-07 | 10.2 Rules p76 | Choose-folder phase | Empty, > 4096 bytes, control/bidi chars, relative path | Exact messages from PDF; folder not set | integration | P1 | G: page_test |
| RD-08 | 10.2 How p76 | — | `"…"` quoted path; `~/PhonePics` | Quotes stripped; `~` expanded | integration | P2 | G: page_test (netns) |
| RD-09 | 10.2 How p76 | — | Choose a folder | `.gallery-slim/` created as a write test | integration | P3 | G: page_test (netns) |
| RD-10 | 10.2 Rules p77 | Unwritable path | Choose it | “Photos can't be saved in that folder: *cause*” | integration | P1 | G: page_test |
| RD-11 | 10.2 Rules p77 | Receiving | Change folder | “Please wait until receiving has finished” | integration | P3 | K: ReceiverPageEndToEndTest |
| RD-12 | 10.2 Rules p77; 10.8 p83 | — | `--folder` in page mode; relative folder in config.json | Remembered in config.json; config folder must be absolute | integration | P2 | G: page_test, system_test |
| RD-13 | 10.2 Rules p76 | No zenity/kdialog | “Choose folder” | “This computer can't show a folder picker; type the folder path.” and the text box opens | integration | P2 | G: page_test |
| RD-14 | 10.2 How p76 | Desktop with dialog | Choose / cancel | Dialog “Choose where to save photos”; “A folder window has opened…”; “Saved. Photos will go to *path*”; cancel no change | device-only (manual) | P3 | — |

## 10.3 The Receiver page: phase by phase (pages 77–78)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RP-01 | 10.3 Where p77 | Page mode | Open page | Served at `/token/`; header “GallerySlim Receiver”; footer version 0.1.0; “Close the receiver” | integration + static | P2 | G: page_test |
| RP-02 | 10.3 How p77–78 | Phone reachable | Pair, receive | Phases finding → pairing → receiving → done | integration | P1 | K: ReceiverPageEndToEndTest |
| RP-03 | 10.3 Finding p77 | No phone (isolated network) | Folder set | “No phone found yet. On your phone, open GallerySlim …” | integration (netns) | P2 | G: page_test |
| RP-04 | 10.3 Finding p77 | Two phones on the LAN | Search | “Phones found. Choose yours:” buttons “name · code code”; re-search stops | device-only (LAN) | P3 | — |
| RP-05 | 10.3 Finding p77 | One phone | Search | Goes straight to pairing | integration | P2 | K: ReceiverPageEndToEndTest |
| RP-06 | 10.3 Finding p77; 10.4 p79 | — | Type addresses | Validation messages of 10.4 | integration | P2 | G: page_test |
| RP-07 | 10.3 Pairing p77 | — | Pairing phase | QR code (gs2), “Code *code*”, “Phone: *name*”, saving folder | integration | P1 | K: ReceiverPageEndToEndTest |
| RP-08 | 10.3 Receiving p77 | 620 rows | Status | At most 500 rows sent, rest counted for “and N earlier file(s)” | integration | P3 | G: ui_test |
| RP-09 | 10.3 Done p78 | — | Finish | “All done!” or “Finished, but some photos were not saved”, counts | integration + static | P2 | K: ReceiverPageEndToEndTest; G: page_test |
| RP-10 | 10.3 p78 | — | Inspect page | Row labels Waiting / Receiving… / Saved, checking… / Checked and safe / Skipped / Not saved | static | P3 | G: page_test |
| RP-11 | 10.3 Rules p78 | Browser | Close the Receiver behind the page | Polls 700 ms (2 s after failures); “Lost contact with the receiver.” after 3 failed polls | static + device (browser) | P3 | G: page_test (text only) |
| RP-12 | 10.3 Rules p78 | One file failed | “Receive more” | Retries only missing files in the same session; verified ones “Received and checked earlier” | integration | P1 | K: ReceiverPageEndToEndTest |
| RP-13 | 10.3 Rules p78 | Code not confirmed in time | “Try again” | “The code expired before it was confirmed on the phone. Press Try again for a new code.”; new code for the same phone | integration | P2 | K: ReceiverPageEndToEndTest |
| RP-14 | 10.3 Rules p78 | Phone disappears while pairing | Wait | “Couldn't reach the phone. Check that it still shows "Send to computer" and is on the same Wi-Fi.” | integration | P2 | K: ReceiverPageEndToEndTest |
| RP-15 | 10.3 Rules p78 | — | Unavailable action; crashing action; action while busy | “This isn't available right now.”, “Something went wrong inside the receiver. Please try again.”, “Please wait a moment, …” | integration | P3 | G: ui_test |
| RP-16 | 10.3 Closed p78 | Page mode | “Close the receiver” | Closed; program exits ~150 ms later (exit 0) | integration | P2 | G: page_test |
| RP-17 | 10.3 Safety p78; 10.9 p84 | Phone text with control/bidi chars, very long | Status | Cleaned and length-limited | integration | P1 | G: ui_test |
| RP-18 | 10.3 p77–78 | — | Inspect page | Tab titles “NN% · GallerySlim Receiver”, “Done · GallerySlim Receiver” | static | P3 | G: page_test |
| RP-19 | 10.3 What the user sees p78 | No folder | “Open folder” | “No folder chosen yet” (with a folder: file manager — device) | integration + device | P3 | G: page_test |
| RP-20 | 10.3 Tester note p78; B#22 | Receiving | Phone ends the session | Page returns to pairing with a new code (note set but not shown) | integration | P2 | K: ReceiverPageEndToEndTest |

## 10.4 Finding the phone (page 79)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RN-01 | 10.4 How p79 | Real LAN with multicast | Search | One mDNS query for galleryslim.local, answers for 1.5 s, then ports 8765–8769 | device-only (LAN) | P2 | — |
| RN-02 | 10.4 How p79 | — | Hosts for /24 and /30 | Every address except network, broadcast, own; ports 8765–8769 | unit | P2 | G: system_test |
| RN-03 | 10.4 How p79 | Servers answering protocol 1, other app, no header, HTML | Probe | Only GallerySlim protocol 2 counts | integration | P1 | G: system_test |
| RN-04 | 10.4 Rules p79 | — | `ip`, `ip:port`, host names, > 64 chars, IPv6; phone on :8767, typed without port | IPv4 with optional port, ≤ 64 chars; host names rejected; without port 8765–8769 tried | unit + integration | P1 | G: cli_test, system_test |
| RN-05 | 10.4 Rules p79 | Page | Empty, > 100 chars, bad chars, `http://…/x` | Page messages from PDF; `http://` and paths stripped | integration | P2 | G: page_test |
| RN-06 | 10.4 Rules p79 | — | `--phone` to a closed port | “no GallerySlim phone answered at *addr*; check that the phone shows "Send to computer" and is on the same Wi-Fi” | integration | P1 | G: cli_test |
| RN-07 | 10.4 Rules p79; A.6 | — | /16, /23, /31, /32 | Not probed | unit | P2 | G: system_test |

## 10.5 Pairing, transfer, resume and verification (pages 79–81)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RT-01 | 10.5 step 1 p80 | Fake phone | Pair | 32-byte secret only inside `gs2:` QR, shown with the phone's short code; polls every 1 s | integration | P1 | G: engine_test |
| RT-02 | 10.5 safety p80–81 | — | Full run; inspect requests, log, folder | Secret never sent, written or logged | integration | P1 | G: engine_test; K: ReceiverEndToEndTest E2E-01 |
| RT-03 | 10.5 step 2 p80 | List fails 2× / 3× | Receive | Up to 3 tries, waits 1 s and 2 s | integration | P2 | G: engine_test |
| RT-04 | 10.5 step 3 p80 | Listed size > free − 50 MB | Receive | Error “not enough free space: need *needed*, free *free*” (needed excludes margin); nothing downloaded | integration | P1 | G: engine_test; K: E2E-06 |
| RT-05 | 10.5 step 4 p80 | 8 files | Receive | Up to 3 files in parallel | integration | P2 | G: engine_test; K: E2E-11 |
| RT-06 | 10.5 step 4 p80 | — | Receive | Partial file → moved into place without overwriting → re-read and hashed → hash+size to phone | integration | P1 | K: E2E-01; G: engine/store tests |
| RT-07 | 10.5 Rules p80 | State polls fail | Pair | Times out after 10 min, or after 15 failed polls in a row | integration (15 polls) + manual (10 min) | P2 | G: engine_test |
| RT-08 | 10.5 Rules p80 | Phone answers 503 | Receive | Wait 2 s, retry up to 5 times, then “The phone stayed busy”; brief busy recovers | integration | P1 | G: engine_test |
| RT-09 | 10.5 Rules p80 | Stream sends nothing for > 60 s | Receive | Cancelled (“The connection stopped; try again”) and retried | integration (slow) | P2 | G: engine_test |
| RT-10 | 10.5 Rules p80 | File request always fails | Receive | 5 attempts, waits 1, 2, 4, 8 s | integration | P2 | G: engine_test |
| RT-11 | 10.5 Rules p80 | Interrupted mid-file | New QR session, same phone | Partial in `.gallery-slim/parts/` continued (range request) | integration | P1 | G: engine_test; K: E2E-09 |
| RT-12 | 10.5 Rules p80 | Partial larger than listed size | Receive | Discarded; download restarts at 0 | integration | P2 | G: engine_test |
| RT-13 | 10.5 Rules p80 | Parts 15 and 13 days old | Run | > 14 days deleted at start of run; newer kept | integration | P2 | K: E2E-10 |
| RT-14 | 10.5 Rules p80 | File changes on phone | Receive | Partial discarded, list fetched again (at most 2 times), new version saved | integration | P1 | G: engine_test; K: E2E-05 |
| RT-15 | 10.5 Rules p80 | File removed from phone | Receive | “Skipped” “No longer on the phone” | integration | P1 | G: engine_test; K: E2E-04 |
| RT-16 | 10.5 Rules p80 | List checksum wrong | Receive | Downloaded once more from scratch; then “Not saved” “The received copy doesn't match the phone's checksum” | integration | P1 | G: engine_test |
| RT-17 | 10.5 Rules p80 | Disk returns wrong size | Receive | Failed “The saved file has the wrong size on disk” | device-only (fault injection) | P3 | — |
| RT-18 | 10.5 Rules p80 | Ack cannot be sent | Receive | Saved but not confirmed: “Saved, but the phone could not be asked to confirm it” | integration | P1 | G: engine_test |
| RT-19 | 10.5 Rules p80 | Phone says mismatch | Receive | File kept and flagged; one fresh download under a new name; then “Couldn't confirm — kept. …” | integration | P1 | G: engine_test; K: E2E-03 |
| RT-20 | 10.5 Rules p80 | File already on computer | Receive again | Re-hashed and confirmed without downloading: “Already on this computer (checked)”; a changed copy is not confirmed | integration | P1 | G: engine_test; K: E2E-02 |
| RT-21 | 10.5 Rules p80 | Windows, deep folder | Receive | Note “The full path is longer than 260 characters…” | device-only (Windows) | P3 | — |
| RT-22 | 10.5 Rules p80; A.6 | List with duplicate ids, 256/257-byte ids, negative and 2 TiB sizes, control chars in id | Receive | Invalid or duplicate entries silently dropped; 256-byte id accepted | integration | P2 | G: engine_test |
| RT-23 | 10.5 What the user sees p80 | — | Error kinds | Short errors exactly as PDF | unit | P2 | G: engine_test |
| RT-24 | 10.5 Safety p81 | Unsigned / wrongly signed “verified” | Receive | Treated as a network error, never a decision | integration | P1 | G: engine_test |
| RT-25 | 10.5 Safety p81 | Phone says mismatch | Receive | Finished file never deleted or replaced | integration | P1 | G: engine_test RT19; K: E2E-03 |

## 10.6 Where files go and never-overwrite naming (page 81)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RW-01 | 10.6 How p81 | — | Months valid/malformed | `<folder>/yyyy-MM/name` directly inside folder; `undated/` when unknown or malformed | unit + integration | P1 | G: store_test; K: E2E-01 |
| RW-02 | 10.6 How p81 | Dates past, 0, negative, +2 h, +72 h | Receive | mtime = date taken unless ≤ 0 or > 24 h in the future | integration | P2 | G: store_test; K: E2E-01 |
| RW-03 | 10.6 How p81 | — | Receive | `.gallery-slim/` with `parts/` and `manifest.json` | integration | P3 | G: store_test |
| RW-04 | 10.6 Rules p81 | Existing file/folder with the same name | Receive | “name (2).ext”, “(3)”…; existing never replaced | unit + integration | P1 | G: store_test; K: E2E-01 |
| RW-05 | 10.6 Rules p81 | name … (9998) exist | Save twice | (9999) used; then refused, nothing overwritten | unit | P3 | G: store_test |
| RW-06 | 10.6 Rules p81 | `img_1.jpg` exists | Save `IMG_1.JPG` | Windows/macOS: counts as taken → “(2)”; Linux: separate name | unit + integration | P2 | G: store_test; K: E2E-01 |
| RW-07 | 10.6 Rules p81 | — | Names with `<>:"/\|?*`, control/bidi, trailing dots/spaces, leading space/dot, empty, CON/PRN/AUX/NUL/CONIN$/CONOUT$/COM0–9/LPT0–9/superscripts, no extension | Cleaned exactly as PDF | unit + integration | P1 | G: store_test; K: E2E-01 |
| RW-08 | 10.6 Rules p81; 9.8 | Hindi names | Receive | Kept as is | unit + integration | P1 | G: store_test; K: E2E-01 |
| RW-09 | 10.6 Rules p81 | 300-byte ASCII, multi-byte Hindi/emoji names | Clean (with and without suffix) | ≤ 255 bytes, valid UTF-8, extension kept | unit | P1 | G: store_test; K: E2E-01 |
| RW-10 | 10.6 Rules p81 | Two savers, one folder | 40 concurrent saves of one name | One lock per folder: 40 distinct names, none overwritten | integration | P2 | G: store_test |
| RW-11 | 10.6 Safety p81 | Two phones, same file name | Receive both | Second becomes “(2)”; first untouched | integration | P1 | G: store_test |
| RW-12 | 10.6 Rules p81 | Windows with AV/sync lock | Receive | Renames retried 6 times | device-only (Windows) | P3 | — |

## 10.7 Keep-awake and idle auto-quit (page 82)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RK-01 | 10.7 How p82 | Each OS | Receive a long batch; kill the Receiver | System kept awake (Windows API, `caffeinate`, `systemd-inhibit`); released if the Receiver dies | device-only (manual) | P2 | — |
| RK-02 | 10.7 How p82 | Page mode, page never opened | Wait | Stops after 30–31 min: “The page has been closed for a while; stopping.” (exit 0) | integration (slow, 31 min) | P2 | G: slow_test (GS_BB_SLOW=1) |
| RK-03 | 10.7 How p82 | Page polling | Wait 33 min | Keeps running | integration (slow) | P3 | G: slow_test (GS_BB_SLOW=1) |
| RK-04 | 10.7 How p82 | Waiting for pairing, page closed | Wait 31 min | Counts as idle → quits | device-only (manual) | P3 | — |
| RK-05 | 10.7 Safety p82 | Mid-transfer | Ctrl+C, start again, scan again | Saved files kept; partial continues | integration | P1 | K: E2E-09 |

## 10.8 Headless mode, command-line options and exit codes (pages 82–83)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RH-01 | 10.8 p83 | — | `--version` | Prints the version, exit 0 | integration | P2 | G: cli_test |
| RH-02 | 10.8 p83 | — | Unknown flag | Exit 2 | integration | P1 | G: cli_test |
| RH-03 | 10.8 p83 | — | Stray argument (headless and page mode) | Exit 2 | integration | P1 | G: cli_test |
| RH-04 | 10.8 p83 | Folder saved in config | `--headless` without `--folder` | “Please choose a folder: --headless --folder <path> [--phone <ip[:port]>]”, exit 2 | integration | P1 | G: cli_test |
| RH-05 | 10.8 p82–83 | Linux, no DISPLAY/WAYLAND_DISPLAY, no saved folder | Run | “No desktop found, so running in the terminal. Choose a folder with --folder <path>.”, exit 2 | integration | P1 | G: cli_test |
| RH-06 | 10.8 p83 | Same, folder saved | Run with `--phone` | Uses the saved folder (“…, saving to *saved*”) | integration | P2 | G: cli_test |
| RH-07 | 10.8 p83 | — | `--folder` not writable | “Photos can't be saved in *folder*: …”, exit 2 | integration | P1 | G: cli_test |
| RH-08 | 10.8 p83; A.6 | — | `--phone` malformed / text / host name / too long / IPv6 / not answering | Exit 1; no pairing | integration | P1 | G: cli_test |
| RH-09 | 10.8 p83 | Isolated network | Headless without `--phone` | “Looking for your phone…”, “No phone found. Run again with --phone <address shown on the phone>.”, exit 1 | integration (netns) | P1 | G: cli_test |
| RH-10 | 10.8 p83 | Two phones found | Headless | “Found several phones; run again with --phone and one of these addresses:” + one line per phone, exit 2 | integration | P2 | G: cli_test |
| RH-11 | 10.8 p83 | — | Full headless run | Output lines in the PDF's order, per-file lines, Done line | integration | P1 | G: cli_test (to the QR); K: E2E-01 (whole run) |
| RH-12 | 10.8 p83 | Slow transfer | Watch output | Progress every 5 s “x of y (p%)” | integration | P3 | K: E2E-12 |
| RH-13 | 10.8 p83 | — | Everything verified (or no longer on phone) | Exit 0 | integration | P1 | K: E2E-01, E2E-04 |
| RH-14 | 10.8 p83 | — | A file unconfirmed or failed | Exit 1 | integration | P1 | K: E2E-03 |
| RH-15 | 10.8 p83 | Headless | Ctrl+C | Exit 1 | integration | P2 | G: cli_test; K: E2E-09 |
| RH-16 | 10.8 p83 | Code dropped by phone | Wait | “Pairing failed: …”, exit 1 | integration | P2 | G: cli_test |
| RH-17 | 10.8 p83 | Phone ends session | Continue / three times | “The phone ended the session (…). Scan the new code to continue; saved files are kept.”; after 3 sessions “Gave up after 3 sessions.” (exit 1) | integration | P2 | K: E2E-07, E2E-08 |
| RH-18 | 10.8 p83 | Folder in Dropbox | Headless | “Note: *folder* syncs to Dropbox; photos there may be moved off this computer.” | integration | P2 | G: cli_test |
| RH-19 | 10.8 p83 | — | Default / `--plain-qr` | Half-block QR, black on white by default; plain with `--plain-qr` | integration | P3 | G: cli_test |
| RH-20 | 10.8 p83 | Page mode | `--no-browser` | Browser not opened; address printed | integration | P3 | G: cli_test RF02 |
| RH-21 | 10.8 p83 | Page mode | Close normally / Ctrl+C | Exit 0 | integration | P2 | G: cli_test, page_test |
| RH-22 | 10.8 p83 | Folder writable but unusable | Headless | “Can't use the folder: …”, exit 1 | device-only (cannot be provoked portably) | P3 | — |

## 10.9 Limits, settings, log and page security (page 84)

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| RS-01 | 10.9 How p84 | Linux | Run | Settings folder `~/.config/GallerySlim Receiver`, private to the user | integration | P2 | G: page_test |
| RS-02 | 10.9 How p84 | — | Save/load config | `config.json`: folder (absolute only), openBrowser default true; written atomically | unit | P2 | G: system_test, page_test |
| RS-03 | 10.9 How p84 | — | Log > 2 MB | `receiver.log` private; rotates at 2 MB to `receiver.log.1` | unit | P2 | G: system_test |
| RS-04 | 10.9 How p84 | — | Page run; full transfer | Log never holds the secret, QR text, keys, signatures or the page token | integration | P1 | G: page_test; K: E2E-01 |
| RS-05 | 10.9 Rules p84 | — | Foreign Host headers | 403 “Forbidden”; 127.0.0.1:port and localhost:port allowed | integration | P1 | G: page_test |
| RS-06 | 10.9 Rules p84 | — | Wrong/missing token, unknown path | 404 | integration | P1 | G: page_test |
| RS-07 | 10.9 Rules p84 | — | Wrong method | 405 | integration | P1 | G: page_test |
| RS-08 | 10.9 Rules p84 | — | Action with foreign Origin / cross-site | 403 | integration | P1 | G: page_test |
| RS-09 | 10.9 Rules p84 | — | Action not JSON | 415 | integration | P1 | G: page_test |
| RS-10 | 10.9 Rules p84 | — | Body > 16 KB | 413 | integration | P1 | G: page_test |
| RS-11 | 10.9 Rules p84 | — | Unknown JSON field | 400 | integration | P1 | G: page_test |
| RS-12 | 10.9 Rules p84 | — | Two runs; connect via other addresses | 127.0.0.1 only; random port; random 32-character token | integration | P1 | G: page_test |
| RS-13 | 10.9 Rules p84 | — | Response headers, CORS preflight | Strict CSP; no cross-origin access; cannot be framed | integration | P1 | G: page_test |
| RS-14 | 10.9 Rules p84 | Long, dirty phone text | Status | Phone name 40, code 12, file name 200, detail 300, errors 400; ≤ 10 phones, ≤ 500 rows | integration | P1 | G: ui_test |
| UN-01 | 2.2 p7; 10.5 step 4 p80 | — | Sizes 1.2/2.9/4.2 GB, 12/900 GB, 5.5/55/245 MB, 1.5/245 kB | Decimal; one decimal under 10, whole from 10 up; terminal writes “kB” | unit | P2 | G: engine_test |

## Related, outside this area

| ID | PDF | Preconditions | Inputs / steps | Expected | Type | Pri | Automated in |
|---|---|---|---|---|---|---|---|
| VLT-01 | 9.3 Rules p67 | Vault on with a folder | Shrink an item; corrupt the vault copy; restore | Each vault copy is read back and kept only if length and SHA-256 match; otherwise the item is not shrunk (“Couldn't protect it in the vault — left untouched”); restored files read back and checked too | device-only (needs Android storage access; vault code needs a Context) | P1 | — |
