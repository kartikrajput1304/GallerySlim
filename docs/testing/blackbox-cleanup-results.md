# GallerySlim black-box results: Clean up, keeping slim, and other features (revision 2)

**Run details**
- Run: 2026-10-24 18:18 UTC, against the updated 92-page `docs/features/GallerySlim-Features.pdf` and the code fixes that landed after revision 1 (ID-shield whitespace, English month names, duplicate order).
- Command: `flock .build.lock gradle --no-daemon -q :app:testDebugUnitTest --tests 'com.galleryslim.blackbox.cleanup.*'`
- Cases: `docs/testing/blackbox-cleanup-cases.md`. Rows marked **(rev 2)** come from the new PDF text.
- Tests: `app/src/test/java/com/galleryslim/blackbox/cleanup/`
- Expectations come only from the PDF.

## Summary

| | Count |
|---|---|
| Cases catalogued | 270 |
| Automated JUnit tests run | 220 |
| PASS | **220** |
| FAIL | **0** |
| NOT AUTOMATED (device-only) | 46 |
| Structural / alias rows | 4: CM-08, ID-14 → SF-01, SF-02 → CL-26, SF-04 → DU-18 |

## What happened to the revision-1 failures

| Rev-1 failure | Cases | Now | Why |
|---|---|---|---|
| F1: ID phrases split by line breaks | ID-15 | PASS | Code fixed. The expectation is unchanged (PDF §1 p4, §6.2 p42). |
| F2: Hindi month name | MR-06 | PASS | Code fixed. The expectation is unchanged (p58, p73). |
| F3: duplicate-extra order | QR-04 | PASS | Code fixed. The expectation is unchanged (p45). |
| F4: Duplicates stricter than PDF | DU-20 – DU-23 | PASS | The new PDF describes the code's behaviour, so the expectations were updated. §6.6 p46: an ID or unreadable copy removes the whole exact group; unreadable photos are removed; unchecked members are held "Not checked yet"; recent members are held "Protected · recent"; held members are never pre-selected. |
| F5: Chat media stricter than PDF | CM-09 – CM-11 | PASS | The new PDF describes the code's behaviour, so the expectations were updated. §6.7 p47-48: offered items are checked, not ID or unreadable, not favourite, not inside the protect window, not Never-touch. §6.2 p42: the ID shield applies to Chat media. |
| F6: Quick review held extras | QR-20 | PASS | The new PDF describes the code's behaviour, so the expectation was updated. §6.5 p45: held members never enter the queue. |
| F7: Privacy wording | PR-01 | PASS | The new PDF §9.7 p71 quotes the shipped text, so the expectation was updated. |

Only the cases above changed their expected results, and only where the new PDF text says so. ID-15, MR-06 and QR-04 kept their revision-1 expectations and now pass because the code was fixed.

## New cases from the revised PDF

All of these pass, except AL-04 and AL-07, which need a device.

| ID | PDF | Checks | Result |
|---|---|---|---|
| DU-20b | 6.6 p46 | One checked copy counts as checking every copy in an exact group | PASS |
| DU-22b | 6.6 p46 | The "Not checked yet" label clears once the photo is checked | PASS |
| DU-31 | 6.6 p46 | Held labels "Protected · favourite", "Never-touch album" and "Album rules unavailable"; none of them is pre-selected | PASS |
| DU-32 | 6.6 p46-47 | "Keep best + contact sheet" is offered only for bursts with nothing held besides the keeper (`ui.contactSheetAllowed`) | PASS |
| QR-21 | 6.5 p45 | Favourite, recent and Never-touch extras never enter Quick review | PASS |
| CM-13 (extended) | 6.7 p47 | The " not checked for ID documents yet — they appear here once checked." line is shipped | PASS |
| CM-14 | 6.7 p48 | Never-touch albums are not offered; nothing is offered when album rules are unreadable | PASS |
| CM-15 | 6.7 p48 | Held items still count in folder totals | PASS |
| CM-10 (rewritten) | 6.7 p47 | Unchecked photos are counted (N, size) for the not-checked line | PASS |
| ST-08 | §1 p5, 9.6 p70, 9.7 p71 | Backup exclusion: `allowBackup="false"`, and the data-extraction rules exclude every app domain in both cloud-backup and device-transfer | PASS |
| SV-07b | 9.3 p67 | A vault copy with the same length but a different SHA-256 is rejected | PASS |
| AL-06 | 8.1 p59-60 | The no-screen-lock "Send these files?" dialog texts and "Not sent" are shipped | PASS |
| NT-01 (extended) | 9.5 p70 | Android daily-time-limit text is shipped | PASS |
| AL-04 (reversed) | 9.4 p68, 9.5 p70 | The lock now also guards notification taps and "Go to Ready to clear" | device-only |
| AL-07 | 8.1 p59-60 | The no-screen-lock dialog actually appears, and Cancel leads to "Not sent" | device-only |

## Per-case results

Every automated case passes. The 46 NOT AUTOMATED cases are listed below.

| Area | Automated (PASS) | NOT AUTOMATED |
|---|---|---|
| Clean up lists, SF | CL-01–CL-33, CL-36, SF-01, SF-01b (36) | CL-34, CL-35, CL-37, CL-38, SF-03 |
| Low quality | LQ-01–LQ-12, LQ-17 (13) | LQ-13–LQ-16 |
| ID shield | ID-01–ID-13, ID-15, ID-18 (15) | ID-16, ID-17 |
| Expiry | EX-01–EX-15, EX-15b (16) | EX-16, EX-17 |
| Swipe review | SW-01 (1) | SW-02–SW-05 |
| Quick review | QR-01–QR-13, QR-15, QR-18–QR-21 (18) | QR-14, QR-16, QR-17 |
| Duplicates | DU-01–DU-25, DU-13b, DU-20b, DU-22b, DU-27, DU-29, DU-31, DU-32 (32) | DU-26, DU-28, DU-30 |
| Chat media | CM-01–CM-07, CM-09–CM-11, CM-13–CM-15 (13) | CM-12 (CM-08 is structural) |
| Standing rules | SR-01–SR-04, SR-06b (5) | SR-05–SR-08 |
| Low space | LS-01–LS-06 (6) | LS-07, LS-08 |
| Widget / forecast | WF-01–WF-13 (13) | WF-14–WF-16 |
| Your month | MR-01–MR-07 (7) | MR-08–MR-11 |
| Search | SE-01–SE-09, SE-11 (10) | SE-10 |
| Share small | SS-01–SS-05, SS-07 (6) | SS-06 |
| Vault | SV-01–SV-07, SV-07b, SV-09 (9) | SV-08 |
| App lock / send confirm | AL-01, AL-02, AL-06 (3) | AL-03, AL-04, AL-07 |
| Notifications | NT-01 (1) | NT-02 |
| Settings | ST-01–ST-06, ST-08 (7) | ST-07 |
| Privacy / How it works | PR-01, PR-02 (2) | – |
| Hindi | HI-01–HI-06, HI-06b (7) | HI-07 |

## Ambiguities and test notes

- **Forecast threshold.** "500 MB" in the forecast is not defined as decimal or binary in the PDF; the code uses 500 MiB. WF-09 and WF-11 accept either.
- **Rounding.** MR-03 and MR-04 avoid values where the rounding of "÷ 3,000,000" and "whole MB" would matter.
- **Chat media buttons.** The month buttons are built from `MONTH_CHOICES` at run time, so the copy check looks only for the literal "1 year". CM-05 checks the choices.

## Device-only cases for a human tester

**P1:**

| Case | What to check |
|---|---|
| CL-35, SF-03 | No removal without Android's dialog; Cancel changes nothing. |
| QR-14 | Frozen queue; one dialog. |
| DU-28 | Contact sheet saved first; Cancel deletes the sheet. |
| CM-12 | Swipe review goes straight to Android's dialog; check the not-checked line on the device. |
| EX-16 | An expired item is never auto-deleted. |
| LQ-14 | A blurry favourite is not listed. |
| SV-08 | Vault flows. |
| AL-03 | App-lock entry points. |
| AL-04 | The lock now covers notification taps and "Go to Ready to clear". |
| AL-07 | The no-screen-lock "Send these files?" dialog appears. |

**P2:** CL-34, CL-37, SW-03, SW-05, ID-17, EX-17, LQ-13, DU-26 (old keeper re-selected only if removable), SR-05–SR-07, LS-07, MR-08, MR-10, SS-06, NT-02, ST-07.

**P3:** CL-38, LQ-15, LQ-16, ID-16, SW-02, QR-16, QR-17, DU-30, SR-08, LS-08, WF-14–WF-16, MR-09, MR-11, SE-10, HI-07.
