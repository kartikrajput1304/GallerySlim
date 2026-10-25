# Black-box results: Send to computer and GallerySlim Receiver

Final run 2026-10-24, against the **updated 92-page** `docs/features/GallerySlim-Features.pdf` and after the code fixes.
Cases: `docs/testing/blackbox-share-cases.md`. Expectations come only from the PDF.

## How the tests were run

| Suite | Command | Result |
|---|---|---|
| Kotlin (phone server; official Receiver build end-to-end) | `GS_RECEIVER_BIN=<dist/receiver/GallerySlim-Receiver-linux-amd64.tar.gz → GallerySlim-Receiver> flock .build.lock gradle --no-daemon -q :app:testDebugUnitTest --tests 'com.galleryslim.blackbox.share.*'` | **82 / 82 pass** |
| Go (Receiver engine, CLI, page, storage) | `cd receiver && ~/tools/go1.27.3/bin/go test ./blackbox/...` | **70 pass, 0 fail, 2 skipped** (the slow ones) |
| Go, slow (idle quit) | `GS_BB_SLOW=1 go test ./blackbox/ -run TestRK0 -timeout 45m` | 2 / 2 pass — run before the latest code fixes, not re-run |

Notes:
- Official build: `dist/receiver/SHA256SUMS` verified OK; the extracted Linux amd64 binary reports `0.1.0`.
  The Go suite builds its own binary from the current `receiver/` sources (`-X main.version=0.1.0`).
- The Go suite runs inside a loopback-only network namespace (`unshare -rn`) so the Receiver never probes the real LAN;
  Kotlin end-to-end tests always pass `--phone 127.0.0.1:<port>`.
- On the JVM the phone cannot read real gallery files, so browser ZIP entries / single downloads arrive empty; names, folders,
  headers and access rules are the real server's. "static" cases check the served page, not browser behaviour.

## Totals

| | Count |
|---|---|
| Cases | 232 (added SND-36 Host 421 check, VLT-01 vault read-back) |
| Automated | 183 |
| PASS | 183 |
| FAIL | 0 |
| NOT AUTOMATED (device-only / manual) | 49 |

## Previous failures and how they were resolved

| ID | First run (old PDF) | New PDF says | Now |
|---|---|---|---|
| RT-15 (P1) | Real phone + deleted file → “failed … the file changed on the phone”, exit 1 (`Gs2Routes.kt:87` answered 409) | Unchanged in substance; now explicit (p62): a deleted file is answered “gone” and skipped | PASS (code fixed): `skipped … — No longer on the phone`, exit 0 with the official build |
| RD-08 | `~/PhonePics` refused on the page (`ui.go:469`) | Unchanged: a path may start with `~`, which is expanded (p76) | PASS (code fixed) |
| RT-04 | “900.0 GB needed, 139.7 GB free” | “not enough free space: need X, free Y” (p80) | PASS (code fixed): `Stopped: not enough free space: need 900 GB, free …` |
| UN-01 | Terminal always one decimal (`12.0 GB`) | One decimal below 10, whole from 10; terminal writes “kB” (p7, p80) | PASS (code fixed); test extended with kB cases |
| RP-06 / RN-05 | Host name rejected with a lower-case CLI message | Unchanged page messages (p79) | PASS (code fixed) |
| MOV-05 | Session without a `receiver` field labelled “Receiver” | Unchanged: computer name or “Your computer” (p65) | PASS (code fixed) |
| GS2-12 | Counter exactly 1024 below refused | Changed: refused when “1024 or more below” (p62) | Expectation updated; PASS |
| SND-22 | 8192-character header line refused | Changed: lines of at most 8191 characters (p60) | Expectation updated; PASS |
| GS1-14 | ZIP method DEFLATE, PDF said “stored” | Changed: “no compression (DEFLATE at level 0)” (p61) | Expectation updated; PASS (method 8; level not measurable on JVM because entries are empty) |

New content in the updated PDF covered:
- **SND-36** Host check (p60): own IP or galleryslim.local, with or without port, served; `evil.example`, other IPs and `localhost` → 421 Misdirected Request, no page session created; also on Receiver routes. PASS.
- **Delete for good only for files verified in this run** (p62–64): automatable parts PASS (D2-03 earlier verifications are labels only; D2-04 a new queue resets the run). The final re-check, its new message “Nothing can be deleted for good — only files verified on your computer in this session” and the Android dialog are device-only (D2-08, D2-13 updated).
- **No-screen-lock in-app confirmation** (p59, SND-34 updated) and **no-Wi-Fi messages** (SND-35 updated): device-only.
- **Vault SHA-256 read-back** (9.3, p67): outside this area and not testable here (vault code needs an Android Context and storage access); recorded as device-only VLT-01.

## Per-case results

Legend: PASS / FAIL / NOT AUTOMATED. "(partial)" = the automatable part passed; the rest needs a device (listed below).

### 8.1 Sharing service
| ID | Result | Evidence |
|---|---|---|
| SND-01…06 | NOT AUTOMATED | device UI |
| SND-07 | PASS (partial) | K PhoneSessionsTest — ShareHub resets progress on approval; bar on device |
| SND-08 | NOT AUTOMATED | device |
| SND-09 | PASS (partial) | K PhoneSessionsTest — only gs1:/gs2: codes approve; camera on device |
| SND-10, SND-11 | NOT AUTOMATED | device |
| SND-12 … SND-21 | PASS | K PhoneServerLimitsTest (8767 chosen when 8765–8766 busy; random port; one address; subnet refusal with no reply; private ranges; 503 at 9th / 6th-per-address; 10 s header limit closed at ~10 s; 64 lines; 32 KB) |
| SND-22 | PASS | 8191 accepted, 8192 refused (new PDF) |
| SND-36 | PASS | Host check 421 |
| SND-23, SND-24 | PASS | K PhoneServerLimitsTest (16 KB+1 closed without answer; stalled stream closed, < 48 MB delivered) |
| SND-25 … SND-27 | PASS | K PhoneSessionsTest (32 kept of 33; pinned to IP) |
| SND-28 | PASS (partial) | 4 000 codes all match; all 24 letters and 10–99 used; "new per start" on device |
| SND-29 | NOT AUTOMATED | device |
| SND-30 | PASS (partial) | stop cuts a running transfer and closes the port; queue emptying/revocation by the service on device |
| SND-31 | PASS (partial) | streaming signalled only during file streams (and released when a stalled client is cut); locks on device |
| SND-32 … SND-35 | NOT AUTOMATED | device (SND-34/35 texts changed in the new PDF) |

### 8.2 Browser (gs1)
| ID | Result | Evidence |
|---|---|---|
| GS1-01, GS1-02 | PASS | page QR decodes (ZXing) to the page's own `gs1:` code; all texts present |
| GS1-03 | PASS (partial) | snackbar text on device |
| GS1-04 … GS1-13 | PASS | (05–08 static) ZIP name, month/undated layout, (2)/(3), entry time, `_` for `/ \ control`, `../` cannot escape |
| GS1-14 | PASS | DEFLATE method (level 0 per new PDF) |
| GS1-15, GS1-16, GS1-19, GS1-21 | PASS | 403 before approval / wrong secret / other computer; Hindi name kept |
| GS1-17, GS1-18 | PASS (static) | real-browser behaviour on device |
| GS1-20, GS1-22, GS1-23 | NOT AUTOMATED | need real gallery files and Android dialogs |

### 8.3 Receiver, phone side (gs2)
| ID | Result | Evidence |
|---|---|---|
| GS2-01 … GS2-11 | PASS | K ReceiverProtocolPhoneTest |
| GS2-12 | PASS | 976 refused, 977 accepted |
| GS2-13 … GS2-20 | PASS | K ReceiverProtocolPhoneTest |
| GS2-21, GS2-22, GS2-25, GS2-26 | NOT AUTOMATED | device |
| GS2-23, GS2-24 | PASS | max 3 streams (K E2E-11, G RT05); secret not in any request (G RT02) |
| GS2-27 | PASS (partial) | K E2E-02: second run streams nothing, all “Already on this computer (checked)”; phone progress bar on device |

### 8.4 Trash export / delete for good (D2)
| ID | Result | Evidence |
|---|---|---|
| D2-03, D2-04 | PASS | K PhoneSessionsTest (earlier verifications only labels; new queue resets the run) |
| D2-01, D2-02, D2-05 … D2-14 | NOT AUTOMATED | eligibility and dialogs live in Compose screens and Android dialogs |

### 8.5 Moved to computer
| ID | Result | Evidence |
|---|---|---|
| MOV-04 | PASS | all six User-Agent labels |
| MOV-05 | PASS | name / empty / missing field → “Your computer” |
| MOV-01 … 03, 06, 07 | NOT AUTOMATED | device |

### 10.1 – 10.2 First run and folder
| ID | Result | Evidence |
|---|---|---|
| RF-01 … RF-03 | PASS | dist files and SHA256SUMS match; console texts; browser-failure text |
| RF-04 | NOT AUTOMATED | manual on Windows/macOS |
| RD-01 … RD-07 | PASS | suggestions incl. Dropbox-synced Pictures and synced home; cloud names; warning not blocking; exact path messages |
| RD-08 | PASS | quotes stripped, `~` expanded |
| RD-09 … RD-13 | PASS | `.gallery-slim/` created; not-writable text; “Please wait until receiving has finished”; `--folder` saved; relative config folder ignored; no-picker text |
| RD-14 | NOT AUTOMATED | real folder dialogs |

### 10.3 – 10.4 Page and finding
| ID | Result | Evidence |
|---|---|---|
| RP-01 … RP-03, RP-05, RP-07 … RP-10 | PASS | K ReceiverPageEndToEndTest (finding → pairing → receiving → done with real phone; page QR decoded and approved) and G page/ui tests |
| RP-04 | NOT AUTOMATED | needs two phones on a LAN |
| RP-06 | PASS | |
| RP-11 | PASS (static) | texts present; timing in a browser on device |
| RP-12 … RP-20 except RP-19 | PASS | Receive more fetched only the failed file, others “Received and checked earlier”; expired code → new code; unreachable phone text; quit exits in < 2 s with 0; limits on phone text; tab titles; phone-ended session → new code |
| RP-19 | PASS (partial) | “No folder chosen yet”; file manager on device |
| RN-01 | NOT AUTOMATED | real multicast LAN |
| RN-02 … RN-04, RN-06, RN-07 | PASS | |
| RN-05 | PASS | |

### 10.5 – 10.9 Receiver
| ID | Result | Evidence |
|---|---|---|
| RT-01 … RT-03, RT-05, RT-06 | PASS | |
| RT-04 | PASS | |
| RT-07 | PASS (partial) | 15 failed polls end pairing; the 10-minute timeout not run |
| RT-08 … RT-14, RT-16, RT-18 … RT-20, RT-22 … RT-25 | PASS | busy 5×2 s; 60 s stall retried; 1/2/4/8 s retries; resume with new QR (G and real binary after Ctrl+C); oversize partial discarded; >14-day parts removed; checksum, unconfirmed, mismatch-kept (`IMG_7 (2).jpg`), already-here, list limits, short errors, forged/unsigned “verified” ignored |
| RT-15 | PASS | real phone + deleted file → skipped, exit 0 |
| RT-17, RT-21 | NOT AUTOMATED | disk fault injection / Windows |
| RW-01 … RW-11 | PASS | |
| RW-12 | NOT AUTOMATED | Windows AV/sync locks |
| RK-02, RK-03 | PASS | quit after 30 min 0 s with the PDF message, exit 0; polling page kept it alive 33 min (run before the latest fixes) |
| RK-05 | PASS | K E2E-09 |
| RK-01, RK-04 | NOT AUTOMATED | manual |
| RH-01 … RH-21 | PASS | all exit codes and messages as PDF (incl. Ctrl+C headless → 1, page → 0; 3-session give-up; synced-folder note; ANSI vs `--plain-qr`) |
| RH-22 | NOT AUTOMATED | cannot be provoked portably |
| RS-01 … RS-14 | PASS | |
| UN-01 | PASS | |
| VLT-01 | NOT AUTOMATED | vault (9.3), device |

## Device-only / manual cases for a human

0. Vault (outside this area): VLT-01 SHA-256 read-back of vault copies and restores.
1. Send screen and flow: SND-01…06, SND-08, SND-10 (prompt always shown; cancel → “Not sent”), SND-11 (Wi-Fi only, VPN refused),
   SND-29 (auto-stop: Wi-Fi lost, swipe away, Android 15 limit, ~30 min idle), SND-32/33 (notification), SND-34 (no screen lock →
   in-app “Send these files?” dialog), SND-35 (no Wi-Fi: “Connect the phone to Wi-Fi first”, “Not sharing: no Wi-Fi”), the device halves of SND-07/09/28/30/31.
2. Browser: GS1-20 (“fully sent” rules incl. cut-off ZIP), GS1-22 (step 4, dialogs, snackbars), GS1-23 (browser sends never deletable for good),
   GS1-17/18 in a real browser (2/4 at a time, folder picker over HTTPS), GS1-03 snackbar.
3. Receiver on phone: GS2-21 (verification survives restart), GS2-22 (step 4 wording, mixed wording), GS2-25 (earlier verifications by id+size),
   GS2-26 (prompt subtitle names the computer), GS2-27 progress bar, GS2-03 “That code has expired…”.
4. Delete for good (D2-01, 02, 05–14, incl. the new re-check message “Nothing can be deleted for good — only files verified on your computer in this session”): who qualifies, re-check before Android's delete dialog, browser and earlier verifications never qualify,
   other apps' trash excluded, all texts; and the known gap D2-14.
5. Moved to computer: MOV-01–03, 06, 07.
6. Receiver on each OS: RF-04, RD-14 (folder dialogs), RK-01 (keep-awake per OS), RK-04 (pairing counts as idle), RN-01 (mDNS on a LAN),
   RP-04 (two phones), RT-21 and RW-12 (Windows), RT-17, RH-22, the 10-minute pairing timeout (RT-07), RP-11 in a browser.

## Files

- Cases: `docs/testing/blackbox-share-cases.md`
- Kotlin: `app/src/test/java/com/galleryslim/blackbox/share/` (`BbHarness.kt`, `PhoneServerLimitsTest.kt`, `PhoneSessionsTest.kt`,
  `BrowserSendTest.kt`, `ReceiverProtocolPhoneTest.kt`, `ReceiverEndToEndTest.kt`, `ReceiverPageEndToEndTest.kt`)
- Go: `receiver/blackbox/` (`main_test.go`, `fakephone_test.go`, `engine_test.go`, `cli_test.go`, `page_test.go`, `ui_test.go`,
  `store_test.go`, `system_test.go`, `slow_test.go`)
